use std::time::{Duration, Instant};

use async_trait::async_trait;
use bytes::Bytes;
use futures_util::{SinkExt, StreamExt};
use srt_tokio::{SrtSocket, SrtSocketBuilder};

use crate::config::{CoreConfig, LiveTarget};
use crate::error::{CoreError, CoreErrorCode, CoreResult};

#[derive(Clone, Debug, Default)]
pub struct TransportStats {
    pub rtt_ms: u64,
    pub retransmitted_packets: u64,
}

#[async_trait]
pub trait Transport: Send {
    async fn send(&mut self, bytes: &[u8]) -> CoreResult<()>;
    async fn close(&mut self) -> CoreResult<()>;
    async fn stats(&mut self) -> CoreResult<TransportStats> {
        Ok(TransportStats::default())
    }
}

#[async_trait]
pub trait TransportFactory: Send + Sync {
    async fn connect(
        &self,
        target: &LiveTarget,
        cfg: &CoreConfig,
    ) -> CoreResult<Box<dyn Transport>>;
}

#[derive(Default)]
pub struct SrtTransportFactory;

#[async_trait]
impl TransportFactory for SrtTransportFactory {
    async fn connect(
        &self,
        target: &LiveTarget,
        cfg: &CoreConfig,
    ) -> CoreResult<Box<dyn Transport>> {
        target.validate()?;
        let endpoint = target.endpoint()?;
        let builder: SrtSocketBuilder = SrtSocket::builder()
            .send_latency(Duration::from_millis(cfg.srt_latency_ms))
            .receive_latency(Duration::from_millis(cfg.srt_peer_latency_ms))
            .encryption(16, target.passphrase().to_owned());
        let socket = builder
            .call(endpoint.as_str(), Some(target.stream_id()))
            .await
            .map_err(|error| transport_error("SRT caller connection failed", error))?;
        Ok(Box::new(SrtTransport { socket }))
    }
}

struct SrtTransport {
    socket: SrtSocket,
}

#[async_trait]
impl Transport for SrtTransport {
    async fn send(&mut self, bytes: &[u8]) -> CoreResult<()> {
        self.socket
            .send((Instant::now(), Bytes::copy_from_slice(bytes)))
            .await
            .map_err(|error| transport_error("SRT send failed", error))
    }

    async fn close(&mut self) -> CoreResult<()> {
        self.socket
            .close_and_finish()
            .await
            .map_err(|error| transport_error("SRT close failed", error))
    }

    async fn stats(&mut self) -> CoreResult<TransportStats> {
        let next = tokio::time::timeout(Duration::from_millis(5), self.socket.statistics().next())
            .await
            .ok()
            .flatten();
        Ok(
            next.map_or_else(TransportStats::default, |stats| TransportStats {
                rtt_ms: stats.tx_average_rtt.as_millis().min(u128::from(u64::MAX)) as u64,
                retransmitted_packets: stats.tx_retransmit_data,
            }),
        )
    }
}

fn transport_error(message: &str, _error: impl std::fmt::Debug) -> CoreError {
    CoreError::new(CoreErrorCode::TransportFailed, message, true).with_cause("SRT_ERROR")
}
