package com.vmodal.smartglass

sealed interface SmartGlassState {
    data object Idle : SmartGlassState
    data object Registering : SmartGlassState
    data object WaitingForDevice : SmartGlassState
    data object StartingDeviceSession : SmartGlassState
    data object StartingCapture : SmartGlassState
    data object StartingCodec : SmartGlassState
    data class ConnectingTransport(val attempt: Int) : SmartGlassState
    data class Streaming(val sessionId: String, val negotiatedVideo: NegotiatedVideo) : SmartGlassState
    data class Reconnecting(val attempt: Int, val disconnectedMs: Long) : SmartGlassState
    data class Stopping(val reason: StopReason) : SmartGlassState
    data class Stopped(val reason: StopReason) : SmartGlassState
    data class Failed(val error: SmartGlassError, val recoverable: Boolean = error.recoverable) : SmartGlassState
}

sealed interface SmartGlassEvent {
    data class FrameDropped(val stage: DropStage, val count: Long) : SmartGlassEvent
    data class KeyframeRequested(val reason: KeyframeReason) : SmartGlassEvent
    data class QualityChanged(val old: NegotiatedVideo, val new: NegotiatedVideo, val reason: String) : SmartGlassEvent
    data class TransportStatsUpdated(val snapshot: StreamingMetrics) : SmartGlassEvent
    data class DeviceWarning(val code: String) : SmartGlassEvent
    data class TargetRefreshRequired(val reason: TargetReason) : SmartGlassEvent
}

enum class DropStage { DAT_INPUT, RAW_QUEUE, NATIVE_QUEUE }
enum class KeyframeReason { START, RECONNECT, BACKPRESSURE, CODEC_RESTART }
enum class StopReason(val nativeCode: Int) {
    USER(1),
    BACKGROUND(2),
    CLOSED(3),
    FAILURE(4),
}
