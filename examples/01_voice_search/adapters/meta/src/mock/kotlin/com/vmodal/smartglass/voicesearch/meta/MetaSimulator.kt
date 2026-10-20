package com.vmodal.smartglass.voicesearch.meta

import android.content.Context
import android.util.Base64
import android.view.View
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Debug-only access to the real DAT 1.0.1 simulator; release contains neither this nor MDK. */
class MetaSimulator(private val context: Context) {
    private val kit = MockDeviceKit.getInstance(context)
    private lateinit var glasses: MockGlasses
    private var port = 0

    fun enable(displayCapable: Boolean = true) {
        kit.enable()
        glasses = kit.pairGlasses(if (displayCapable) GlassesModel.META_RAYBAN_DISPLAY else GlassesModel.RAYBAN_META)
            .fold(onSuccess = { it }, onFailure = { error, _ -> error("Mock pairing failed: ${error.description}") })
        glasses.powerOn()
        glasses.unfold()
        glasses.don()
        glasses.services.speech.setLocale("en-US")
        port = kit.startTestServer(0).fold(onSuccess = { it }, onFailure = { error, _ -> error("Mock test server failed: ${error.description}") })
    }

    suspend fun awaitListening() = withTimeout(10_000) {
        while (!glasses.services.speech.isListening) delay(20)
    }

    fun transcript(text: String, final: Boolean = true) {
        glasses.services.speech.simulateTranscription(text, isFinal = final, confidence = 0.9f)
    }
    fun completion() = glasses.services.speech.simulateCompletion()
    fun locale(value: String) = glasses.services.speech.setLocale(value)
    fun speechError() = glasses.services.speech.simulateError(1, "Injected recognition failure")
    fun microphonePermission(granted: Boolean) = kit.permissions.set(Permission.MICROPHONE, if (granted) PermissionStatus.Granted else PermissionStatus.Denied)
    fun click(identifier: String): Boolean = glasses.services.display.sendClick(identifier)
    fun preview(): View = glasses.services.display.createPreviewView(context)
    fun disconnect() = glasses.powerOff()
    fun reconnect() { glasses.powerOn(); glasses.unfold(); glasses.don() }
    fun disable() { kit.stopTestServer(); kit.disable() }
    val listening: Boolean get() = glasses.services.speech.isListening

    /** Read simulator's accepted display payload via its public HTTP test server. */
    suspend fun displayContent(): String = withContext(Dispatchers.IO) {
        val connection = URL("http://127.0.0.1:$port/display/content").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("{}".toByteArray()) }
            val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (response.isNull("payload")) "" else Base64.decode(response.getString("payload"), Base64.DEFAULT).toString(Charsets.UTF_8)
        } finally { connection.disconnect() }
    }
}
