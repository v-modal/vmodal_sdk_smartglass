use std::collections::VecDeque;
use std::sync::{Condvar, Mutex};
use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::config::CONTRACT_VERSION;

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum EventKind {
    TransportConnecting,
    TransportConnected,
    TransportDisconnected,
    NeedKeyframe,
    FrameDropped,
    TargetRefreshRequired,
    Stopped,
    Failed,
    Metrics,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EventRecord {
    pub contract_version: u16,
    pub kind: EventKind,
    pub terminal: bool,
    pub code: i32,
    pub value: u64,
    pub message: String,
}

impl EventRecord {
    pub fn new(kind: EventKind, code: i32, value: u64, message: impl Into<String>) -> Self {
        Self {
            contract_version: CONTRACT_VERSION,
            kind,
            terminal: matches!(kind, EventKind::Stopped | EventKind::Failed),
            code,
            value,
            message: message.into(),
        }
    }
}

struct EventState {
    items: VecDeque<EventRecord>,
    closed: bool,
}

pub struct EventQueue {
    capacity: usize,
    state: Mutex<EventState>,
    ready: Condvar,
}

impl EventQueue {
    pub fn new(capacity: usize) -> Self {
        Self {
            capacity,
            state: Mutex::new(EventState {
                items: VecDeque::with_capacity(capacity),
                closed: false,
            }),
            ready: Condvar::new(),
        }
    }

    pub fn push(&self, event: EventRecord) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.closed {
            return;
        }
        if let Some(existing) = state
            .items
            .iter_mut()
            .find(|item| !event.terminal && item.kind == event.kind && !item.terminal)
        {
            *existing = event;
            self.ready.notify_one();
            return;
        }
        if state.items.len() == self.capacity {
            let removable = state.items.iter().position(|item| !item.terminal);
            match removable {
                Some(index) => {
                    state.items.remove(index);
                }
                None if !event.terminal => return,
                None => {
                    state.items.pop_front();
                }
            }
        }
        state.items.push_back(event);
        self.ready.notify_one();
    }

    pub fn poll(&self, timeout: Duration) -> Option<EventRecord> {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.items.is_empty() && !state.closed {
            let result = self.ready.wait_timeout(state, timeout);
            state = match result {
                Ok((guard, _)) => guard,
                Err(error) => error.into_inner().0,
            };
        }
        state.items.pop_front()
    }

    pub fn close(&self) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        state.closed = true;
        self.ready.notify_all();
    }

    pub fn len(&self) -> usize {
        self.state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .items
            .len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}
