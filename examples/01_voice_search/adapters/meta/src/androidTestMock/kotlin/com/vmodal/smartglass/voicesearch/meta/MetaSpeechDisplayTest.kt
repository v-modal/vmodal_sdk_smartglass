package com.vmodal.smartglass.voicesearch.meta

import android.Manifest
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vmodal.smartglass.voicesearch.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class MetaSpeechDisplayTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mock: MetaSimulator
    private lateinit var gateway: MetaDeviceGateway

    @Before fun setup() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BLUETOOTH_CONNECT)
        val context = instrumentation.targetContext
        withContext(Dispatchers.Main) {
            mock = MetaSimulator(context)
            mock.enable()
            gateway = MetaDeviceGateway(context, scope)
        }
    }

    @After fun teardown() = runBlocking {
        gateway.close()
        withContext(Dispatchers.Main) { mock.disable() }
        scope.cancel()
        InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
    }

    @Test fun speechAndLocalImageShareOneSessionAndActionsReturnFromSimulator() = runBlocking {
        gateway.prepare()
        assertTrue(gateway.readiness.value.ready)
        val speech = MetaSpeechAdapter(gateway)
        val display = MetaDisplayAdapter(gateway)
        val events = mutableListOf<TranscriptEvent>()
        val collecting = launch { speech.listen(VoiceTurn(1, 7, "en-US")).collect { events += it } }
        mock.awaitListening()
        mock.transcript("Find white", final = false)
        withTimeout(3_000) { while (events.isEmpty()) delay(20) }
        mock.transcript("Find white shirt in my collection")
        withTimeout(3_000) { while (events.none { it.isFinal }) delay(20) }
        assertFalse(events.first().isFinal)
        assertEquals("Find white shirt in my collection", events.last().rawText)
        assertEquals(1L, events.last().turnId)
        collecting.cancelAndJoin()
        assertFalse(mock.listening)

        val scope = SessionScope(7, "test-user", "vid_file", "shirts", "video", 1, "vid_img")
        val query = SearchQuery(2, 1, scope, "Find white shirt in my collection")
        val source = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(-1) }
        val bytes = ByteArrayOutputStream().use { out -> source.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
        source.recycle()
        val result = ScreenModel(10, 7, query, ScreenKind.ShowingImage, "White shirt", image = PreparedImage(query, bytes, 24, 16, "image/png"), actions = setOf(Action.Listen))
        assertEquals(DisplayResult.Accepted, display.render(result))
        val content = mock.displayContent()
        assertTrue("DAT accepted display payload: $content", content.contains("White shirt"))
        assertTrue("Local image should be embedded in DAT payload", content.contains("image", ignoreCase = true))
        val action = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3_000) { display.actions.first() } }
        val identifier = findIdentifier(JSONObject(content))
        assertNotNull("Clickable native DAT button must exist: $content", identifier)
        assertTrue(mock.click(identifier!!))
        assertEquals(ScreenAction(10, 7, 2, Action.Listen), action.await())
        assertEquals(1, gateway.sessionsCreated)
        display.clear()
    }

    @Test fun permissionDeniedIsExplicitAndNewSessionCanRestart() = runBlocking {
        mock.microphonePermission(false)
        val error = runCatching { gateway.prepare() }.exceptionOrNull() as? AppException
        assertEquals(FailureCode.PermissionDenied, error?.failure?.code)
        assertFalse(gateway.readiness.value.ready)
        mock.microphonePermission(true)
        gateway.prepare()
        gateway.close()
        assertFalse(gateway.readiness.value.connected)
        gateway.prepare()
        assertTrue(gateway.readiness.value.ready)
        assertEquals(2, gateway.sessionsCreated)
    }

    @Test fun audioOnlyModelCannotPretendToSupportDisplay() = runBlocking {
        gateway.close()
        withContext(Dispatchers.Main) { mock.disable(); mock.enable(displayCapable = false) }
        val error = runCatching { gateway.prepare() }.exceptionOrNull() as? AppException
        assertEquals(FailureCode.UnsupportedDevice, error?.failure?.code)
        assertFalse(gateway.readiness.value.displayReady)
    }

    @Test fun unsupportedLocaleStopsMicrophoneAndDisconnectPreventsDisplaySuccess() = runBlocking {
        gateway.prepare()
        val adapter = MetaSpeechAdapter(gateway)
        mock.locale("ja-JP")
        val result = async { runCatching { adapter.listen(VoiceTurn(2, 7, "en-US")).toList() } }
        mock.awaitListening()
        withTimeout(3_000) { gateway.speech!!.locale.first { it == "ja-JP" } }
        mock.transcript("白いシャツ")
        val failure = withTimeout(3_000) { result.await().exceptionOrNull() } as? AppException
        assertEquals(FailureCode.SpeechUnavailable, failure?.failure?.code)
        assertFalse(mock.listening)
        mock.disconnect()
        withTimeout(3_000) { gateway.readiness.first { !it.connected } }
        assertEquals(DisplayResult.Unavailable, MetaDisplayAdapter(gateway).render(
            ScreenModel(12, 7, kind = ScreenKind.Ready, status = "Ready"),
        ))
    }

    @Test fun completionWithoutFinalIsBoundedAndRepeatedListeningStopsCleanly() = runBlocking {
        gateway.prepare()
        val adapter = MetaSpeechAdapter(gateway, maxListenMs = 300)
        repeat(2) { index ->
            val result = async { runCatching { adapter.listen(VoiceTurn(index.toLong(), 7, "en-US")).toList() } }
            mock.awaitListening()
            mock.completion()
            val error = withTimeout(3_000) { result.await().exceptionOrNull() } as? AppException
            assertEquals(FailureCode.NoSpeech, error?.failure?.code)
            assertFalse(mock.listening)
        }
        assertEquals(1, gateway.sessionsCreated)
    }

    @Test fun identicalFinalOnlyTurnsKeepOneSessionAndDisplayCapability() = runBlocking {
        gateway.prepare()
        val adapter = MetaSpeechAdapter(gateway)
        val display = gateway.display
        var previous: Any? = null
        repeat(2) { index ->
            val turn = VoiceTurn(index.toLong() + 1, 7, "en-US")
            val result = async { adapter.listen(turn).first { it.isFinal } }
            mock.awaitListening()
            assertTrue(gateway.readiness.value.ready)
            assertSame(display, gateway.display)
            assertNotSame(previous, gateway.speech)
            previous = gateway.speech
            // Deliberately no partial text: the prior final must not suppress this final.
            mock.transcript("Find white shirt in my collection")
            val transcript = withTimeout(3_000) { result.await() }
            assertEquals(turn.turnId, transcript.turnId)
            assertEquals("Find white shirt in my collection", transcript.rawText)
            assertFalse(mock.listening)
        }
        assertEquals(1, gateway.sessionsCreated)
        assertSame(display, gateway.display)
    }

    private fun findIdentifier(node: Any): String? {
        if (node is JSONObject) {
            for (key in node.keys()) {
                val value = node.get(key)
                if ((key.contains("click", true) || key == "identifier") && value is String) return value
                findIdentifier(value)?.let { return it }
            }
        }
        if (node is org.json.JSONArray) for (i in 0 until node.length()) findIdentifier(node.get(i))?.let { return it }
        return null
    }
}
