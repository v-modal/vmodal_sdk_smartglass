package com.vmodal.smartglass.voicesearch

import android.Manifest
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vmodal.smartglass.voicesearch.core.*
import com.vmodal.smartglass.voicesearch.meta.MetaSimulator
import com.vmodal.smartglass.voicesearch.vmodal.VmodalSessionGateway
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real app composition, SDK HTTP transport, Meta speech/display; only the VModal endpoint is mocked. */
@RunWith(AndroidJUnit4::class)
class VoiceSearchFlowTest {
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var simulator: MetaSimulator
    private lateinit var graph: AppGraph
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val server = MockWebServer()
    @Volatile private var search = """{"data":[{"filename":"rank1.mp4","caption":"White shirt rank 1","ts_unix":"0000000002000"},{"filename":"rank2.mp4"}],"cnt_total":2}"""

    @Before fun setup() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BLUETOOTH_CONNECT)
        val image = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(-1) }
        val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        image.recycle()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath
                val body = when {
                    path.endsWith("/me") -> """{"user_id":"test-user","tenant_id":"test-tenant"}"""
                    path.endsWith("/groups") -> """{"data":[{"mode":"vid_file","group_name":"shirts","lancedb_versions":["v1","v7"]}]}"""
                    path.endsWith("/search") -> search
                    path.endsWith("/get_url_bulk") -> """{"records":[{"input_index":0,"found":true,"url_pre_signed":"https://fixture.invalid/rank1"}]}"""
                    path.endsWith("/get_image") -> return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes))
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        server.start()
        val endpoint = server.url("/").toString().trimEnd('/')
        val context = instrumentation.targetContext
        withContext(Dispatchers.Main) {
            simulator = MetaSimulator(context)
            simulator.enable()
            graph = AppGraph(context, owner, VmodalSessionGateway(endpoint))
        }
        val session = graph.vmodal.connect("test-runtime-key", 1, "shirts", "default")
        graph.controller.setup(session)
        await(ScreenKind.Ready)
        Unit
    }

    @After fun teardown() = runBlocking {
        if (::graph.isInitialized) graph.close()
        if (::simulator.isInitialized) withContext(Dispatchers.Main) { simulator.disable() }
        owner.cancel()
        server.shutdown()
        InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
    }

    @Test fun nativeListenActionRunsRankOneAndTwentySequentialTurns() = runBlocking {
        // Select the actual button ID from DAT's accepted screen, then inject a native MDK click.
        val readyContent = withTimeout(5_000) {
            var text = simulator.displayContent()
            while (!text.contains("Ready")) { delay(20); text = simulator.displayContent() }
            text
        }
        val button = requireNotNull(findIdentifier(JSONObject(readyContent)))
        assertTrue(simulator.click(button))
        var previous = 0L
        repeat(20) { index ->
            android.util.Log.i("VoiceSearchFlowTest", "Starting simulator turn $index; state=${graph.controller.state.value.kind}")
            if (index > 0) graph.controller.listen()
            try { simulator.awaitListening() }
            catch (error: TimeoutCancellationException) {
                throw AssertionError("Turn $index did not start listening; state=${graph.controller.state.value}", error)
            }
            simulator.transcript("Find white", final = false)
            delay(50)
            assertEquals(index, searchRequests().size)
            simulator.transcript("Find white shirt in my collection  ")
            simulator.completion()
            val screen = withTimeout(15_000) {
                graph.controller.state.first { it.kind == ScreenKind.ShowingImage && (it.query?.requestId ?: 0) > previous }
            }
            previous = screen.query!!.requestId
            assertEquals("Find white shirt in my collection", screen.query!!.text)
            assertEquals(7L, screen.query!!.scope.versionLancedb)
            assertNotNull(screen.image)
            simulator.awaitStopped()
            assertFalse(simulator.listening)
        }
        assertEquals(20, searchRequests().size)
        for (request in searchRequests()) {
            val body = JSONObject(request.body.clone().readUtf8())
            assertEquals(1, body.getInt("limit"))
            assertEquals(0, body.getInt("offset"))
            assertEquals("Find white shirt in my collection", body.getString("query_text"))
        }
        val lookups = requests.filter { it.requestUrl!!.encodedPath.endsWith("/get_url_bulk") }
        assertEquals(20, lookups.size)
        assertTrue(lookups.all { it.body.clone().readUtf8().contains("rank1.mp4") })
        assertTrue(lookups.none { it.body.clone().readUtf8().contains("rank2.mp4") })
        val content = simulator.displayContent()
        assertTrue(content.contains("White shirt rank 1"))
        assertTrue(content.contains("image", ignoreCase = true))
        val rejected = runCatching { graph.selectCollection("not-visible", "default") }.exceptionOrNull()
        assertEquals(FailureCode.AuthRequired, (rejected as AppException).failure.code)
        assertEquals(ScreenKind.SetupRequired, graph.controller.state.value.kind)
        assertNull(graph.controller.state.value.image)
        graph.controller.listen()
        delay(100)
        assertEquals(20, searchRequests().size)
        graph.controller.background()
        assertFalse(graph.meta.readiness.value.ready)
        simulator.awaitStopped()
        assertFalse(simulator.listening)
    }

    @Test fun emptyAndMalformedTopHitHaveDifferentScreensAndNoDownload() = runBlocking {
        search = """{"data":[],"cnt_total":200}"""
        speak()
        await(ScreenKind.Empty)
        assertTrue(Action.Retry in graph.controller.state.value.actions)
        assertFalse(requests.any { it.requestUrl!!.encodedPath.endsWith("/get_image") })
        search = """{"data":[{"title":"malformed first row"},{"filename":"rank2.mp4"}]}"""
        graph.controller.retry()
        val screen = await(ScreenKind.Error)
        assertEquals(FailureCode.TopImageUnavailable, screen.failure!!.code)
        assertFalse(requests.any { it.requestUrl!!.encodedPath.endsWith("/get_image") })
        graph.logout()
        assertEquals(ScreenKind.SetupRequired, graph.controller.state.value.kind)
    }

    private suspend fun speak() {
        graph.controller.listen()
        simulator.awaitListening()
        simulator.transcript("Find white shirt in my collection")
        simulator.completion()
    }
    private suspend fun await(kind: ScreenKind) = withTimeout(15_000) { graph.controller.state.first { it.kind == kind } }
    private fun searchRequests() = requests.filter { it.requestUrl!!.encodedPath.endsWith("/search") }
    private fun findIdentifier(node: Any): String? {
        if (node is JSONObject) for (key in node.keys()) {
            val value = node.get(key)
            if ((key.contains("click", true) || key == "identifier") && value is String) return value
            findIdentifier(value)?.let { return it }
        }
        if (node is org.json.JSONArray) for (i in 0 until node.length()) findIdentifier(node.get(i))?.let { return it }
        return null
    }
}
