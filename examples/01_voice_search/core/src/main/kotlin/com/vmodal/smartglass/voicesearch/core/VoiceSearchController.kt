package com.vmodal.smartglass.voicesearch.core

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** One state owner; workers may finish late but may never publish across a generation. */
class VoiceSearchController(
    private val scope: CoroutineScope,
    private val device: DevicePort,
    private val speech: SpeechPort,
    private val search: SearchPort,
    private val images: ImageSourcePort,
    private val preparer: ImagePreparePort,
    private val display: DisplayPort,
    private val limits: ImageLimits = ImageLimits(),
    private val deadlines: Deadlines = Deadlines(),
) {
    private val generation = AtomicLong()
    private val ids = AtomicLong()
    private val displayLock = Mutex()
    private val speechLock = Mutex()
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(
        ScreenModel(0, 0, kind = ScreenKind.SetupRequired, status = "Set up account and glasses"),
    )
    val state: StateFlow<ScreenModel> = mutableState.asStateFlow()
    private var session: SessionScope? = null
    private var active: Job? = null
    private var accepted: SearchQuery? = null
    private var closed = false

    private val owner = scope.launch { for (command in commands) command() }
    private val actionCollector = scope.launch { display.actions.collect { onAction(it) } }
    private val readinessCollector = scope.launch {
        device.readiness.collect { readiness ->
            enqueue {
                if (session != null && !readiness.ready && state.value.kind !in setOf(
                        ScreenKind.SetupRequired, ScreenKind.Disconnected,
                    )) {
                    val token = invalidate()
                    accepted = null
                    publish(token, ScreenKind.Disconnected, "Glasses disconnected", failure = readiness.failure)
                    cleanup(token, closeDevice = true)
                }
            }
        }
    }

    private fun enqueue(command: suspend () -> Unit) { if (!closed) commands.trySend(command) }

    /** Setup is explicit after background/disconnect; readiness never replays old speech. */
    suspend fun setup(session: SessionScope) {
        val done = CompletableDeferred<Unit>()
        enqueue {
            val token = invalidate()
            this.session = session
            accepted = null
            publish(token, ScreenKind.SetupRequired, "Preparing glasses")
            val previous = active
            active = scope.launch {
                awaitPrevious(previous)
                guarded(token) {
                    speech.stop()
                    checkCurrent(token)
                    device.prepare()
                    checkCurrent(token)
                    if (device.readiness.value.ready) {
                        clear(token)
                        post(token, ScreenKind.Ready, "Ready")
                    }
                    else postFailure(token, AppFailure(Stage.Setup, FailureCode.UnsupportedDevice, true,
                        "Glasses speech and display must both be ready"))
                }
            }
            done.complete(Unit)
        }
        if (closed) return
        done.await()
    }

    fun listen(locale: String = "en-US") = enqueue { startListening(locale) }

    private fun startListening(locale: String) {
        val current = session ?: return
        if (!device.readiness.value.ready || state.value.kind == ScreenKind.Listening) return
        val token = invalidate()
        accepted = null
        val turn = VoiceTurn(ids.incrementAndGet(), current.sessionGeneration, locale)
        val listening = publish(token, ScreenKind.Listening, "Listening", actions = setOf(Action.Cancel), render = false)
        active = scope.launch {
            guarded(token) {
                clear(token)
                if (listening != null) bestEffort { render(token, listening) }
                checkCurrent(token)
                var final: TranscriptEvent? = null
                speechLock.withLock {
                    checkCurrent(token)
                    try {
                        final = withTimeout(deadlines.listeningMs) {
                            speech.listen(turn).firstOrNull { event ->
                                checkCurrent(token)
                                if (event.turnId != turn.turnId) false
                                else if (event.isFinal && normalizeTranscript(event.rawText).isNotBlank()) true
                                else {
                                    if (!event.isFinal) post(token, ScreenKind.Listening, "Listening",
                                        transcript = event.rawText, actions = setOf(Action.Cancel))
                                    false
                                }
                            }
                        }
                    } finally {
                        withContext(NonCancellable) { withTimeout(deadlines.displayMs) { speech.stop() } }
                    }
                }
                checkCurrent(token)
                val text = final ?: throw AppException(AppFailure(Stage.Speech, FailureCode.NoSpeech,
                    true, "No completed speech received", turnId = turn.turnId))
                val query = SearchQuery(ids.incrementAndGet(), turn.turnId, current,
                    normalizeTranscript(text.rawText), text.rawText)
                post(token, ScreenKind.Finalizing, "Speech received", query, text.rawText)
                checkCurrent(token)
                commands.send { if (isCurrent(token)) accepted = query }
                runQuery(token, query)
            }
        }
    }

    fun retry() = enqueue { startRetry() }

    private fun startRetry() {
        val old = accepted ?: return
        if (old.scope != session || !device.readiness.value.ready) return
        val token = invalidate()
        val query = old.copy(requestId = ids.incrementAndGet())
        accepted = query
        active = scope.launch {
            guarded(token) { clear(token); checkCurrent(token); runQuery(token, query) }
        }
    }

    fun cancel() = enqueue { cancelCurrent() }

    private fun cancelCurrent() {
        val token = invalidate()
        accepted = null
        val ready = device.readiness.value.ready && session != null
        publish(token, if (ready) ScreenKind.Ready else ScreenKind.SetupRequired,
            if (ready) "Ready" else "Set up account and glasses", render = false)
        cleanup(token)
    }

    fun onAction(action: ScreenAction) = enqueue {
        val screen = state.value
        if (action.screenId != screen.screenId || action.sessionGeneration != screen.sessionGeneration ||
            action.requestId != screen.query?.requestId || action.action !in screen.actions) return@enqueue
        when (action.action) {
            Action.Listen -> startListening("en-US")
            Action.Retry -> startRetry()
            Action.Cancel, Action.Dismiss -> cancelCurrent()
        }
    }

    suspend fun reset() = teardown(removeSession = true, permanently = false)
    suspend fun background() = teardown(removeSession = false, permanently = false)
    suspend fun close() = teardown(removeSession = true, permanently = true)

    private suspend fun teardown(removeSession: Boolean, permanently: Boolean) {
        if (closed) return
        val done = CompletableDeferred<Unit>()
        enqueue {
            val token = invalidate()
            val previous = active
            if (removeSession) session = null
            accepted = null
            publish(token, ScreenKind.SetupRequired, "Set up account and glasses")
            active = scope.launch {
                try {
                    awaitPrevious(previous)
                    withContext(NonCancellable) {
                        bestEffort { speechLock.withLock { speech.stop() } }
                        bestEffort { clear(token) }
                        if (isCurrent(token)) bestEffort { device.close() }
                    }
                } finally { done.complete(Unit) }
            }
        }
        done.await()
        if (permanently) {
            closed = true
            actionCollector.cancelAndJoin()
            readinessCollector.cancelAndJoin()
            commands.close()
            owner.join()
        }
    }

    private fun invalidate(): Long {
        val token = generation.incrementAndGet()
        active?.cancel()
        return token
    }

    private fun isCurrent(token: Long): Boolean = token == generation.get() && !closed
    private fun checkCurrent(token: Long) { if (!isCurrent(token)) throw CancellationException("Obsolete turn") }

    private suspend fun runQuery(token: Long, query: SearchQuery) = withTimeout(deadlines.requestMs) {
        post(token, ScreenKind.Searching, "Searching", query, query.text)
        checkCurrent(token)
        val outcome = search.topOne(query)
        checkCurrent(token)
        when (outcome) {
            SearchOutcome.Empty -> post(token, ScreenKind.Empty, "No results", query, query.text,
                actions = setOf(Action.Retry, Action.Listen, Action.Dismiss))
            SearchOutcome.TopHitUnresolvable -> throw AppException(AppFailure(Stage.Image,
                FailureCode.TopImageUnavailable, true, "The first result has no usable image", query.requestId))
            is SearchOutcome.Match -> {
                require(outcome.ref.query == query) { "Search returned a different request" }
                post(token, ScreenKind.ResolvingImage, "Loading first result", query, query.text)
                checkCurrent(token)
                val encoded = images.load(outcome.ref)
                checkCurrent(token)
                require(encoded.query == query) { "Image belongs to another request" }
                post(token, ScreenKind.PreparingImage, "Preparing image", query, query.text)
                checkCurrent(token)
                val prepared = preparer.prepare(encoded, limits)
                checkCurrent(token)
                require(prepared.query == query && prepared.width <= limits.maxSide &&
                    prepared.height <= limits.maxSide && prepared.bytes.size <= limits.maxEncodedBytes &&
                    prepared.width.toLong() * prepared.height * 4 <= limits.maxDecodedBytes) {
                    "Prepared image exceeds bounds or belongs to another request"
                }
                val screen = post(token, ScreenKind.SendingDisplay, "Sending image", query, query.text,
                    image = prepared, caption = outcome.ref.caption,
                    actions = setOf(Action.Listen, Action.Retry, Action.Dismiss), render = false) ?: return@withTimeout
                val submitted = screen.copy(kind = ScreenKind.ShowingImage, status = "First result")
                val result = render(token, submitted)
                checkCurrent(token)
                when (result) {
                    DisplayResult.Accepted -> commands.send {
                        if (isCurrent(token)) mutableState.value = submitted
                    }
                    DisplayResult.Unavailable -> throw AppException(AppFailure(Stage.Display,
                        FailureCode.DisplayUnavailable, true, "Glasses display unavailable", query.requestId))
                    is DisplayResult.Failed -> throw AppException(result.failure)
                }
            }
        }
    }

    private suspend fun guarded(token: Long, block: suspend () -> Unit) {
        try { checkCurrent(token); block() }
        catch (error: TimeoutCancellationException) {
            if (isCurrent(token)) postFailure(token, AppFailure(currentStage(), FailureCode.Timeout,
                true, "Operation timed out", state.value.query?.requestId))
        } catch (error: CancellationException) { throw error }
        catch (error: AppException) { if (isCurrent(token)) postFailure(token, error.failure) }
        catch (error: Exception) {
            if (isCurrent(token)) postFailure(token, AppFailure(currentStage(), when (currentStage()) {
                Stage.Speech -> FailureCode.SpeechUnavailable
                Stage.Image, Stage.Prepare -> FailureCode.TopImageUnavailable
                Stage.Display -> FailureCode.DisplayUnavailable
                Stage.Setup -> FailureCode.UnsupportedDevice
                Stage.Search -> FailureCode.SearchFailed
            }, true, "Unable to complete ${currentStage().name.lowercase()}", state.value.query?.requestId))
        }
    }

    private fun currentStage(): Stage = when (state.value.kind) {
        ScreenKind.Listening, ScreenKind.Finalizing -> Stage.Speech
        ScreenKind.ResolvingImage -> Stage.Image
        ScreenKind.PreparingImage -> Stage.Prepare
        ScreenKind.SendingDisplay, ScreenKind.ShowingImage -> Stage.Display
        ScreenKind.SetupRequired, ScreenKind.Ready, ScreenKind.Disconnected -> Stage.Setup
        else -> Stage.Search
    }

    private suspend fun postFailure(token: Long, failure: AppFailure) {
        post(token, ScreenKind.Error, failure.message, accepted, failure = failure,
            actions = if (accepted != null) setOf(Action.Retry, Action.Listen, Action.Dismiss)
            else setOf(Action.Listen, Action.Dismiss))
    }

    private fun publish(
        token: Long, kind: ScreenKind, status: String, query: SearchQuery? = null,
        transcript: String? = null, image: PreparedImage? = null, caption: String? = null,
        actions: Set<Action> = if (kind == ScreenKind.Ready) setOf(Action.Listen) else emptySet(),
        failure: AppFailure? = null, render: Boolean = true,
    ): ScreenModel? {
        if (!isCurrent(token)) return null
        val screen = ScreenModel(ids.incrementAndGet(), session?.sessionGeneration ?: 0, query,
            kind, status, transcript, image, caption, actions, failure)
        mutableState.value = screen
        if (render && device.readiness.value.displayReady) scope.launch {
            try { render(token, screen) } catch (_: CancellationException) { /* Superseded or bounded send. */ }
            catch (_: Exception) { /* Phone state remains authoritative for status screens. */ }
        }
        return screen
    }

    private suspend fun post(
        token: Long, kind: ScreenKind, status: String, query: SearchQuery? = null,
        transcript: String? = null, image: PreparedImage? = null, caption: String? = null,
        actions: Set<Action> = if (kind == ScreenKind.Ready) setOf(Action.Listen) else emptySet(),
        failure: AppFailure? = null, render: Boolean = true,
    ): ScreenModel? {
        val done = CompletableDeferred<ScreenModel?>()
        commands.send { done.complete(publish(token, kind, status, query, transcript, image, caption,
            actions, failure, render)) }
        return done.await()
    }

    private suspend fun render(token: Long, screen: ScreenModel): DisplayResult = displayLock.withLock {
        checkCurrent(token)
        // Drop queued progress/status screens after a newer screen has become active.
        if (state.value.screenId != screen.screenId) return@withLock DisplayResult.Unavailable
        withTimeout(deadlines.displayMs) { display.render(screen) }.also { checkCurrent(token) }
    }

    private suspend fun clear(token: Long) = displayLock.withLock {
        checkCurrent(token)
        withTimeout(deadlines.displayMs) { display.clear() }
        checkCurrent(token)
    }

    private fun cleanup(token: Long, closeDevice: Boolean = false) {
        val previous = active
        active = scope.launch {
            awaitPrevious(previous)
            if (!isCurrent(token)) return@launch
            withContext(NonCancellable) {
                bestEffort { speechLock.withLock { speech.stop() } }
                bestEffort { clear(token) }
                if (closeDevice && isCurrent(token)) bestEffort { device.close() }
            }
            if (isCurrent(token) && !closeDevice && device.readiness.value.displayReady) {
                bestEffort { render(token, state.value) }
            }
        }
    }

    private suspend fun awaitPrevious(previous: Job?) {
        withTimeoutOrNull(deadlines.displayMs) { previous?.join() }
    }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try { withTimeoutOrNull(deadlines.displayMs) { block() } }
        catch (_: Exception) { /* Local cleanup must finish even when remote teardown rejects. */ }
    }
}
