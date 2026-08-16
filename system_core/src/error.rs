use serde::{Deserialize, Serialize};
use thiserror::Error;

pub type CoreResult<T> = Result<T, CoreError>;

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[repr(i32)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CoreErrorCode {
    ContractMismatch = 1001,
    InvalidConfig = 1002,
    InvalidTarget = 1003,
    TargetExpired = 1004,
    InvalidSample = 1005,
    InvalidHandle = 1006,
    InvalidState = 1007,
    QueueStopped = 1008,
    H264Malformed = 1009,
    TransportFailed = 1010,
    TransportTimeout = 1011,
    Cancelled = 1012,
    InternalInvariant = 1099,
}

#[derive(Clone, Debug, Deserialize, Error, Eq, PartialEq, Serialize)]
#[error("{code:?}: {message}")]
pub struct CoreError {
    pub code: CoreErrorCode,
    pub message: String,
    pub recoverable: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cause_code: Option<String>,
}

impl CoreError {
    pub fn new(code: CoreErrorCode, message: impl Into<String>, recoverable: bool) -> Self {
        Self {
            code,
            message: message.into(),
            recoverable,
            cause_code: None,
        }
    }

    pub fn with_cause(mut self, cause_code: impl Into<String>) -> Self {
        self.cause_code = Some(cause_code.into());
        self
    }

    pub fn invalid_config(message: impl Into<String>) -> Self {
        Self::new(CoreErrorCode::InvalidConfig, message, false)
    }

    pub fn invalid_sample(message: impl Into<String>) -> Self {
        Self::new(CoreErrorCode::InvalidSample, message, false)
    }

    pub fn internal(message: impl Into<String>) -> Self {
        Self::new(CoreErrorCode::InternalInvariant, message, false)
    }
}
