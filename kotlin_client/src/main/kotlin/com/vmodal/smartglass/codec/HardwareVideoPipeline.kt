package com.vmodal.smartglass.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import com.vmodal.smartglass.NegotiatedVideo
import com.vmodal.smartglass.SmartGlassError
import com.vmodal.smartglass.VideoConfig
import com.vmodal.smartglass.bridge.EncodedVideoSample
import com.vmodal.smartglass.bridge.PushResult
import com.vmodal.smartglass.bridge.SampleFlag
import com.vmodal.smartglass.dat.DatFrame
import com.vmodal.smartglass.dat.DatFrameFormat
import java.nio.ByteBuffer
import java.util.ArrayDeque
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class HardwareVideoPipeline(
    private val rawCapacity: Int,
) : VideoPipeline {
    private val mutableSignals = MutableSharedFlow<VideoSignal>(extraBufferCapacity = 16)
    override val signals: Flow<VideoSignal> = mutableSignals.asSharedFlow()
    private val lock = Any()
    private val frames = ArrayDeque<DatFrame>()
    private val inputIndexes = ArrayDeque<Int>()

    private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var sink: VideoSampleSink? = null
    private var video = VideoConfig()
    private var dropped = 0L
    private var running = false

    override fun start(video: VideoConfig, sink: VideoSampleSink): NegotiatedVideo {
        check(!running) { "video pipeline is already running" }
        this.video = video
        this.sink = sink
        val choice = CodecSelector.select(video)
        val codecThread = HandlerThread("vmodal-smartglass-codec").also { it.start() }
        val codecHandler = Handler(codecThread.looper)
        val activeCodec = try {
            MediaCodec.createByCodecName(choice.info.name).also { mediaCodec ->
                mediaCodec.setCallback(callback, codecHandler)
                mediaCodec.configure(format(video, choice), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                mediaCodec.start()
            }
        } catch (error: Exception) {
            codecThread.quitSafely()
            throw SmartGlassError.CodecConfigurationFailed(error.javaClass.simpleName)
        }
        codec = activeCodec
        thread = codecThread
        handler = codecHandler
        running = true
        return NegotiatedVideo(
            width = video.preferredWidth,
            height = video.preferredHeight,
            fps = video.preferredFps,
            bitrateBps = video.preferredBitrateBps,
            codecName = choice.info.name,
            profile = profileName(choice.profile),
            level = choice.level.toString(),
            colorFormat = "YUV420_FLEXIBLE",
            bitrateMode = if (choice.bitrateMode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) "CBR" else "VBR",
        )
    }

    override fun offer(frame: DatFrame): Boolean {
        if (!running || frame.format != DatFrameFormat.I420) return false
        synchronized(lock) {
            if (frames.size >= rawCapacity) {
                dropped += 1
                mutableSignals.tryEmit(VideoSignal.FrameDropped(dropped))
                return false
            }
            frames.addLast(frame)
        }
        handler?.post(::drainInput)
        return true
    }

    override fun requestKeyframe() {
        if (!running) return
        handler?.post {
            runCatching {
                codec?.setParameters(Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                })
            }.onFailure { mutableSignals.tryEmit(VideoSignal.Failed("KEYFRAME_REQUEST")) }
        }
        mutableSignals.tryEmit(VideoSignal.KeyframeRequested)
    }

    override fun stop() {
        if (!running && codec == null) return
        running = false
        synchronized(lock) {
            frames.clear()
            inputIndexes.clear()
        }
        val activeCodec = codec
        codec = null
        runCatching { activeCodec?.stop() }
        runCatching { activeCodec?.release() }
        thread?.quitSafely()
        thread = null
        handler = null
        sink = null
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            synchronized(lock) { inputIndexes.addLast(index) }
            drainInput()
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val output = codec.getOutputBuffer(index) ?: return
                val flags = sampleFlags(info.flags)
                val result = sink?.push(
                    EncodedVideoSample(output, info.offset, info.size, info.presentationTimeUs, flags),
                )
                when (result) {
                    PushResult.NEED_KEYFRAME -> requestKeyframe()
                    PushResult.BACKPRESSURE_DROP -> mutableSignals.tryEmit(VideoSignal.FrameDropped(++dropped))
                    PushResult.INVALID_SAMPLE -> mutableSignals.tryEmit(VideoSignal.Failed("NATIVE_INVALID_SAMPLE"))
                    else -> Unit
                }
            } catch (error: Exception) {
                mutableSignals.tryEmit(VideoSignal.Failed(error.javaClass.simpleName))
            } finally {
                codec.releaseOutputBuffer(index, false)
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            val config = directCodecConfig(format) ?: return
            val result = sink?.push(
                EncodedVideoSample(config, 0, config.remaining(), 0, SampleFlag.CODEC_CONFIG),
            )
            if (result == PushResult.NEED_KEYFRAME) requestKeyframe()
        }

        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
            mutableSignals.tryEmit(VideoSignal.Failed(error.diagnosticInfo ?: "MEDIACODEC"))
        }
    }

    private fun drainInput() {
        while (running) {
            val pair = synchronized(lock) {
                if (frames.isEmpty() || inputIndexes.isEmpty()) null
                else inputIndexes.removeFirst() to frames.removeFirst()
            } ?: return
            val (index, frame) = pair
            try {
                if (frame.width != video.preferredWidth || frame.height != video.preferredHeight) {
                    codec?.queueInputBuffer(index, 0, 0, frame.ptsUs, 0)
                    mutableSignals.tryEmit(VideoSignal.FrameDropped(++dropped))
                    continue
                }
                val image = codec?.getInputImage(index)
                    ?: throw SmartGlassError.UnsupportedInputFormat()
                image.use { copyI420(frame, it) }
                codec?.queueInputBuffer(index, 0, frame.bytes.size, frame.ptsUs, 0)
            } catch (error: Exception) {
                mutableSignals.tryEmit(VideoSignal.Failed(error.javaClass.simpleName))
            }
        }
    }

    private fun copyI420(frame: DatFrame, image: android.media.Image) {
        val width = frame.width
        val height = frame.height
        val offsets = intArrayOf(0, width * height, width * height + width * height / 4)
        val planeWidths = intArrayOf(width, width / 2, width / 2)
        val planeHeights = intArrayOf(height, height / 2, height / 2)
        image.planes.forEachIndexed { planeIndex, plane ->
            val srcWidth = planeWidths[planeIndex]
            val srcHeight = planeHeights[planeIndex]
            val dst = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            var src = offsets[planeIndex]
            for (row in 0 until srcHeight) {
                var dstPos = row * rowStride
                for (column in 0 until srcWidth) {
                    dst.put(dstPos, frame.bytes[src++])
                    dstPos += pixelStride
                }
            }
        }
    }

    private fun format(video: VideoConfig, choice: CodecChoice): MediaFormat =
        MediaFormat.createVideoFormat(CodecSelector.MIME, video.preferredWidth, video.preferredHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, video.preferredBitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, video.preferredFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, video.keyframeIntervalSec)
            setInteger(MediaFormat.KEY_BITRATE_MODE, choice.bitrateMode)
            setInteger(MediaFormat.KEY_PROFILE, choice.profile)
            setInteger(MediaFormat.KEY_LEVEL, choice.level)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }

    private fun sampleFlags(flags: Int): Int {
        var result = 0
        if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) result = result or SampleFlag.CODEC_CONFIG
        if (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) result = result or SampleFlag.KEY_FRAME
        if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) result = result or SampleFlag.END_OF_STREAM
        return result
    }

    private fun directCodecConfig(format: MediaFormat): ByteBuffer? {
        val parts = listOfNotNull(format.getByteBuffer("csd-0"), format.getByteBuffer("csd-1"))
        if (parts.isEmpty()) return null
        val size = parts.sumOf { it.remaining() }
        return ByteBuffer.allocateDirect(size).apply {
            parts.forEach { put(it.duplicate()) }
            flip()
        }
    }

    private fun profileName(profile: Int): String = when (profile) {
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> "HIGH"
        MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> "MAIN"
        else -> "BASELINE"
    }
}
