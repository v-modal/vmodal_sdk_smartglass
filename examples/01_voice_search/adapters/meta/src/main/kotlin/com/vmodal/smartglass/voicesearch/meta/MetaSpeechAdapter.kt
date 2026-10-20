package com.vmodal.smartglass.voicesearch.meta

import com.meta.wearable.dat.speech.types.SpeechState
import com.vmodal.smartglass.voicesearch.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MetaSpeechAdapter(private val gateway: MetaDeviceGateway, private val maxListenMs: Long = 15_000) : SpeechPort {
    init { require(maxListenMs > 0) }
    private var active: Job? = null

    override fun listen(turn: VoiceTurn): Flow<TranscriptEvent> = channelFlow {
        val ended = withTimeoutOrNull(maxListenMs) { withContext(Dispatchers.Main.immediate) {
            if (active?.isActive == true) throw AppException(failure(Stage.Speech, FailureCode.SpeechUnavailable, "Already listening", turn.turnId))
            val listening = currentCoroutineContext().job
            active = listening
            val speech = try { gateway.freshSpeech() }
            catch (error: Throwable) {
                if (active === listening) active = null
                throw error
            }
            val epoch = gateway.generation
            val readers = mutableListOf<Job>()
            val completed = CompletableDeferred<Unit>()
            try {
                // StateFlow replays the previous turn; skip its existing value before starting.
                readers += launch(start = CoroutineStart.UNDISPATCHED) {
                    speech.transcriptions.drop(1).filterNotNull().collect { result ->
                        val locale = speech.locale.value.orEmpty()
                        if (locale.isNotBlank() && !locale.substringBefore('-').equals(turn.locale.substringBefore('-'), true)) {
                            throw AppException(failure(Stage.Speech, FailureCode.SpeechUnavailable, "The glasses speech locale does not match the requested language", turn.turnId))
                        }
                        send(TranscriptEvent(turn.turnId, result.text, result.isFinal, locale, confidence(result.confidence)))
                    }
                }
                readers += launch(start = CoroutineStart.UNDISPATCHED) {
                    speech.errors.drop(1).filterNotNull().collect {
                        throw AppException(failure(Stage.Speech, FailureCode.SpeechUnavailable, "Glasses speech failed or is unavailable", turn.turnId))
                    }
                }
                readers += launch(start = CoroutineStart.UNDISPATCHED) {
                    gateway.readiness.collect {
                        if (gateway.generation != epoch || !it.connected) {
                            throw AppException(failure(Stage.Speech, FailureCode.SpeechUnavailable, "Glasses disconnected", turn.turnId))
                        }
                    }
                }
                // Observe capability teardown. DAT's native utterance completion leaves state STARTED.
                readers += launch(start = CoroutineStart.UNDISPATCHED) {
                    speech.state.drop(1).first { it == SpeechState.STOPPED }
                    completed.complete(Unit)
                }
                speech.start().onFailure { _, _ ->
                    throw AppException(failure(Stage.Speech, FailureCode.SpeechUnavailable, "Unable to start glasses speech", turn.turnId))
                }
                completed.await()
            } finally {
                readers.forEach { it.cancel() }
                speech.stop()
                if (active === listening) active = null
            }
        }; true }
        if (ended == null) {
            throw AppException(failure(Stage.Speech, FailureCode.NoSpeech, "No completed speech received", turn.turnId))
        }
    }

    override suspend fun stop() = withContext(Dispatchers.Main.immediate) {
        active?.cancel()
        gateway.speech?.stop()
        Unit
    }
}

internal fun confidence(value: Float): Double? = if (value in 0f..1f) value.toDouble() else null
