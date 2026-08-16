package com.vmodal.smartglass.bridge

import com.vmodal.smartglass.LiveStreamTarget
import com.vmodal.smartglass.SmartGlassConfig
import com.vmodal.smartglass.StreamingMetrics
import java.nio.ByteBuffer

internal interface CoreBridge {
    fun create(config: SmartGlassConfig): Long
    fun start(handle: Long, target: LiveStreamTarget)
    fun pushVideo(handle: Long, sample: EncodedVideoSample): PushResult
    fun updateTarget(handle: Long, target: LiveStreamTarget)
    fun pollEvent(handle: Long, timeoutMs: Int): NativeEvent?
    fun stats(handle: Long): StreamingMetrics
    fun stop(handle: Long, reasonCode: Int)
    fun destroy(handle: Long)
}

internal data class EncodedVideoSample(
    val buffer: ByteBuffer,
    val offset: Int,
    val length: Int,
    val ptsUs: Long,
    val flags: Int,
)

internal enum class PushResult(val value: Int) {
    ACCEPTED(0),
    BACKPRESSURE_DROP(1),
    NEED_KEYFRAME(2),
    STOPPED(3),
    INVALID_SAMPLE(4),
}

internal data class NativeEvent(
    val kind: NativeEventKind,
    val terminal: Boolean,
    val code: Int,
    val value: Long,
    val message: String,
)

internal enum class NativeEventKind {
    TRANSPORT_CONNECTING,
    TRANSPORT_CONNECTED,
    TRANSPORT_DISCONNECTED,
    NEED_KEYFRAME,
    FRAME_DROPPED,
    TARGET_REFRESH_REQUIRED,
    STOPPED,
    FAILED,
    METRICS,
}

internal object SampleFlag {
    const val CODEC_CONFIG = 1
    const val KEY_FRAME = 1 shl 1
    const val END_OF_STREAM = 1 shl 2
}
