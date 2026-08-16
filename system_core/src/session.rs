use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::atomic::{AtomicBool, AtomicU8, AtomicU32, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

use crate::buffer::{EncodedQueue, EncodedSample, PushResult, SampleFlags};
use crate::config::{CoreConfig, LiveTarget};
use crate::error::{CoreError, CoreErrorCode, CoreResult};
use crate::event::{EventKind, EventQueue, EventRecord};
use crate::h264::{H264Framing, H264Normalizer};
use crate::metrics::{Metrics, MetricsSnapshot};
use crate::mpegts::MpegTsMuxer;
use crate::reconnect::ReconnectPolicy;
use crate::transport::{SrtTransportFactory, Transport, TransportFactory};

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SessionState {
    Idle,
    Running,
    Stopping,
    Stopped,
    Failed,
}

struct WorkerState {
    queue: EncodedQueue,
    target: Option<LiveTarget>,
    target_version: u64,
    stop: bool,
}

pub struct CoreSession {
    cfg: CoreConfig,
    state: Mutex<SessionState>,
    shared: Arc<(Mutex<WorkerState>, Condvar)>,
    events: Arc<EventQueue>,
    metrics: Arc<Metrics>,
    worker: Mutex<Option<JoinHandle<()>>>,
    factory: Arc<dyn TransportFactory>,
    input_generation: AtomicU32,
    input_framing: AtomicU8,
    input_configured: AtomicBool,
}

impl CoreSession {
    pub fn new(cfg: CoreConfig) -> Arc<Self> {
        Self::with_factory(cfg, Arc::new(SrtTransportFactory))
    }

    pub fn with_factory(cfg: CoreConfig, factory: Arc<dyn TransportFactory>) -> Arc<Self> {
        let queue = EncodedQueue::new(
            cfg.encoded_queue_max_bytes,
            cfg.encoded_queue_max_duration_ms,
            cfg.max_sample_bytes,
            cfg.block_pool_capacity,
        );
        Arc::new(Self {
            events: Arc::new(EventQueue::new(cfg.event_queue_capacity)),
            cfg,
            state: Mutex::new(SessionState::Idle),
            shared: Arc::new((
                Mutex::new(WorkerState {
                    queue,
                    target: None,
                    target_version: 0,
                    stop: false,
                }),
                Condvar::new(),
            )),
            metrics: Arc::new(Metrics::default()),
            worker: Mutex::new(None),
            factory,
            input_generation: AtomicU32::new(1),
            input_framing: AtomicU8::new(0),
            input_configured: AtomicBool::new(false),
        })
    }

    pub fn start(self: &Arc<Self>, target: LiveTarget) -> CoreResult<()> {
        target.validate()?;
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if *state != SessionState::Idle {
            return Err(CoreError::new(
                CoreErrorCode::InvalidState,
                "session can only start from idle",
                false,
            ));
        }
        {
            let (lock, ready) = &*self.shared;
            let mut shared = lock.lock().unwrap_or_else(|e| e.into_inner());
            shared.target = Some(target);
            shared.target_version += 1;
            ready.notify_all();
        }
        *state = SessionState::Running;
        drop(state);

        let session = Arc::clone(self);
        let worker = thread::Builder::new()
            .name("smartglass-core".to_owned())
            .spawn(move || {
                let panic_session = Arc::clone(&session);
                if catch_unwind(AssertUnwindSafe(|| session.worker_main())).is_err() {
                    panic_session.fail(CoreError::internal("core runtime panic was contained"));
                }
            })
            .map_err(|_| {
                *self.state.lock().unwrap_or_else(|e| e.into_inner()) = SessionState::Failed;
                CoreError::internal("failed to start core runtime thread")
            })?;
        *self.worker.lock().unwrap_or_else(|e| e.into_inner()) = Some(worker);
        Ok(())
    }

    pub fn push_video(
        &self,
        bytes: &[u8],
        pts_us: u64,
        flags: SampleFlags,
        generation: u32,
        framing: H264Framing,
    ) -> CoreResult<PushResult> {
        if self.state() != SessionState::Running {
            return Err(CoreError::new(
                CoreErrorCode::InvalidState,
                "session is not accepting video",
                false,
            ));
        }
        let (lock, ready) = &*self.shared;
        let mut shared = lock.lock().unwrap_or_else(|e| e.into_inner());
        let result = shared
            .queue
            .push(bytes, pts_us, flags, generation, framing)?;
        match result {
            PushResult::Accepted => self.metrics.accepted(),
            PushResult::NeedKeyframe => {
                self.metrics.dropped();
                self.metrics.keyframe_requested();
                self.events.push(EventRecord::new(
                    EventKind::NeedKeyframe,
                    0,
                    1,
                    "fresh IDR required",
                ));
            }
            PushResult::BackpressureDrop => self.metrics.dropped(),
            PushResult::Stopped | PushResult::InvalidSample => {}
        }
        self.metrics
            .set_queue(shared.queue.bytes_len(), shared.queue.len());
        ready.notify_one();
        Ok(result)
    }

    pub fn push_video_auto(
        &self,
        bytes: &[u8],
        pts_us: u64,
        flags: SampleFlags,
    ) -> CoreResult<PushResult> {
        let framing = if flags.contains(SampleFlags::CODEC_CONFIG) {
            let framing = H264Framing::from_codec_config(bytes)?;
            if self.input_configured.swap(true, Ordering::AcqRel) {
                self.input_generation.fetch_add(1, Ordering::AcqRel);
            }
            self.input_framing.store(framing as u8, Ordering::Release);
            framing
        } else {
            match self.input_framing.load(Ordering::Acquire) {
                1 => H264Framing::Avcc,
                2 => H264Framing::AnnexB,
                _ if flags.contains(SampleFlags::END_OF_STREAM) && bytes.is_empty() => {
                    H264Framing::AnnexB
                }
                _ => {
                    return Err(CoreError::invalid_sample(
                        "codec configuration must declare framing before media",
                    ));
                }
            }
        };
        self.push_video(
            bytes,
            pts_us,
            flags,
            self.input_generation.load(Ordering::Acquire),
            framing,
        )
    }

    pub fn update_target(&self, target: LiveTarget) -> CoreResult<()> {
        target.validate()?;
        if self.state() != SessionState::Running {
            return Err(CoreError::new(
                CoreErrorCode::InvalidState,
                "target can only be updated while running",
                false,
            ));
        }
        let (lock, ready) = &*self.shared;
        let mut shared = lock.lock().unwrap_or_else(|e| e.into_inner());
        shared.target = Some(target);
        shared.target_version += 1;
        ready.notify_all();
        Ok(())
    }

    pub fn poll_event(&self, timeout: Duration) -> Option<EventRecord> {
        self.events.poll(timeout)
    }

    pub fn stats(&self) -> MetricsSnapshot {
        self.metrics.snapshot()
    }

    pub fn state(&self) -> SessionState {
        *self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn stop(&self, reason_code: i32) -> CoreResult<()> {
        {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            match *state {
                SessionState::Stopped => return Ok(()),
                SessionState::Idle => {
                    *state = SessionState::Stopped;
                    self.events.push(EventRecord::new(
                        EventKind::Stopped,
                        reason_code,
                        0,
                        "session stopped",
                    ));
                    return Ok(());
                }
                SessionState::Stopping => {}
                SessionState::Running | SessionState::Failed => *state = SessionState::Stopping,
            }
        }
        let (lock, ready) = &*self.shared;
        {
            let mut shared = lock.lock().unwrap_or_else(|e| e.into_inner());
            shared.stop = true;
            shared.queue.stop();
            shared.target = None;
            ready.notify_all();
        }
        if let Some(worker) = self.worker.lock().unwrap_or_else(|e| e.into_inner()).take()
            && worker.thread().id() != thread::current().id()
        {
            let _ = worker.join();
        }
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if *state != SessionState::Failed {
            *state = SessionState::Stopped;
        }
        Ok(())
    }

    fn worker_main(self: Arc<Self>) {
        let runtime = match tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
        {
            Ok(runtime) => runtime,
            Err(_) => {
                self.fail(CoreError::internal("failed to build core runtime"));
                return;
            }
        };
        let mut normalizer = H264Normalizer::new(self.cfg.max_sample_bytes);
        let mut muxer = MpegTsMuxer::new(&self.cfg);
        let policy = ReconnectPolicy::new(&self.cfg);
        let mut disconnected_since = Instant::now();
        let mut attempt = 0u32;
        let mut seed = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos() as u64;

        loop {
            if self.should_stop() {
                break;
            }
            attempt = attempt.saturating_add(1);
            self.events.push(EventRecord::new(
                EventKind::TransportConnecting,
                0,
                u64::from(attempt),
                "connecting SRT caller",
            ));
            let (target, target_version) = match self.current_target() {
                Some(value) => value,
                None => {
                    self.fail(CoreError::new(
                        CoreErrorCode::InvalidTarget,
                        "session has no live target",
                        false,
                    ));
                    return;
                }
            };
            let connect = runtime.block_on(async {
                tokio::select! {
                    result = self.factory.connect(&target, &self.cfg) => Some(result),
                    () = self.cancelled_async() => None,
                }
            });
            let Some(connect) = connect else {
                break;
            };
            let mut transport = match connect {
                Ok(transport) => transport,
                Err(error) => {
                    if matches!(
                        error.code,
                        CoreErrorCode::TargetExpired | CoreErrorCode::InvalidTarget
                    ) {
                        self.events.push(EventRecord::new(
                            EventKind::TargetRefreshRequired,
                            error.code as i32,
                            0,
                            "live target refresh required",
                        ));
                    }
                    if policy.timed_out(disconnected_since.elapsed()) {
                        self.fail(CoreError::new(
                            CoreErrorCode::TransportTimeout,
                            "transport outage exceeded timeout",
                            false,
                        ));
                        return;
                    }
                    let delay = policy.delay(attempt, seed);
                    seed = seed.wrapping_add(1);
                    if self.wait_cancelled(delay) {
                        break;
                    }
                    continue;
                }
            };

            if attempt > 1 {
                self.metrics.reconnected();
            }
            attempt = 0;
            muxer.reset_generation();
            normalizer.reset_transport();
            self.set_connected(true);
            self.metrics.keyframe_requested();
            self.events.push(EventRecord::new(
                EventKind::TransportConnected,
                0,
                target_version,
                "SRT caller connected",
            ));
            self.events.push(EventRecord::new(
                EventKind::NeedKeyframe,
                0,
                1,
                "fresh IDR required after transport start",
            ));

            let sent = self.send_loop(
                &runtime,
                transport.as_mut(),
                target_version,
                &mut normalizer,
                &mut muxer,
            );
            let _ = runtime.block_on(async {
                tokio::time::timeout(Duration::from_millis(500), transport.close()).await
            });
            self.set_connected(false);
            if self.should_stop() {
                break;
            }
            if let Err(error) = sent {
                match error.code {
                    CoreErrorCode::Cancelled => break,
                    CoreErrorCode::TransportFailed => {}
                    _ => {
                        self.fail(error);
                        return;
                    }
                }
            }
            disconnected_since = Instant::now();
            self.events.push(EventRecord::new(
                EventKind::TransportDisconnected,
                0,
                0,
                "SRT transport disconnected",
            ));
        }

        {
            let (lock, ready) = &*self.shared;
            let mut shared = lock.lock().unwrap_or_else(|e| e.into_inner());
            shared.stop = true;
            shared.queue.stop();
            shared.target = None;
            ready.notify_all();
        }
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if *state != SessionState::Failed {
            *state = SessionState::Stopped;
            self.events.push(EventRecord::new(
                EventKind::Stopped,
                0,
                0,
                "session stopped",
            ));
        }
    }

    fn send_loop(
        &self,
        runtime: &tokio::runtime::Runtime,
        transport: &mut dyn Transport,
        target_version: u64,
        normalizer: &mut H264Normalizer,
        muxer: &mut MpegTsMuxer,
    ) -> CoreResult<()> {
        let mut last_stats = Instant::now()
            .checked_sub(Duration::from_secs(1))
            .unwrap_or_else(Instant::now);
        loop {
            let (sample, config) = match self.next_sample(target_version) {
                NextSample::Sample(sample, config) => (sample, config),
                NextSample::Reconnect => return Ok(()),
                NextSample::Stop => {
                    return Err(CoreError::new(
                        CoreErrorCode::Cancelled,
                        "session stopped",
                        true,
                    ));
                }
            };
            let is_eos = sample.flags.contains(SampleFlags::END_OF_STREAM);
            if is_eos && sample.bytes.is_empty() {
                self.release_sample(sample);
                return Err(CoreError::new(
                    CoreErrorCode::Cancelled,
                    "encoded stream ended",
                    false,
                ));
            }
            if sample.is_keyframe()
                && let Some((bytes, framing, generation)) = config
            {
                normalizer.configure(&bytes, framing, generation)?;
            }
            let normalized = normalizer.normalize(&sample)?;
            if let Some(annex_b) = normalized {
                let ts = muxer.mux_video(&annex_b, sample.pts_us, sample.is_keyframe());
                let sent = runtime.block_on(async {
                    tokio::select! {
                        result = transport.send(&ts) => result,
                        () = self.cancelled_async() => Err(CoreError::new(
                            CoreErrorCode::Cancelled,
                            "session stopped",
                            true,
                        )),
                    }
                });
                sent?;
                self.metrics.sent(ts.len());
            }
            if last_stats.elapsed() >= Duration::from_secs(1) {
                let stats = runtime.block_on(transport.stats())?;
                self.metrics
                    .set_transport(stats.rtt_ms, stats.retransmitted_packets);
                self.events.push(EventRecord::new(
                    EventKind::Metrics,
                    stats.rtt_ms.min(i32::MAX as u64) as i32,
                    stats.retransmitted_packets,
                    "transport statistics updated",
                ));
                last_stats = Instant::now();
            }
            self.release_sample(sample);
            if is_eos {
                return Err(CoreError::new(
                    CoreErrorCode::Cancelled,
                    "encoded stream ended",
                    false,
                ));
            }
        }
    }

    fn next_sample(&self, target_version: u64) -> NextSample {
        let (lock, ready) = &*self.shared;
        let mut shared = lock.lock().unwrap_or_else(|e| e.into_inner());
        loop {
            if shared.stop {
                return NextSample::Stop;
            }
            if shared.target_version != target_version {
                return NextSample::Reconnect;
            }
            if let Some(sample) = shared.queue.pop() {
                let config = shared
                    .queue
                    .codec_config()
                    .map(|(bytes, framing, generation)| (bytes.to_vec(), framing, generation));
                self.metrics
                    .set_queue(shared.queue.bytes_len(), shared.queue.len());
                return NextSample::Sample(sample, config);
            }
            let result = ready.wait_timeout(shared, Duration::from_millis(250));
            shared = match result {
                Ok((guard, _)) => guard,
                Err(error) => error.into_inner().0,
            };
        }
    }

    fn release_sample(&self, sample: EncodedSample) {
        let (lock, _) = &*self.shared;
        lock.lock()
            .unwrap_or_else(|e| e.into_inner())
            .queue
            .release(sample);
    }

    fn current_target(&self) -> Option<(LiveTarget, u64)> {
        let (lock, _) = &*self.shared;
        let shared = lock.lock().unwrap_or_else(|e| e.into_inner());
        shared
            .target
            .as_ref()
            .map(|target| (target.clone(), shared.target_version))
    }

    fn set_connected(&self, connected: bool) {
        let (lock, _) = &*self.shared;
        let mut shared = lock.lock().unwrap_or_else(|e| e.into_inner());
        shared.queue.set_connected(connected);
        self.metrics
            .set_queue(shared.queue.bytes_len(), shared.queue.len());
    }

    fn wait_cancelled(&self, duration: Duration) -> bool {
        let (lock, ready) = &*self.shared;
        let shared = lock.lock().unwrap_or_else(|e| e.into_inner());
        if shared.stop {
            return true;
        }
        let result = ready.wait_timeout(shared, duration);
        match result {
            Ok((guard, _)) => guard.stop,
            Err(error) => error.into_inner().0.stop,
        }
    }

    fn should_stop(&self) -> bool {
        let (lock, _) = &*self.shared;
        lock.lock().unwrap_or_else(|e| e.into_inner()).stop
    }

    async fn cancelled_async(&self) {
        loop {
            if self.should_stop() {
                return;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }

    fn fail(&self, error: CoreError) {
        *self.state.lock().unwrap_or_else(|e| e.into_inner()) = SessionState::Failed;
        let (lock, ready) = &*self.shared;
        let mut shared = lock.lock().unwrap_or_else(|e| e.into_inner());
        shared.stop = true;
        shared.queue.stop();
        shared.target = None;
        ready.notify_all();
        self.events.push(EventRecord::new(
            EventKind::Failed,
            error.code as i32,
            0,
            error.message,
        ));
    }
}

enum NextSample {
    Sample(EncodedSample, Option<(Vec<u8>, H264Framing, u32)>),
    Reconnect,
    Stop,
}
