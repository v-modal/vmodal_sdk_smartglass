package com.vmodal.smartglass

data class SmartGlassConfig(
    val video: VideoConfig = VideoConfig(),
    val reconnect: ReconnectConfig = ReconnectConfig(),
    val buffering: BufferConfig = BufferConfig(),
    val stopOnBackground: Boolean = true,
    val disconnectTimeoutMs: Long = 60_000,
) {
    internal fun validate() {
        video.validate()
        reconnect.validate(disconnectTimeoutMs)
        buffering.validate()
        require(disconnectTimeoutMs > 0) { "disconnectTimeoutMs must be positive" }
    }
}

data class VideoConfig(
    val preferredWidth: Int = 720,
    val preferredHeight: Int = 1280,
    val preferredFps: Int = 30,
    val preferredBitrateBps: Int = 4_000_000,
    val maxBitrateBps: Int = 5_000_000,
    val keyframeIntervalSec: Int = 2,
    val allowThermalDownshift: Boolean = true,
) {
    internal fun validate() {
        require(preferredWidth > 0 && preferredHeight > 0) { "video dimensions must be positive" }
        require(preferredFps in SUPPORTED_FPS) { "preferredFps must be one of $SUPPORTED_FPS" }
        require(preferredBitrateBps in 1..maxBitrateBps) { "preferred bitrate must fit max bitrate" }
        require(keyframeIntervalSec in 1..10) { "keyframe interval must be between 1 and 10" }
    }

    internal companion object {
        val SUPPORTED_FPS = setOf(2, 7, 15, 24, 30)
    }
}

data class ReconnectConfig(
    val delaysMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 15_000),
    val jitterPercent: Int = 10,
    val srtLatencyMs: Long = 1_200,
    val srtPeerLatencyMs: Long = 1_200,
) {
    internal fun validate(timeoutMs: Long) {
        require(delaysMs.isNotEmpty() && delaysMs.all { it > 0 }) { "reconnect delays must be positive" }
        require(jitterPercent in 0..25) { "reconnect jitter must be between 0 and 25" }
        require(srtLatencyMs in 20..8_000 && srtPeerLatencyMs in 20..8_000) {
            "SRT latency must be between 20 and 8000 ms"
        }
        require(timeoutMs >= delaysMs.first()) { "disconnect timeout is shorter than first retry" }
    }
}

data class BufferConfig(
    val encodedQueueMaxBytes: Int = 8 * 1024 * 1024,
    val encodedQueueMaxDurationMs: Long = 2_000,
    val eventQueueCapacity: Int = 128,
    val maxEncodedSampleBytes: Int = 2 * 1024 * 1024,
    val blockPoolCapacity: Int = 96,
    val rawFrameCapacity: Int = 3,
) {
    internal fun validate() {
        require(encodedQueueMaxBytes > 0 && encodedQueueMaxDurationMs > 0) {
            "encoded queue bounds must be positive"
        }
        require(eventQueueCapacity >= 2) { "event queue capacity must be at least 2" }
        require(maxEncodedSampleBytes in 1..encodedQueueMaxBytes) {
            "sample bound must fit encoded queue"
        }
        require(blockPoolCapacity > 0) { "block pool capacity must be positive" }
        require(rawFrameCapacity in 2..3) { "raw frame capacity must be 2 or 3" }
    }
}

data class NegotiatedVideo(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val codecName: String,
    val profile: String,
    val level: String,
    val colorFormat: String,
    val bitrateMode: String,
)
