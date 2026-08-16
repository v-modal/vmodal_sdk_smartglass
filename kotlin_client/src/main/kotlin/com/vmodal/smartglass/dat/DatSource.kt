package com.vmodal.smartglass.dat

import com.vmodal.smartglass.VideoConfig
import kotlinx.coroutines.flow.Flow

internal interface DatSource {
    val signals: Flow<DatSignal>
    suspend fun open(video: VideoConfig)
    suspend fun startCapture(onFrame: (DatFrame) -> Unit)
    suspend fun stop()
}

internal data class DatFrame(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val ptsUs: Long,
    val format: DatFrameFormat,
)

internal enum class DatFrameFormat { I420 }

internal sealed interface DatSignal {
    data object Paused : DatSignal
    data object Resumed : DatSignal
    data class Warning(val code: String) : DatSignal
    data class Failure(val code: String, val thermal: Boolean = false) : DatSignal
}
