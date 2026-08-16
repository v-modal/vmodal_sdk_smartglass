//! Platform-neutral smart-glasses streaming core.

pub mod buffer;
pub mod config;
pub mod error;
pub mod event;
pub mod ffi;
pub mod h264;
pub mod metrics;
pub mod mpegts;
pub mod reconnect;
pub mod session;
pub mod transport;

pub use buffer::{EncodedSample, PushResult, SampleFlags};
pub use config::{CONTRACT_VERSION, CoreConfig, LiveTarget};
pub use error::{CoreError, CoreErrorCode, CoreResult};
pub use event::{EventKind, EventRecord};
pub use h264::H264Framing;
pub use metrics::MetricsSnapshot;
pub use session::{CoreSession, SessionState};
