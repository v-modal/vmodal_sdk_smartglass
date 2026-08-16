package com.vmodal.smartglass.dat

import com.meta.wearable.dat.camera.types.VideoFrame
import com.vmodal.smartglass.SmartGlassError

internal object DatFrameAdapter {
    fun adapt(frame: VideoFrame): DatFrame {
        if (frame.isCompressed || frame.isCodecConfig) {
            throw SmartGlassError.UnsupportedInputFormat()
        }
        val expected = frame.width.toLong() * frame.height * 3 / 2
        if (expected <= 0 || expected > Int.MAX_VALUE || frame.buffer.remaining() < expected) {
            throw SmartGlassError.UnsupportedInputFormat()
        }
        val copy = ByteArray(expected.toInt())
        frame.buffer.duplicate().get(copy)
        return DatFrame(copy, frame.width, frame.height, frame.presentationTimeUs, DatFrameFormat.I420)
    }
}
