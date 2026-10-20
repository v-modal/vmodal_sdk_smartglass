package com.vmodal.smartglass.voicesearch.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface DevicePort {
    val readiness: StateFlow<DeviceReadiness>
    suspend fun prepare()
    suspend fun close()
}

interface SpeechPort {
    fun listen(turn: VoiceTurn): Flow<TranscriptEvent>
    suspend fun stop()
}

fun interface SearchPort { suspend fun topOne(query: SearchQuery): SearchOutcome }
fun interface ImageSourcePort { suspend fun load(ref: RankedImageRef): EncodedImage }
fun interface ImagePreparePort { suspend fun prepare(encoded: EncodedImage, limits: ImageLimits): PreparedImage }

interface DisplayPort {
    val actions: Flow<ScreenAction>
    suspend fun render(screen: ScreenModel): DisplayResult
    suspend fun clear()
}
