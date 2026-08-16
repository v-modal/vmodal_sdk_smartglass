package com.vmodal.smartglass.codec

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import com.vmodal.smartglass.SmartGlassError
import com.vmodal.smartglass.VideoConfig

internal data class CodecChoice(
    val info: MediaCodecInfo,
    val profile: Int,
    val level: Int,
    val bitrateMode: Int,
)

internal object CodecSelector {
    fun select(video: VideoConfig): CodecChoice {
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder && !it.isAlias && it.supportedTypes.any(MIME::equals) }
            .filter { it.isHardwareAccelerated && !it.isSoftwareOnly }
            .mapNotNull { info -> choice(info, video) }
            .toList()
        return candidates.firstOrNull()
            ?: throw SmartGlassError.HardwareCodecUnavailable()
    }

    private fun choice(info: MediaCodecInfo, video: VideoConfig): CodecChoice? {
        val caps = runCatching { info.getCapabilitiesForType(MIME) }.getOrNull() ?: return null
        val videoCaps = caps.videoCapabilities ?: return null
        val sizeOk = runCatching {
            videoCaps.isSizeSupported(video.preferredWidth, video.preferredHeight)
        }.getOrDefault(false)
        if (!sizeOk || !caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)) {
            return null
        }
        val profiles = listOf(
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
            MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
        )
        val profile = profiles.firstOrNull { requested ->
            caps.profileLevels.any { it.profile == requested }
        } ?: return null
        val advertisedLevel = caps.profileLevels
            .filter { it.profile == profile }
            .maxOfOrNull { it.level }
            ?: MediaCodecInfo.CodecProfileLevel.AVCLevel31
        val level = minOf(advertisedLevel, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
        val enc = caps.encoderCapabilities ?: return null
        val bitrateMode = when {
            enc.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) ->
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            enc.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) ->
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            else -> return null
        }
        return CodecChoice(info, profile, level, bitrateMode)
    }

    const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
}
