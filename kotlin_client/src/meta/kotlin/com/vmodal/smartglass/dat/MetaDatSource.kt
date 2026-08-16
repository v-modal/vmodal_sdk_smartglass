package com.vmodal.smartglass.dat

import android.content.Context
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.vmodal.smartglass.DevicePermissionRequester
import com.vmodal.smartglass.SmartGlassError
import com.vmodal.smartglass.VideoConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal class MetaDatSource(
    private val context: Context,
    private val permissionRequester: DevicePermissionRequester?,
    private val selector: DeviceSelector = AutoDeviceSelector(),
) : DatSource {
    private val mutableSignals = MutableSharedFlow<DatSignal>(extraBufferCapacity = 16)
    override val signals: Flow<DatSignal> = mutableSignals.asSharedFlow()
    private var scope: CoroutineScope? = null
    private var session: DeviceSession? = null
    private var stream: Stream? = null
    private var video = VideoConfig()
    private val jobs = mutableListOf<Job>()

    override suspend fun open(video: VideoConfig) {
        this.video = video
        Wearables.initialize(context.applicationContext).onFailure { error, cause ->
            throw SmartGlassError.DeviceSessionFailed(
                cause?.javaClass?.simpleName ?: error.javaClass.simpleName,
            )
        }
        if (Wearables.registrationState.value != RegistrationState.REGISTERED) {
            throw SmartGlassError.RegistrationRequired()
        }
        ensureCameraPermission()
        val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = ownScope
        val created = Wearables.createSession(selector).getOrNull()
            ?: throw SmartGlassError.DeviceUnavailable()
        session = created
        jobs += ownScope.launch {
            created.errors.collect { error ->
                val code = error.name
                mutableSignals.emit(
                    DatSignal.Failure(
                        code,
                        code == "THERMAL_CRITICAL" || code == "THERMAL_EMERGENCY",
                    ),
                )
            }
        }
        jobs += ownScope.launch {
            created.state.collect { state ->
                when (state) {
                    DeviceSessionState.PAUSED -> mutableSignals.emit(DatSignal.Paused)
                    DeviceSessionState.STARTED -> mutableSignals.emit(DatSignal.Resumed)
                    else -> Unit
                }
            }
        }
        created.start()
        withTimeout(30_000) { created.state.first { it == DeviceSessionState.STARTED } }
    }

    override suspend fun startCapture(onFrame: (DatFrame) -> Unit) {
        val ownScope = scope ?: throw SmartGlassError.DeviceSessionFailed("DAT_NOT_OPEN")
        val activeSession = session ?: throw SmartGlassError.DeviceSessionFailed("DAT_NOT_OPEN")
        val added = activeSession.addStream(
            StreamConfiguration(
                videoQuality = videoQuality(video.preferredWidth, video.preferredHeight),
                frameRate = video.preferredFps,
                compressVideo = false,
            ),
        ).getOrNull() ?: throw SmartGlassError.DeviceSessionFailed("DAT_ADD_STREAM")
        stream = added
        jobs += ownScope.launch {
            added.videoStream.collect { frame ->
                try {
                    onFrame(DatFrameAdapter.adapt(frame))
                } catch (_: SmartGlassError.UnsupportedInputFormat) {
                    mutableSignals.emit(DatSignal.Failure("UNSUPPORTED_INPUT_FORMAT"))
                }
            }
        }
        jobs += ownScope.launch {
            added.errorStream.collect { error ->
                val code = error.name
                if (code == "STREAM_ERROR" || code == "BATTERY_LOW" || code == "THERMAL_HOT") {
                    mutableSignals.emit(DatSignal.Warning(code))
                } else {
                    mutableSignals.emit(DatSignal.Failure(code, code == "THERMAL_EMERGENCY"))
                }
            }
        }
        jobs += ownScope.launch {
            added.state.collect { state ->
                when (state) {
                    StreamState.PAUSED -> mutableSignals.emit(DatSignal.Paused)
                    StreamState.STREAMING -> mutableSignals.emit(DatSignal.Resumed)
                    else -> Unit
                }
            }
        }
        added.start().onFailure { error, cause ->
            throw SmartGlassError.DeviceSessionFailed(
                cause?.javaClass?.simpleName ?: error.javaClass.simpleName,
            )
        }
    }

    override suspend fun stop() {
        jobs.forEach(Job::cancel)
        jobs.clear()
        stream?.stop()
        stream = null
        session?.stop()
        session = null
        scope?.cancel()
        scope = null
    }

    private suspend fun ensureCameraPermission() {
        if (Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull() == PermissionStatus.Granted) return
        if (permissionRequester?.requestCameraPermission() != true) {
            throw SmartGlassError.PermissionDenied()
        }
    }

    private fun videoQuality(width: Int, height: Int): VideoQuality = when {
        width >= 720 || height >= 1280 -> VideoQuality.HIGH
        width >= 504 || height >= 896 -> VideoQuality.MEDIUM
        else -> VideoQuality.LOW
    }
}
