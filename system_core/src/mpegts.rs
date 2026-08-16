use crate::config::CoreConfig;

const TS_PACKET_SIZE: usize = 188;
const PTS_MASK: u64 = (1_u64 << 33) - 1;

pub struct MpegTsMuxer {
    program_number: u16,
    video_pid: u16,
    pmt_pid: u16,
    table_interval_90k: u64,
    continuity: [u8; 3],
    last_table_pts: Option<u64>,
}

impl MpegTsMuxer {
    pub fn new(cfg: &CoreConfig) -> Self {
        Self {
            program_number: cfg.mpeg_ts_program_number,
            video_pid: cfg.video_pid,
            pmt_pid: cfg.pmt_pid,
            table_interval_90k: cfg.table_interval_ms.saturating_mul(90),
            continuity: [0; 3],
            last_table_pts: None,
        }
    }

    pub fn reset_generation(&mut self) {
        self.continuity = [0; 3];
        self.last_table_pts = None;
    }

    pub fn mux_video(&mut self, annex_b: &[u8], pts_us: u64, keyframe: bool) -> Vec<u8> {
        let pts_90k = pts_us
            .saturating_div(1_000)
            .saturating_mul(90)
            .saturating_add(pts_us % 1_000 * 90 / 1_000);
        let emit_tables = self
            .last_table_pts
            .is_none_or(|last| pts_90k.saturating_sub(last) >= self.table_interval_90k);
        let mut out = Vec::new();
        if emit_tables {
            self.write_pat(&mut out);
            self.write_pmt(&mut out);
            self.last_table_pts = Some(pts_90k);
        }
        let pes = pes_packet(annex_b, pts_90k);
        packetize_pes(
            &mut out,
            self.video_pid,
            &pes,
            pts_90k,
            keyframe,
            &mut self.continuity[2],
        );
        out
    }

    fn write_pat(&mut self, out: &mut Vec<u8>) {
        let mut section = vec![
            0x00,
            0xB0,
            0x0D,
            0x00,
            0x01,
            0xC1,
            0x00,
            0x00,
            (self.program_number >> 8) as u8,
            self.program_number as u8,
            0xE0 | ((self.pmt_pid >> 8) as u8 & 0x1F),
            self.pmt_pid as u8,
        ];
        section.extend_from_slice(&mpeg_crc32(&section).to_be_bytes());
        write_psi_packet(out, 0, &section, &mut self.continuity[0]);
    }

    fn write_pmt(&mut self, out: &mut Vec<u8>) {
        let mut section = vec![
            0x02,
            0xB0,
            0x12,
            (self.program_number >> 8) as u8,
            self.program_number as u8,
            0xC1,
            0x00,
            0x00,
            0xE0 | ((self.video_pid >> 8) as u8 & 0x1F),
            self.video_pid as u8,
            0xF0,
            0x00,
            0x1B,
            0xE0 | ((self.video_pid >> 8) as u8 & 0x1F),
            self.video_pid as u8,
            0xF0,
            0x00,
        ];
        section.extend_from_slice(&mpeg_crc32(&section).to_be_bytes());
        write_psi_packet(out, self.pmt_pid, &section, &mut self.continuity[1]);
    }
}

fn write_psi_packet(out: &mut Vec<u8>, pid: u16, section: &[u8], continuity: &mut u8) {
    let mut packet = [0xFF; TS_PACKET_SIZE];
    packet[0] = 0x47;
    packet[1] = 0x40 | ((pid >> 8) as u8 & 0x1F);
    packet[2] = pid as u8;
    packet[3] = 0x10 | (*continuity & 0x0F);
    packet[4] = 0;
    packet[5..5 + section.len()].copy_from_slice(section);
    out.extend_from_slice(&packet);
    *continuity = (*continuity + 1) & 0x0F;
}

fn pes_packet(payload: &[u8], pts_90k: u64) -> Vec<u8> {
    let mut pes = Vec::with_capacity(payload.len() + 14);
    pes.extend_from_slice(&[0, 0, 1, 0xE0, 0, 0, 0x80, 0x80, 5]);
    pes.extend_from_slice(&encode_pts(pts_90k));
    pes.extend_from_slice(payload);
    pes
}

fn packetize_pes(
    out: &mut Vec<u8>,
    pid: u16,
    pes: &[u8],
    pts_90k: u64,
    keyframe: bool,
    continuity: &mut u8,
) {
    let mut pos = 0usize;
    let mut first = true;
    while pos < pes.len() {
        let remaining = pes.len() - pos;
        let needs_pcr = first;
        let min_adaptation_total = if needs_pcr { 8 } else { 0 };
        let max_payload = 184 - min_adaptation_total;
        let take = remaining.min(max_payload);
        let adaptation_total = if needs_pcr || take < 184 {
            184_usize.saturating_sub(take)
        } else {
            0
        };

        let mut packet = [0xFF; TS_PACKET_SIZE];
        packet[0] = 0x47;
        packet[1] = ((pid >> 8) as u8 & 0x1F) | if first { 0x40 } else { 0 };
        packet[2] = pid as u8;
        packet[3] = (if adaptation_total == 0 { 0x10 } else { 0x30 }) | (*continuity & 0x0F);
        let mut payload_pos = 4usize;
        if adaptation_total > 0 {
            packet[4] = (adaptation_total - 1) as u8;
            payload_pos += adaptation_total;
            if adaptation_total > 1 {
                packet[5] = if needs_pcr {
                    0x10 | if keyframe { 0x40 } else { 0 }
                } else {
                    0
                };
                if needs_pcr {
                    packet[6..12].copy_from_slice(&encode_pcr(pts_90k));
                }
            }
        }
        packet[payload_pos..payload_pos + take].copy_from_slice(&pes[pos..pos + take]);
        out.extend_from_slice(&packet);
        *continuity = (*continuity + 1) & 0x0F;
        pos += take;
        first = false;
    }
}

fn encode_pts(value: u64) -> [u8; 5] {
    let pts = value & PTS_MASK;
    [
        0x20 | (((pts >> 30) as u8 & 0x07) << 1) | 1,
        (pts >> 22) as u8,
        (((pts >> 15) as u8 & 0x7F) << 1) | 1,
        (pts >> 7) as u8,
        ((pts as u8 & 0x7F) << 1) | 1,
    ]
}

fn encode_pcr(base: u64) -> [u8; 6] {
    let value = base & PTS_MASK;
    [
        (value >> 25) as u8,
        (value >> 17) as u8,
        (value >> 9) as u8,
        (value >> 1) as u8,
        ((value as u8 & 1) << 7) | 0x7E,
        0,
    ]
}

fn mpeg_crc32(bytes: &[u8]) -> u32 {
    let mut crc = 0xFFFF_FFFF_u32;
    for byte in bytes {
        crc ^= u32::from(*byte) << 24;
        for _ in 0..8 {
            crc = if crc & 0x8000_0000 != 0 {
                (crc << 1) ^ 0x04C1_1DB7
            } else {
                crc << 1
            };
        }
    }
    crc
}
