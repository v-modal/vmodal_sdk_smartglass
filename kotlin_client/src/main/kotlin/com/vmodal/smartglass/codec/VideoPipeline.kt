package com.vmodal.smartglass.codec

import com.vmodal.smartglass.NegotiatedVideo
import com.vmodal.smartglass.VideoConfig
import com.vmodal.smartglass.bridge.EncodedVideoSample
import com.vmodal.smartglass.bridge.PushResult
import com.vmodal.smartglass.dat.DatFrame
import kotlinx.coroutines.flow.Flow

internal fun interface VideoSampleSink {
    fun push(sample: EncodedVideoSample): PushResult
}

internal interface VideoPipeline {
    val signals: Flow<VideoSignal>
    fun start(video: VideoConfig, sink: VideoSampleSink): NegotiatedVideo
    fun offer(frame: DatFrame): Boolean
    fun requestKeyframe()
    fun stop()
}

internal sealed interface VideoSignal {
    data class FrameDropped(val count: Long) : VideoSignal
    data object KeyframeRequested : VideoSignal
    data class Failed(val code: String) : VideoSignal
}
