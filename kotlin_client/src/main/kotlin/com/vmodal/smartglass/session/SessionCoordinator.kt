package com.vmodal.smartglass.session

import com.vmodal.smartglass.DropStage
import com.vmodal.smartglass.KeyframeReason
import com.vmodal.smartglass.LiveStreamTarget
import com.vmodal.smartglass.LiveStreamTargetProvider
import com.vmodal.smartglass.NegotiatedVideo
import com.vmodal.smartglass.SmartGlassConfig
import com.vmodal.smartglass.SmartGlassError
import com.vmodal.smartglass.SmartGlassEvent
import com.vmodal.smartglass.SmartGlassState
import com.vmodal.smartglass.StopReason
import com.vmodal.smartglass.StreamingMetrics
import com.vmodal.smartglass.TargetReason
import com.vmodal.smartglass.bridge.CoreBridge
import com.vmodal.smartglass.bridge.NativeEvent
import com.vmodal.smartglass.bridge.NativeEventKind
import com.vmodal.smartglass.bridge.PushResult
import com.vmodal.smartglass.codec.VideoPipeline
import com.vmodal.smartglass.codec.VideoSampleSink
import com.vmodal.smartglass.codec.VideoSignal
import com.vmodal.smartglass.dat.DatSignal
import com.vmodal.smartglass.dat.DatSource
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SessionCoordinator(
    private val config: SmartGlassConfig,
    private val targetProvider: LiveStreamTargetProvider,
    private val dat: DatSource,
    private val videoPipeline: VideoPipeline,
    private val core: CoreBridge,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commandLock = Mutex()
    private val mutableState = MutableStateFlow<SmartGlassState>(SmartGlassState.Idle)
    private val mutableEvents = MutableSharedFlow<SmartGlassEvent>(extraBufferCapacity = 64)
    private val mutableMetrics = MutableStateFlow(StreamingMetrics())

    val state: StateFlow<SmartGlassState> = mutableState.asStateFlow()
    val events: Flow<SmartGlassEvent> = mutableEvents.asSharedFlow()
    val metrics: StateFlow<StreamingMetrics> = mutableMetrics.asStateFlow()

    private var handle = 0L
    private var target: LiveStreamTarget? = null
    private var negotiated: NegotiatedVideo? = null
    private var eventJob: Job? = null
    private var datJob: Job? = null
    private var codecJob: Job? = null
    private var closed = false

    suspend fun start() = commandLock.withLock {
        check(!closed) { "client is closed" }
        if (mutableState.value !is SmartGlassState.Idle && mutableState.value !is SmartGlassState.Stopped) {
            return@withLock
        }
        try {
            config.validate()
            val acquired = targetProvider.acquire(TargetReason.INITIAL)
            validateTarget(acquired)
            target = acquired

            handle = core.create(config)
            collectSignals()
            mutableState.value = SmartGlassState.StartingDeviceSession
            dat.open(config.video)

            mutableState.value = SmartGlassState.StartingCodec
            negotiated = videoPipeline.start(config.video, VideoSampleSink(::pushEncoded))

            mutableState.value = SmartGlassState.ConnectingTransport(1)
            core.start(handle, acquired)
            eventJob = scope.launch(Dispatchers.IO) { pollEvents() }

            mutableState.value = SmartGlassState.StartingCapture
            dat.startCapture { frame ->
                if (!videoPipeline.offer(frame)) {
                    mutableEvents.tryEmit(SmartGlassEvent.FrameDropped(DropStage.RAW_QUEUE, 1))
                }
            }
            videoPipeline.requestKeyframe()
            mutableEvents.tryEmit(SmartGlassEvent.KeyframeRequested(KeyframeReason.START))
        } catch (cancelled: CancellationException) {
            teardown(StopReason.FAILURE)
            mutableState.value = SmartGlassState.Failed(SmartGlassError.Cancelled())
            throw cancelled
        } catch (error: Throwable) {
            val safe = mapError(error)
            teardown(StopReason.FAILURE)
            mutableState.value = SmartGlassState.Failed(safe)
        }
    }

    suspend fun stop(reason: StopReason = StopReason.USER) = commandLock.withLock {
        if (mutableState.value is SmartGlassState.Stopped || mutableState.value is SmartGlassState.Idle) {
            mutableState.value = SmartGlassState.Stopped(reason)
            return@withLock
        }
        if (mutableState.value is SmartGlassState.Stopping) return@withLock
        mutableState.value = SmartGlassState.Stopping(reason)
        teardown(reason)
        mutableState.value = SmartGlassState.Stopped(reason)
    }

    suspend fun onBackgrounded() {
        if (config.stopOnBackground) stop(StopReason.BACKGROUND)
    }

    override fun close() {
        if (closed) return
        runBlocking { closeAndJoin() }
    }

    suspend fun closeAndJoin() {
        if (!closed) closed = true
        stop(StopReason.CLOSED)
        scope.cancel()
    }

    private fun collectSignals() {
        datJob = scope.launch {
            dat.signals.collect { signal ->
                when (signal) {
                    DatSignal.Paused -> mutableEvents.emit(SmartGlassEvent.DeviceWarning("DAT_PAUSED"))
                    DatSignal.Resumed -> Unit
                    is DatSignal.Warning -> mutableEvents.emit(SmartGlassEvent.DeviceWarning(signal.code))
                    is DatSignal.Failure -> fail(
                        if (signal.thermal) SmartGlassError.ThermalLimit()
                        else SmartGlassError.DeviceSessionFailed(signal.code),
                    )
                }
            }
        }
        codecJob = scope.launch {
            videoPipeline.signals.collect { signal ->
                when (signal) {
                    is VideoSignal.FrameDropped -> mutableEvents.emit(
                        SmartGlassEvent.FrameDropped(DropStage.RAW_QUEUE, signal.count),
                    )
                    VideoSignal.KeyframeRequested -> Unit
                    is VideoSignal.Failed -> fail(SmartGlassError.CodecRuntimeFailed(signal.code))
                }
            }
        }
    }

    private fun pushEncoded(sample: com.vmodal.smartglass.bridge.EncodedVideoSample): PushResult {
        val activeHandle = handle
        if (activeHandle == 0L) return PushResult.STOPPED
        return try {
            val result = core.pushVideo(activeHandle, sample)
            when (result) {
                PushResult.NEED_KEYFRAME -> {
                    mutableEvents.tryEmit(SmartGlassEvent.KeyframeRequested(KeyframeReason.BACKPRESSURE))
                    videoPipeline.requestKeyframe()
                }
                PushResult.BACKPRESSURE_DROP -> mutableEvents.tryEmit(
                    SmartGlassEvent.FrameDropped(DropStage.NATIVE_QUEUE, 1),
                )
                else -> Unit
            }
            result
        } catch (error: Throwable) {
            scope.launch { fail(mapError(error)) }
            PushResult.STOPPED
        }
    }

    private suspend fun pollEvents() {
        var lastMetricsMs = 0L
        while (scope.isActive && handle != 0L) {
            val event = core.pollEvent(handle, 500)
            if (event != null) handleNativeEvent(event)
            val nowMs = System.currentTimeMillis()
            if (event?.kind == NativeEventKind.METRICS || nowMs - lastMetricsMs >= 1_000) {
                runCatching { core.stats(handle) }.getOrNull()?.let {
                    mutableMetrics.value = it
                    mutableEvents.tryEmit(SmartGlassEvent.TransportStatsUpdated(it))
                }
                lastMetricsMs = nowMs
            }
            if (event == null) delay(10)
        }
    }

    private suspend fun handleNativeEvent(event: NativeEvent) {
        when (event.kind) {
            NativeEventKind.TRANSPORT_CONNECTING ->
                mutableState.value = SmartGlassState.ConnectingTransport(event.value.toInt().coerceAtLeast(1))
            NativeEventKind.TRANSPORT_CONNECTED -> {
                val activeTarget = target ?: return
                val activeVideo = negotiated ?: return
                mutableState.value = SmartGlassState.Streaming(activeTarget.sessionId, activeVideo)
            }
            NativeEventKind.TRANSPORT_DISCONNECTED -> {
                mutableState.value = SmartGlassState.Reconnecting(event.code.coerceAtLeast(1), event.value)
            }
            NativeEventKind.NEED_KEYFRAME -> {
                videoPipeline.requestKeyframe()
                mutableEvents.emit(SmartGlassEvent.KeyframeRequested(KeyframeReason.RECONNECT))
            }
            NativeEventKind.FRAME_DROPPED -> mutableEvents.emit(
                SmartGlassEvent.FrameDropped(DropStage.NATIVE_QUEUE, event.value),
            )
            NativeEventKind.TARGET_REFRESH_REQUIRED -> {
                mutableEvents.emit(SmartGlassEvent.TargetRefreshRequired(TargetReason.EXPIRED))
                refreshTarget(TargetReason.EXPIRED)
            }
            NativeEventKind.STOPPED -> Unit
            NativeEventKind.FAILED -> fail(nativeError(event.code))
            NativeEventKind.METRICS -> Unit
        }
    }

    private suspend fun refreshTarget(reason: TargetReason) {
        val refreshed = targetProvider.acquire(reason)
        validateTarget(refreshed)
        core.updateTarget(handle, refreshed)
        target = refreshed
    }

    private suspend fun fail(error: SmartGlassError) {
        if (mutableState.value is SmartGlassState.Failed || mutableState.value is SmartGlassState.Stopping) return
        teardown(StopReason.FAILURE)
        mutableState.value = SmartGlassState.Failed(error)
    }

    private suspend fun teardown(reason: StopReason) {
        val currentJob = currentCoroutineContext()[Job]
        if (eventJob != currentJob) eventJob?.cancelAndJoin()
        eventJob = null
        runCatching { dat.stop() }
        videoPipeline.stop()
        if (handle != 0L) {
            runCatching { core.stop(handle, reason.nativeCode) }
            runCatching { core.destroy(handle) }
            handle = 0
        }
        datJob?.cancel()
        codecJob?.cancel()
        datJob = null
        codecJob = null
        target = null
        negotiated = null
    }

    private fun validateTarget(value: LiveStreamTarget) {
        try {
            value.validate()
        } catch (error: IllegalArgumentException) {
            if (error.message == "target is expired") throw SmartGlassError.TargetExpired()
            throw SmartGlassError.InvalidTarget()
        }
    }

    private fun nativeError(code: Int): SmartGlassError = when (code) {
        1003 -> SmartGlassError.InvalidTarget()
        1004 -> SmartGlassError.TargetExpired()
        1010 -> SmartGlassError.TransportFailed()
        1011 -> SmartGlassError.TransportTimeout()
        else -> SmartGlassError.InternalInvariantViolation("CORE_$code")
    }

    private fun mapError(error: Throwable): SmartGlassError = when (error) {
        is SmartGlassError -> error
        else -> SmartGlassError.InternalInvariantViolation(error.javaClass.simpleName)
    }
}
