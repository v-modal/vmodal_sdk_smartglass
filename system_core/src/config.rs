use std::fmt;
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use url::{Host, Url};
use zeroize::{Zeroize, ZeroizeOnDrop};

use crate::error::{CoreError, CoreErrorCode, CoreResult};

pub const CONTRACT_VERSION: u16 = 1;
pub const DEFAULT_VIDEO_PID: u16 = 0x0100;
pub const DEFAULT_PMT_PID: u16 = 0x1000;

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(default, rename_all = "camelCase")]
pub struct CoreConfig {
    pub contract_version: u16,
    pub encoded_queue_max_bytes: usize,
    pub encoded_queue_max_duration_ms: u64,
    pub event_queue_capacity: usize,
    pub srt_latency_ms: u64,
    pub srt_peer_latency_ms: u64,
    pub reconnect_delays_ms: Vec<u64>,
    pub reconnect_jitter_percent: u8,
    pub disconnect_timeout_ms: u64,
    pub mpeg_ts_program_number: u16,
    pub video_pid: u16,
    pub pmt_pid: u16,
    pub table_interval_ms: u64,
    pub max_sample_bytes: usize,
    pub block_pool_capacity: usize,
}

impl Default for CoreConfig {
    fn default() -> Self {
        Self {
            contract_version: CONTRACT_VERSION,
            encoded_queue_max_bytes: 8 * 1024 * 1024,
            encoded_queue_max_duration_ms: 2_000,
            event_queue_capacity: 128,
            srt_latency_ms: 1_200,
            srt_peer_latency_ms: 1_200,
            reconnect_delays_ms: vec![1_000, 2_000, 4_000, 8_000, 15_000],
            reconnect_jitter_percent: 10,
            disconnect_timeout_ms: 60_000,
            mpeg_ts_program_number: 1,
            video_pid: DEFAULT_VIDEO_PID,
            pmt_pid: DEFAULT_PMT_PID,
            table_interval_ms: 500,
            max_sample_bytes: 2 * 1024 * 1024,
            block_pool_capacity: 96,
        }
    }
}

impl CoreConfig {
    pub fn from_bytes(bytes: &[u8]) -> CoreResult<Self> {
        let cfg: Self = serde_json::from_slice(bytes)
            .map_err(|_| CoreError::invalid_config("core config must be valid UTF-8 JSON"))?;
        cfg.validate()?;
        Ok(cfg)
    }

    pub fn validate(&self) -> CoreResult<()> {
        if self.contract_version != CONTRACT_VERSION {
            return Err(CoreError::new(
                CoreErrorCode::ContractMismatch,
                "unsupported core contract version",
                false,
            ));
        }
        if self.encoded_queue_max_bytes == 0 || self.encoded_queue_max_duration_ms == 0 {
            return Err(CoreError::invalid_config(
                "encoded queue bounds must be positive",
            ));
        }
        if self.event_queue_capacity < 2 {
            return Err(CoreError::invalid_config(
                "event queue capacity must be at least 2",
            ));
        }
        if !(20..=8_000).contains(&self.srt_latency_ms)
            || !(20..=8_000).contains(&self.srt_peer_latency_ms)
        {
            return Err(CoreError::invalid_config(
                "SRT latency must be between 20 and 8000 ms",
            ));
        }
        if self.reconnect_delays_ms.is_empty() || self.reconnect_delays_ms.contains(&0) {
            return Err(CoreError::invalid_config(
                "reconnect delays must be positive",
            ));
        }
        if self.reconnect_jitter_percent > 25 {
            return Err(CoreError::invalid_config(
                "reconnect jitter must not exceed 25 percent",
            ));
        }
        if self.disconnect_timeout_ms < self.reconnect_delays_ms[0] {
            return Err(CoreError::invalid_config(
                "disconnect timeout is shorter than first retry",
            ));
        }
        if self.video_pid == 0 || self.video_pid == self.pmt_pid || self.pmt_pid == 0 {
            return Err(CoreError::invalid_config(
                "MPEG-TS PIDs must be distinct and nonzero",
            ));
        }
        if self.max_sample_bytes == 0 || self.max_sample_bytes > self.encoded_queue_max_bytes {
            return Err(CoreError::invalid_config(
                "sample bound must fit within queue byte bound",
            ));
        }
        if self.block_pool_capacity == 0 {
            return Err(CoreError::invalid_config(
                "block pool capacity must be positive",
            ));
        }
        Ok(())
    }
}

#[derive(Clone, Deserialize, Serialize, Zeroize, ZeroizeOnDrop)]
#[serde(transparent)]
pub struct SecretString(String);

impl SecretString {
    pub fn new(value: String) -> Self {
        Self(value)
    }

    pub(crate) fn expose(&self) -> &str {
        &self.0
    }
}

impl fmt::Debug for SecretString {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("<redacted>")
    }
}

#[derive(Clone, Deserialize, Serialize, Zeroize, ZeroizeOnDrop)]
#[serde(rename_all = "camelCase")]
pub struct LiveTarget {
    #[zeroize(skip)]
    pub contract_version: u16,
    pub session_id: String,
    pub url: String,
    pub stream_id: SecretString,
    pub passphrase: SecretString,
    #[zeroize(skip)]
    pub expires_at_epoch_ms: Option<u64>,
}

impl fmt::Debug for LiveTarget {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let host = Url::parse(&self.url)
            .ok()
            .and_then(|url| url.host_str().map(str::to_owned))
            .unwrap_or_else(|| "<invalid>".to_owned());
        f.debug_struct("LiveTarget")
            .field("contract_version", &self.contract_version)
            .field("session_id", &self.session_id)
            .field("host", &host)
            .field("stream_id", &"<redacted>")
            .field("passphrase", &"<redacted>")
            .field("expires_at_epoch_ms", &self.expires_at_epoch_ms)
            .finish()
    }
}

impl LiveTarget {
    pub fn from_bytes(bytes: &[u8]) -> CoreResult<Self> {
        let target: Self = serde_json::from_slice(bytes).map_err(|_| {
            CoreError::new(
                CoreErrorCode::InvalidTarget,
                "target must be valid UTF-8 JSON",
                false,
            )
        })?;
        target.validate()?;
        Ok(target)
    }

    pub fn validate(&self) -> CoreResult<()> {
        if self.contract_version != CONTRACT_VERSION {
            return Err(CoreError::new(
                CoreErrorCode::ContractMismatch,
                "unsupported target contract version",
                false,
            ));
        }
        if self.session_id.is_empty() || self.session_id.len() > 128 {
            return Err(CoreError::new(
                CoreErrorCode::InvalidTarget,
                "target session ID is invalid",
                false,
            ));
        }
        let url = Url::parse(&self.url).map_err(|_| {
            CoreError::new(CoreErrorCode::InvalidTarget, "target URL is invalid", false)
        })?;
        if url.scheme() != "srt" || url.host_str().is_none() || url.port().is_none() {
            return Err(CoreError::new(
                CoreErrorCode::InvalidTarget,
                "target URL must be srt://host:port",
                false,
            ));
        }
        if !url.username().is_empty() || url.password().is_some() || url.query().is_some() {
            return Err(CoreError::new(
                CoreErrorCode::InvalidTarget,
                "credentials and query parameters must be separate from target URL",
                false,
            ));
        }
        if self.stream_id.expose().is_empty() || self.stream_id.expose().len() > 512 {
            return Err(CoreError::new(
                CoreErrorCode::InvalidTarget,
                "target stream ID is invalid",
                false,
            ));
        }
        if !(10..=79).contains(&self.passphrase.expose().len()) {
            return Err(CoreError::new(
                CoreErrorCode::InvalidTarget,
                "target passphrase length is invalid",
                false,
            ));
        }
        if self.is_expired(now_epoch_ms()) {
            return Err(CoreError::new(
                CoreErrorCode::TargetExpired,
                "target is expired",
                true,
            ));
        }
        Ok(())
    }

    pub fn endpoint(&self) -> CoreResult<String> {
        let url = Url::parse(&self.url).map_err(|_| {
            CoreError::new(CoreErrorCode::InvalidTarget, "target URL is invalid", false)
        })?;
        let host = match url.host() {
            Some(Host::Ipv6(address)) => format!("[{address}]"),
            Some(host) => host.to_string(),
            None => String::new(),
        };
        Ok(format!("{host}:{}", url.port().unwrap_or(0)))
    }

    pub fn stream_id(&self) -> &str {
        self.stream_id.expose()
    }

    pub fn passphrase(&self) -> &str {
        self.passphrase.expose()
    }

    pub fn is_expired(&self, now_ms: u64) -> bool {
        self.expires_at_epoch_ms
            .is_some_and(|expires| expires <= now_ms)
    }
}

fn now_epoch_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .min(u128::from(u64::MAX)) as u64
}
