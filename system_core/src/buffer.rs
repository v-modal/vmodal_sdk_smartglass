use std::collections::VecDeque;

use bitflags::bitflags;
use serde::{Deserialize, Serialize};

use crate::error::{CoreError, CoreResult};
use crate::h264::H264Framing;

bitflags! {
    #[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
    pub struct SampleFlags: u32 {
        const CODEC_CONFIG = 1 << 0;
        const KEY_FRAME = 1 << 1;
        const END_OF_STREAM = 1 << 2;
    }
}

#[derive(Debug)]
pub struct EncodedSample {
    pub bytes: Vec<u8>,
    pub pts_us: u64,
    pub flags: SampleFlags,
    pub generation: u32,
    pub framing: H264Framing,
}

impl EncodedSample {
    pub fn is_keyframe(&self) -> bool {
        self.flags.contains(SampleFlags::KEY_FRAME)
    }

    pub fn is_config(&self) -> bool {
        self.flags.contains(SampleFlags::CODEC_CONFIG)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(i32)]
pub enum PushResult {
    Accepted = 0,
    BackpressureDrop = 1,
    NeedKeyframe = 2,
    Stopped = 3,
    InvalidSample = 4,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum IntakeState {
    AwaitingKeyframe,
    Accepting,
    Stopping,
    Stopped,
}

struct BlockPool {
    free: Vec<Vec<u8>>,
    capacity: usize,
}

impl BlockPool {
    fn new(capacity: usize) -> Self {
        Self {
            free: Vec::with_capacity(capacity),
            capacity,
        }
    }

    fn copy(&mut self, bytes: &[u8]) -> Vec<u8> {
        let mut block = self.free.pop().unwrap_or_default();
        block.clear();
        block.extend_from_slice(bytes);
        block
    }

    fn release(&mut self, mut block: Vec<u8>) {
        if self.free.len() < self.capacity {
            block.clear();
            self.free.push(block);
        }
    }
}

pub struct EncodedQueue {
    items: VecDeque<EncodedSample>,
    bytes: usize,
    max_bytes: usize,
    max_duration_us: u64,
    max_sample_bytes: usize,
    state: IntakeState,
    connected: bool,
    last_pts: Option<u64>,
    generation: Option<u32>,
    generation_framing: Option<H264Framing>,
    codec_config: Option<(Vec<u8>, H264Framing, u32)>,
    pool: BlockPool,
}

impl EncodedQueue {
    pub fn new(
        max_bytes: usize,
        max_duration_ms: u64,
        max_sample_bytes: usize,
        pool_capacity: usize,
    ) -> Self {
        Self {
            items: VecDeque::new(),
            bytes: 0,
            max_bytes,
            max_duration_us: max_duration_ms.saturating_mul(1_000),
            max_sample_bytes,
            state: IntakeState::AwaitingKeyframe,
            connected: false,
            last_pts: None,
            generation: None,
            generation_framing: None,
            codec_config: None,
            pool: BlockPool::new(pool_capacity),
        }
    }

    pub fn push(
        &mut self,
        bytes: &[u8],
        pts_us: u64,
        flags: SampleFlags,
        generation: u32,
        framing: H264Framing,
    ) -> CoreResult<PushResult> {
        if matches!(self.state, IntakeState::Stopping | IntakeState::Stopped) {
            return Ok(PushResult::Stopped);
        }
        let is_eos = flags.contains(SampleFlags::END_OF_STREAM);
        if (bytes.is_empty() && !is_eos) || bytes.len() > self.max_sample_bytes {
            return Err(CoreError::invalid_sample("encoded sample size is invalid"));
        }
        if let Some(last) = self.last_pts
            && self.generation == Some(generation)
            && pts_us < last
        {
            return Err(CoreError::invalid_sample("sample PTS is not monotonic"));
        }
        if self.generation != Some(generation) {
            self.clear_items();
            self.state = IntakeState::AwaitingKeyframe;
            self.last_pts = None;
            self.codec_config = None;
            self.generation = Some(generation);
            self.generation_framing = Some(framing);
        } else if self.generation_framing != Some(framing) {
            return Err(CoreError::invalid_sample(
                "sample framing changed within encoder generation",
            ));
        }
        self.last_pts = Some(pts_us);

        if flags.contains(SampleFlags::CODEC_CONFIG) {
            let block = self.pool.copy(bytes);
            if let Some((old, _, _)) = self.codec_config.replace((block, framing, generation)) {
                self.pool.release(old);
            }
            return Ok(PushResult::Accepted);
        }
        if is_eos && bytes.is_empty() {
            let block = self.pool.copy(bytes);
            self.items.push_back(EncodedSample {
                bytes: block,
                pts_us,
                flags,
                generation,
                framing,
            });
            return Ok(PushResult::Accepted);
        }
        if !self.connected {
            return Ok(PushResult::BackpressureDrop);
        }
        let is_key = flags.contains(SampleFlags::KEY_FRAME);
        if self.state == IntakeState::AwaitingKeyframe && !is_key {
            return Ok(PushResult::NeedKeyframe);
        }

        let duration_overflow = self
            .items
            .front()
            .is_some_and(|first| pts_us.saturating_sub(first.pts_us) > self.max_duration_us);
        if self.bytes.saturating_add(bytes.len()) > self.max_bytes || duration_overflow {
            self.clear_items();
            self.state = IntakeState::AwaitingKeyframe;
            if !is_key || bytes.len() > self.max_bytes {
                return Ok(PushResult::NeedKeyframe);
            }
        }
        let block = self.pool.copy(bytes);
        self.bytes += block.len();
        self.items.push_back(EncodedSample {
            bytes: block,
            pts_us,
            flags,
            generation,
            framing,
        });
        if is_key {
            self.state = IntakeState::Accepting;
        }
        Ok(PushResult::Accepted)
    }

    pub fn set_connected(&mut self, connected: bool) {
        if self.connected == connected {
            return;
        }
        self.connected = connected;
        self.clear_items();
        self.state = IntakeState::AwaitingKeyframe;
    }

    pub fn pop(&mut self) -> Option<EncodedSample> {
        let item = self.items.pop_front()?;
        self.bytes = self.bytes.saturating_sub(item.bytes.len());
        Some(item)
    }

    pub fn release(&mut self, sample: EncodedSample) {
        self.pool.release(sample.bytes);
    }

    pub fn codec_config(&self) -> Option<(&[u8], H264Framing, u32)> {
        self.codec_config
            .as_ref()
            .map(|(bytes, framing, generation)| (bytes.as_slice(), *framing, *generation))
    }

    pub fn stop(&mut self) {
        self.state = IntakeState::Stopping;
        self.clear_items();
        self.state = IntakeState::Stopped;
    }

    pub fn len(&self) -> usize {
        self.items.len()
    }

    pub fn is_empty(&self) -> bool {
        self.items.is_empty()
    }

    pub fn bytes_len(&self) -> usize {
        self.bytes
    }

    pub fn is_awaiting_keyframe(&self) -> bool {
        self.state == IntakeState::AwaitingKeyframe
    }

    fn clear_items(&mut self) {
        while let Some(item) = self.items.pop_front() {
            self.pool.release(item.bytes);
        }
        self.bytes = 0;
    }
}
