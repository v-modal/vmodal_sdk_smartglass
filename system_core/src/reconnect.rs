use std::time::Duration;

use crate::config::CoreConfig;

#[derive(Clone, Debug)]
pub struct ReconnectPolicy {
    delays_ms: Vec<u64>,
    jitter_percent: u8,
    timeout_ms: u64,
}

impl ReconnectPolicy {
    pub fn new(cfg: &CoreConfig) -> Self {
        Self {
            delays_ms: cfg.reconnect_delays_ms.clone(),
            jitter_percent: cfg.reconnect_jitter_percent,
            timeout_ms: cfg.disconnect_timeout_ms,
        }
    }

    pub fn delay(&self, attempt: u32, seed: u64) -> Duration {
        let index = (attempt as usize)
            .saturating_sub(1)
            .min(self.delays_ms.len() - 1);
        let base = self.delays_ms[index];
        if self.jitter_percent == 0 {
            return Duration::from_millis(base);
        }
        let span = base.saturating_mul(u64::from(self.jitter_percent)) / 100;
        let width = span.saturating_mul(2).saturating_add(1);
        let mixed = seed
            .wrapping_add(u64::from(attempt).wrapping_mul(0x9E37_79B9_7F4A_7C15))
            .rotate_left(17);
        let offset = mixed % width;
        Duration::from_millis(base.saturating_sub(span).saturating_add(offset))
    }

    pub fn timed_out(&self, disconnected: Duration) -> bool {
        disconnected >= Duration::from_millis(self.timeout_ms)
    }
}
