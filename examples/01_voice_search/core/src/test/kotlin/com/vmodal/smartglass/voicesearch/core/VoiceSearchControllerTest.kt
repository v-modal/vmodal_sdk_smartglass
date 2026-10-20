package com.vmodal.smartglass.voicesearch.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSearchControllerTest {
    private val session = SessionScope(1, "account", "vid_file", "shirts", "frames", 42, "vid_img")

    private class Device : DevicePort {
        override val readiness = MutableStateFlow(DeviceReadiness(true, true, true))
        var closes = 0
        override suspend fun prepare() { readiness.value = DeviceReadiness(true, true, true) }
        override suspend fun close() { closes++; readiness.value = DeviceReadiness() }
    }
    private class Speech : SpeechPort {
        val events = MutableSharedFlow<TranscriptEvent>(extraBufferCapacity = 10)
        val turns = mutableListOf<VoiceTurn>()
        var stops = 0
        var onStop: suspend () -> Unit = {}
        override fun listen(turn: VoiceTurn): Flow<TranscriptEvent> = flow {
            turns += turn
            events.collect { emit(it) }
        }
        override suspend fun stop() { stops++; onStop() }
    }
    private class Display : DisplayPort {
        override val actions = MutableSharedFlow<ScreenAction>(extraBufferCapacity = 10)
        val screens = mutableListOf<ScreenModel>()
        var clears = 0
        var onRender: suspend (ScreenModel) -> DisplayResult = { DisplayResult.Accepted }
        override suspend fun render(screen: ScreenModel): DisplayResult {
            screens += screen
            return onRender(screen)
        }
        override suspend fun clear() { clears++; screens.clear() }
    }
    private class Fixture(scope: CoroutineScope) {
        val device = Device()
        val speech = Speech()
        val display = Display()
        val queries = mutableListOf<SearchQuery>()
        val loads = mutableListOf<RankedImageRef>()
        var onSearch: suspend (SearchQuery) -> SearchOutcome = { SearchOutcome.Match(RankedImageRef(it, "shirt.mp4")) }
        var onLoad: suspend (RankedImageRef) -> EncodedImage = { EncodedImage(it.query, byteArrayOf(1), "image/png") }
        var onPrepare: suspend (EncodedImage) -> PreparedImage = { PreparedImage(it.query, byteArrayOf(2), 10, 20, "image/png") }
        val controller = VoiceSearchController(scope, device, speech,
            SearchPort { queries += it; onSearch(it) },
            ImageSourcePort { loads += it; onLoad(it) },
            ImagePreparePort { encoded, _ -> onPrepare(encoded) }, display)
        suspend fun final(text: String = "Find white shirt in my collection") {
            speech.events.emit(TranscriptEvent(speech.turns.last().turnId, text, true, "en-US"))
        }
    }

    private suspend fun TestScope.ready(): Fixture = Fixture(backgroundScope).also {
        it.controller.setup(session)
        runCurrent()
        assertEquals(ScreenKind.Ready, it.controller.state.value.kind)
    }

    @Test fun partialsAndBlankFinalsNeverSearch() = runTest {
        val f = ready()
        f.controller.listen(); runCurrent()
        val id = f.speech.turns.single().turnId
        f.speech.events.emit(TranscriptEvent(id, "Find white", false, "en-US"))
        f.speech.events.emit(TranscriptEvent(id, "  ", true, "en-US")); runCurrent()
        assertEquals(0, f.queries.size)
        assertEquals("Find white", f.controller.state.value.transcript)
        f.controller.close()
    }

    @Test fun duplicateFinalProducesOneSearchAndStopsMicrophone() = runTest {
        val f = ready(); f.controller.listen(); runCurrent()
        f.final(" Find white shirt\n in my collection "); f.final(); runCurrent()
        assertEquals(1, f.queries.size)
        assertEquals("Find white shirt in my collection", f.queries.single().text)
        assertEquals(" Find white shirt\n in my collection ", f.queries.single().rawText)
        assertTrue(f.speech.stops >= 2)
        assertEquals(ScreenKind.ShowingImage, f.controller.state.value.kind)
        f.controller.close()
    }

    @Test fun separateTurnsWithIdenticalWordsSearchTwice() = runTest {
        val f = ready()
        repeat(2) { f.controller.listen(); runCurrent(); f.final(); runCurrent() }
        assertEquals(2, f.queries.size)
        assertNotEquals(f.queries[0].turnId, f.queries[1].turnId)
        assertNotEquals(f.queries[0].requestId, f.queries[1].requestId)
        f.controller.close()
    }

    @Test fun doubleListenStartsOnlyOneMicrophoneCollector() = runTest {
        val f = ready(); f.controller.listen(); f.controller.listen(); runCurrent()
        assertEquals(1, f.speech.turns.size)
        assertEquals(ScreenKind.Listening, f.display.screens.last().kind)
        f.controller.close()
    }

    @Test fun wrongTurnFinalIsIgnored() = runTest {
        val f = ready(); f.controller.listen(); runCurrent()
        f.speech.events.emit(TranscriptEvent(-1, "white shirt", true, "en-US")); runCurrent()
        assertEquals(0, f.queries.size)
        f.controller.close()
    }

    @Test fun cancelIsReadyWithoutErrorAndLeavesGlassesListenAction() = runTest {
        val f = ready(); f.controller.listen(); runCurrent(); f.controller.cancel(); runCurrent()
        assertEquals(ScreenKind.Ready, f.controller.state.value.kind)
        assertNull(f.controller.state.value.failure)
        assertEquals(ScreenKind.Ready, f.display.screens.last().kind)
        assertEquals(setOf(Action.Listen), f.display.screens.last().actions)
        f.controller.close()
    }

    @Test fun silenceHasBoundedSpeechTimeout() = runTest {
        val f = ready(); f.controller.listen(); runCurrent()
        advanceTimeBy(15_001); runCurrent()
        assertEquals(FailureCode.Timeout, f.controller.state.value.failure?.code)
        assertEquals(0, f.queries.size)
        assertTrue(f.speech.stops >= 2)
        f.controller.close()
    }

    @Test fun requestDeadlineCoversSearchAndImage() = runTest {
        val f = ready(); f.onSearch = { awaitCancellation() }
        f.controller.listen(); runCurrent(); f.final(); runCurrent()
        advanceTimeBy(20_001); runCurrent()
        assertEquals(FailureCode.Timeout, f.controller.state.value.failure?.code)
        assertEquals(Stage.Search, f.controller.state.value.failure?.stage)
        f.controller.close()
    }

    @Test fun lateCancellationResistantQueryCannotOverwriteNewResult() = runTest {
        val f = ready(); val release = CompletableDeferred<Unit>()
        f.onSearch = { query ->
            if (query.text == "old") withContext(NonCancellable) { release.await() }
            SearchOutcome.Match(RankedImageRef(query, "${query.text}.mp4"))
        }
        f.controller.listen(); runCurrent(); f.final("old"); runCurrent()
        f.controller.listen(); runCurrent(); f.final("new"); runCurrent()
        assertEquals("new", f.controller.state.value.query?.text)
        assertEquals(ScreenKind.ShowingImage, f.controller.state.value.kind)
        release.complete(Unit); runCurrent()
        assertEquals("new", f.controller.state.value.query?.text)
        assertEquals(listOf("new.mp4"), f.loads.map { it.filename })
        f.controller.close()
    }

    @Test fun scopeChangeDiscardsOldSearchAndFrozenVersion() = runTest {
        val f = ready(); val release = CompletableDeferred<Unit>()
        f.onSearch = { query -> withContext(NonCancellable) { release.await() }; SearchOutcome.Match(RankedImageRef(query, "old.mp4")) }
        f.controller.listen(); runCurrent(); f.final(); runCurrent()
        f.controller.setup(session.copy(sessionGeneration = 2, versionLancedb = 99)); runCurrent()
        release.complete(Unit); runCurrent()
        assertEquals(42L, f.queries.single().scope.versionLancedb)
        assertEquals(ScreenKind.Ready, f.controller.state.value.kind)
        assertEquals(2L, f.controller.state.value.sessionGeneration)
        assertTrue(f.loads.isEmpty())
        f.controller.close()
    }

    @Test fun logoutCancelsRequestAndClearsImage() = runTest {
        val f = ready(); f.onSearch = { awaitCancellation() }
        f.controller.listen(); runCurrent(); f.final(); runCurrent(); f.controller.reset(); runCurrent()
        assertEquals(ScreenKind.SetupRequired, f.controller.state.value.kind)
        assertNull(f.controller.state.value.image)
        assertTrue(f.device.closes > 0)
        f.controller.listen(); runCurrent(); assertEquals(1, f.queries.size)
        f.controller.close()
    }

    @Test fun emptyAndMalformedFirstHitRemainDistinctAndNeverLoadImage() = runTest {
        val f = ready(); f.onSearch = { SearchOutcome.Empty }
        f.controller.listen(); runCurrent(); f.final(); runCurrent()
        assertEquals(ScreenKind.Empty, f.controller.state.value.kind)
        assertTrue(Action.Retry in f.controller.state.value.actions)
        f.onSearch = { SearchOutcome.TopHitUnresolvable }; f.controller.retry(); runCurrent()
        assertEquals(FailureCode.TopImageUnavailable, f.controller.state.value.failure?.code)
        assertTrue(f.loads.isEmpty())
        f.controller.close()
    }

    @Test fun rejectedListeningReplacementNeverStartsAnotherMicrophone() = runTest {
        val f = ready()
        f.controller.listen(); runCurrent(); f.final(); runCurrent()
        assertEquals(ScreenKind.ShowingImage, f.controller.state.value.kind)
        f.display.onRender = { if (it.kind == ScreenKind.Listening) DisplayResult.Unavailable else DisplayResult.Accepted }
        f.controller.listen(); runCurrent()
        assertEquals(1, f.speech.turns.size)
        assertEquals(1, f.queries.size)
        assertEquals(FailureCode.DisplayUnavailable, f.controller.state.value.failure?.code)
        assertEquals(Stage.Display, f.controller.state.value.failure?.stage)
        assertNull(f.controller.state.value.image)
        f.controller.close()
    }

    @Test fun rejectedRenderCannotBecomeShowingImage() = runTest {
        val f = ready()
        f.display.onRender = { if (it.image != null) DisplayResult.Unavailable else DisplayResult.Accepted }
        f.controller.listen(); runCurrent(); f.final(); runCurrent()
        assertEquals(ScreenKind.Error, f.controller.state.value.kind)
        assertEquals(FailureCode.DisplayUnavailable, f.controller.state.value.failure?.code)
        assertNull(f.controller.state.value.image)
        f.controller.close()
    }

    @Test fun displayDeadlineCannotBecomeSuccess() = runTest {
        val f = ready(); f.display.onRender = { if (it.image != null) delay(6_000); DisplayResult.Accepted }
        f.controller.listen(); runCurrent(); f.final(); runCurrent()
        assertEquals(ScreenKind.SendingDisplay, f.controller.state.value.kind)
        advanceTimeBy(5_001); runCurrent()
        assertEquals(FailureCode.Timeout, f.controller.state.value.failure?.code)
        assertEquals(Stage.Display, f.controller.state.value.failure?.stage)
        f.controller.close()
    }

    @Test fun lateRenderAcceptanceCannotMarkOldRequestSuccessful() = runTest {
        val f = ready(); val release = CompletableDeferred<Unit>()
        f.display.onRender = { screen ->
            if (screen.image != null && screen.query?.text == "old") withContext(NonCancellable) { release.await() }
            DisplayResult.Accepted
        }
        f.controller.listen(); runCurrent(); f.final("old"); runCurrent()
        f.controller.cancel(); runCurrent()
        release.complete(Unit); runCurrent()
        assertEquals(ScreenKind.Ready, f.controller.state.value.kind)
        assertNull(f.controller.state.value.query)
        f.controller.close()
    }

    @Test fun staleDisplayActionsCannotDismissOrRetryNewResult() = runTest {
        val f = ready(); f.controller.listen(); runCurrent(); f.final("old"); runCurrent()
        val old = f.controller.state.value
        f.controller.listen(); runCurrent(); f.final("new"); runCurrent()
        f.display.actions.emit(ScreenAction(old.screenId, old.sessionGeneration, old.query?.requestId, Action.Retry))
        f.controller.onAction(ScreenAction(old.screenId, old.sessionGeneration, old.query?.requestId, Action.Dismiss)); runCurrent()
        assertEquals(2, f.queries.size)
        assertEquals("new", f.controller.state.value.query?.text)
        val current = f.controller.state.value
        f.display.actions.emit(ScreenAction(current.screenId, current.sessionGeneration, current.query?.requestId, Action.Retry)); runCurrent()
        assertEquals(3, f.queries.size)
        assertEquals("new", f.queries.last().text)
        f.controller.close()
    }

    @Test fun disconnectStopsSpeechAndDoesNotAutoReplayOnReconnect() = runTest {
        val f = ready(); f.controller.listen(); runCurrent()
        f.device.readiness.value = DeviceReadiness(); runCurrent()
        assertEquals(ScreenKind.Disconnected, f.controller.state.value.kind)
        f.device.readiness.value = DeviceReadiness(true, true, true); runCurrent()
        assertEquals(ScreenKind.Disconnected, f.controller.state.value.kind)
        assertEquals(0, f.queries.size)
        f.controller.setup(session.copy(sessionGeneration = 2)); runCurrent()
        assertEquals(ScreenKind.Ready, f.controller.state.value.kind)
        f.controller.close()
    }

    @Test fun backgroundRequiresExplicitReadinessSetup() = runTest {
        val f = ready(); f.controller.listen(); runCurrent(); f.final(); runCurrent()
        f.controller.background(); runCurrent()
        assertEquals(ScreenKind.SetupRequired, f.controller.state.value.kind)
        assertNull(f.controller.state.value.image)
        f.controller.listen(); runCurrent(); assertEquals(1, f.queries.size)
        f.controller.setup(session.copy(sessionGeneration = 2)); runCurrent()
        assertEquals(ScreenKind.Ready, f.controller.state.value.kind)
        f.controller.close()
    }

    @Test fun crossRequestImageAndUnboundedPreparedImageAreRejected() = runTest {
        val f = ready()
        f.onPrepare = { PreparedImage(it.query, byteArrayOf(1), 601, 600, "image/png") }
        f.controller.listen(); runCurrent(); f.final(); runCurrent()
        assertEquals(ScreenKind.Error, f.controller.state.value.kind)
        assertFalse(f.display.screens.any { it.image != null })
        f.onPrepare = { PreparedImage(it.query.copy(requestId = -1), byteArrayOf(1), 10, 10, "image/png") }
        f.controller.retry(); runCurrent()
        assertEquals(ScreenKind.Error, f.controller.state.value.kind)
        assertFalse(f.display.screens.any { it.image != null })
        f.controller.close()
    }

    @Test fun failedMicrophoneStopPreventsSearch() = runTest {
        val f = ready(); f.controller.listen(); runCurrent()
        f.speech.onStop = { throw AppException(AppFailure(Stage.Speech,
            FailureCode.SpeechUnavailable, true, "Microphone did not stop")) }
        f.final(); runCurrent()
        assertTrue(f.queries.isEmpty())
        assertEquals(FailureCode.SpeechUnavailable, f.controller.state.value.failure?.code)
        f.controller.close()
    }

    @Test fun acceptedDismissCannotQueueBehindAndCancelNewListen() = runTest {
        val f = ready(); f.controller.listen(); runCurrent(); f.final(); runCurrent()
        val old = f.controller.state.value
        f.controller.onAction(ScreenAction(old.screenId, old.sessionGeneration, old.query?.requestId, Action.Dismiss))
        f.controller.listen(); runCurrent()
        assertEquals(ScreenKind.Listening, f.controller.state.value.kind)
        assertEquals(2, f.speech.turns.size)
        f.controller.close()
    }
}
