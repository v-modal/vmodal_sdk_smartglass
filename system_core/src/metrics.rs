use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Instant;

use serde::{Deserialize, Serialize};

use crate::config::CONTRACT_VERSION;

#[derive(Clone, Debug, Default, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MetricsSnapshot {
    pub contract_version: u16,
    pub accepted_samples: u64,
    pub dropped_samples: u64,
    pub keyframe_requests: u64,
    pub sent_bytes: u64,
    pub reconnects: u64,
    pub rtt_ms: u64,
    pub retransmitted_packets: u64,
    pub queue_bytes: u64,
    pub queue_depth: u64,
    pub uptime_ms: u64,
}

pub struct Metrics {
    started: Instant,
    accepted_samples: AtomicU64,
    dropped_samples: AtomicU64,
    keyframe_requests: AtomicU64,
    sent_bytes: AtomicU64,
    reconnects: AtomicU64,
    rtt_ms: AtomicU64,
    retransmitted_packets: AtomicU64,
    queue_bytes: AtomicU64,
    queue_depth: AtomicU64,
}

impl Default for Metrics {
    fn default() -> Self {
        Self {
            started: Instant::now(),
            accepted_samples: AtomicU64::new(0),
            dropped_samples: AtomicU64::new(0),
            keyframe_requests: AtomicU64::new(0),
            sent_bytes: AtomicU64::new(0),
            reconnects: AtomicU64::new(0),
            rtt_ms: AtomicU64::new(0),
            retransmitted_packets: AtomicU64::new(0),
            queue_bytes: AtomicU64::new(0),
            queue_depth: AtomicU64::new(0),
        }
    }
}

impl Metrics {
    pub fn accepted(&self) {
        self.accepted_samples.fetch_add(1, Ordering::Relaxed);
    }

    pub fn dropped(&self) {
        self.dropped_samples.fetch_add(1, Ordering::Relaxed);
    }

    pub fn keyframe_requested(&self) {
        self.keyframe_requests.fetch_add(1, Ordering::Relaxed);
    }

    pub fn sent(&self, bytes: usize) {
        self.sent_bytes.fetch_add(bytes as u64, Ordering::Relaxed);
    }

    pub fn reconnected(&self) {
        self.reconnects.fetch_add(1, Ordering::Relaxed);
    }

    pub fn set_transport(&self, rtt_ms: u64, retransmitted_packets: u64) {
        self.rtt_ms.store(rtt_ms, Ordering::Relaxed);
        self.retransmitted_packets
            .store(retransmitted_packets, Ordering::Relaxed);
    }

    pub fn set_queue(&self, bytes: usize, depth: usize) {
        self.queue_bytes.store(bytes as u64, Ordering::Relaxed);
        self.queue_depth.store(depth as u64, Ordering::Relaxed);
    }

    pub fn snapshot(&self) -> MetricsSnapshot {
        MetricsSnapshot {
            contract_version: CONTRACT_VERSION,
            accepted_samples: self.accepted_samples.load(Ordering::Relaxed),
            dropped_samples: self.dropped_samples.load(Ordering::Relaxed),
            keyframe_requests: self.keyframe_requests.load(Ordering::Relaxed),
            sent_bytes: self.sent_bytes.load(Ordering::Relaxed),
            reconnects: self.reconnects.load(Ordering::Relaxed),
            rtt_ms: self.rtt_ms.load(Ordering::Relaxed),
            retransmitted_packets: self.retransmitted_packets.load(Ordering::Relaxed),
            queue_bytes: self.queue_bytes.load(Ordering::Relaxed),
            queue_depth: self.queue_depth.load(Ordering::Relaxed),
            uptime_ms: self.started.elapsed().as_millis().min(u128::from(u64::MAX)) as u64,
        }
    }
}
