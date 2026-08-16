use serde::{Deserialize, Serialize};

use crate::buffer::{EncodedSample, SampleFlags};
use crate::error::{CoreError, CoreErrorCode, CoreResult};

const START_CODE: [u8; 4] = [0, 0, 0, 1];

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[repr(u8)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum H264Framing {
    Avcc = 1,
    AnnexB = 2,
}

impl H264Framing {
    pub fn from_codec_config(bytes: &[u8]) -> CoreResult<Self> {
        if bytes.starts_with(&START_CODE) || bytes.starts_with(&[0, 0, 1]) {
            return Ok(Self::AnnexB);
        }
        if bytes.len() >= 7 && bytes[0] == 1 {
            return Ok(Self::Avcc);
        }
        Err(h264_error("codec configuration framing is unknown"))
    }
}

pub struct H264Normalizer {
    config: Vec<u8>,
    generation: Option<u32>,
    max_sample_bytes: usize,
}

impl H264Normalizer {
    pub fn new(max_sample_bytes: usize) -> Self {
        Self {
            config: Vec::new(),
            generation: None,
            max_sample_bytes,
        }
    }

    pub fn configure(
        &mut self,
        bytes: &[u8],
        framing: H264Framing,
        generation: u32,
    ) -> CoreResult<()> {
        if bytes.is_empty() || bytes.len() > self.max_sample_bytes {
            return Err(h264_error("codec configuration size is invalid"));
        }
        if framing == H264Framing::Avcc
            && bytes.first() == Some(&1)
            && bytes.get(4).is_some_and(|value| value & 0x03 != 0x03)
        {
            return Err(h264_error("only four-byte AVCC NAL lengths are supported"));
        }
        let nals = match framing {
            H264Framing::AnnexB => annex_b_nals(bytes)?,
            H264Framing::Avcc if bytes.first() == Some(&1) => avcc_config_nals(bytes)?,
            H264Framing::Avcc => avcc_nals(bytes, 4)?,
        };
        let mut config = Vec::new();
        for nal in nals {
            if matches!(nal_type(nal), 7 | 8) {
                config.extend_from_slice(&START_CODE);
                config.extend_from_slice(nal);
            }
        }
        if config.is_empty() {
            return Err(h264_error("codec configuration has no SPS/PPS"));
        }
        self.config = config;
        self.generation = Some(generation);
        Ok(())
    }

    pub fn normalize(&mut self, sample: &EncodedSample) -> CoreResult<Option<Vec<u8>>> {
        if sample.flags.contains(SampleFlags::CODEC_CONFIG) {
            self.configure(&sample.bytes, sample.framing, sample.generation)?;
            return Ok(None);
        }
        if self.generation != Some(sample.generation) {
            self.config.clear();
            self.generation = Some(sample.generation);
        }
        if sample.bytes.len() > self.max_sample_bytes {
            return Err(h264_error("access unit exceeds configured size"));
        }
        let nals = match sample.framing {
            H264Framing::AnnexB => annex_b_nals(&sample.bytes)?,
            H264Framing::Avcc => avcc_nals(&sample.bytes, 4)?,
        };
        let has_idr = nals.iter().any(|nal| nal_type(nal) == 5);
        if sample.is_keyframe() && !has_idr {
            return Err(h264_error("keyframe sample does not contain an IDR NAL"));
        }
        let has_sps = nals.iter().any(|nal| nal_type(nal) == 7);
        let has_pps = nals.iter().any(|nal| nal_type(nal) == 8);
        if sample.is_keyframe() && (!has_sps || !has_pps) && self.config.is_empty() {
            return Err(h264_error("IDR has no cached SPS/PPS"));
        }

        let mut out = Vec::with_capacity(sample.bytes.len() + self.config.len() + 32);
        if sample.is_keyframe() && (!has_sps || !has_pps) {
            out.extend_from_slice(&self.config);
        }
        for nal in nals {
            out.extend_from_slice(&START_CODE);
            out.extend_from_slice(nal);
        }
        Ok(Some(out))
    }

    pub fn reset_transport(&mut self) {
        // Codec configuration remains valid, but the next IDR will prepend it.
    }
}

fn h264_error(message: impl Into<String>) -> CoreError {
    CoreError::new(CoreErrorCode::H264Malformed, message, false)
}

fn nal_type(nal: &[u8]) -> u8 {
    nal.first().copied().unwrap_or_default() & 0x1f
}

fn annex_b_nals(bytes: &[u8]) -> CoreResult<Vec<&[u8]>> {
    let mut starts = Vec::new();
    let mut pos = 0;
    while pos + 3 <= bytes.len() {
        let size = if pos + 4 <= bytes.len() && bytes[pos..pos + 4] == START_CODE {
            4
        } else if bytes[pos..pos + 3] == [0, 0, 1] {
            3
        } else {
            pos += 1;
            continue;
        };
        starts.push((pos, size));
        pos += size;
    }
    if starts.is_empty() || starts[0].0 != 0 {
        return Err(h264_error("Annex-B access unit has no leading start code"));
    }
    let mut nals = Vec::with_capacity(starts.len());
    for (index, (start, size)) in starts.iter().copied().enumerate() {
        let end = starts.get(index + 1).map_or(bytes.len(), |next| next.0);
        let nal = &bytes[start + size..end];
        if nal.is_empty() {
            return Err(h264_error("Annex-B access unit contains an empty NAL"));
        }
        nals.push(nal);
    }
    Ok(nals)
}

fn avcc_nals(bytes: &[u8], length_size: usize) -> CoreResult<Vec<&[u8]>> {
    if !(1..=4).contains(&length_size) {
        return Err(h264_error("AVCC NAL length size is invalid"));
    }
    let mut nals = Vec::new();
    let mut pos = 0;
    while pos < bytes.len() {
        if pos + length_size > bytes.len() {
            return Err(h264_error("AVCC NAL length is truncated"));
        }
        let mut size = 0usize;
        for byte in &bytes[pos..pos + length_size] {
            size = size
                .checked_mul(256)
                .and_then(|value| value.checked_add(usize::from(*byte)))
                .ok_or_else(|| h264_error("AVCC NAL length overflows"))?;
        }
        pos += length_size;
        if size == 0 || pos.checked_add(size).is_none_or(|end| end > bytes.len()) {
            return Err(h264_error("AVCC NAL size exceeds access unit"));
        }
        nals.push(&bytes[pos..pos + size]);
        pos += size;
    }
    if nals.is_empty() {
        return Err(h264_error("AVCC access unit is empty"));
    }
    Ok(nals)
}

fn avcc_config_nals(bytes: &[u8]) -> CoreResult<Vec<&[u8]>> {
    if bytes.len() < 7 || bytes[0] != 1 {
        return Err(h264_error("AVC decoder configuration record is truncated"));
    }
    let mut pos = 6usize;
    let mut nals = Vec::new();
    let sps_count = usize::from(bytes[5] & 0x1f);
    for _ in 0..sps_count {
        nals.push(read_config_nal(bytes, &mut pos)?);
    }
    if pos >= bytes.len() {
        return Err(h264_error(
            "AVC decoder configuration record has no PPS count",
        ));
    }
    let pps_count = usize::from(bytes[pos]);
    pos += 1;
    for _ in 0..pps_count {
        nals.push(read_config_nal(bytes, &mut pos)?);
    }
    if nals.is_empty() {
        return Err(h264_error("AVC decoder configuration record has no NALs"));
    }
    Ok(nals)
}

fn read_config_nal<'a>(bytes: &'a [u8], pos: &mut usize) -> CoreResult<&'a [u8]> {
    if *pos + 2 > bytes.len() {
        return Err(h264_error(
            "AVC decoder configuration NAL length is truncated",
        ));
    }
    let size = usize::from(u16::from_be_bytes([bytes[*pos], bytes[*pos + 1]]));
    *pos += 2;
    if size == 0 || pos.checked_add(size).is_none_or(|end| end > bytes.len()) {
        return Err(h264_error("AVC decoder configuration NAL is truncated"));
    }
    let nal = &bytes[*pos..*pos + size];
    *pos += size;
    Ok(nal)
}
