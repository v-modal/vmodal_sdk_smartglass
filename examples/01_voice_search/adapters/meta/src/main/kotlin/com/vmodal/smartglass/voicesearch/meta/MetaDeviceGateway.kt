package com.vmodal.smartglass.voicesearch.meta

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.types.WearablesError
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.removeDisplay
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.speech.Speech
import com.meta.wearable.dat.speech.addSpeech
import com.meta.wearable.dat.speech.removeSpeech
import com.vmodal.smartglass.voicesearch.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One owner and one DAT session for both capabilities. Call close on background/logout. */
class MetaDeviceGateway(context: Context, private val scope: CoroutineScope) : DevicePort {
    private val context = context.applicationContext
    private val lock = Mutex()
    private val state = MutableStateFlow(DeviceReadiness())
    override val readiness = state.asStateFlow()
    private var session: DeviceSession? = null
    @Volatile internal var speech: Speech? = null
        private set
    @Volatile internal var display: Display? = null
        private set
    private val jobs = mutableListOf<Job>()
    @Volatile internal var generation = 0L
        private set
    internal var sessionsCreated = 0
        private set

    fun initialize() {
        Wearables.initialize(context).onFailure { error, _ ->
            if (error != WearablesError.ALREADY_INITIALIZED) {
                throw AppException(failure(Stage.Setup, FailureCode.UnsupportedDevice, "Meta initialization failed"))
            }
        }
    }

    /** Invoke from the phone's explicit Register button after Bluetooth permission. */
    fun startRegistration(activity: Activity) {
        initialize()
        Wearables.startRegistration(activity)
    }

    fun openFirmwareUpdate(activity: Activity): Boolean = Wearables.openFirmwareUpdate(activity).isSuccess
    fun openGlassesAppUpdate(activity: Activity): Boolean = Wearables.openDATGlassesAppUpdate(activity).isSuccess

    /** Vendor-free Activity Result contract; launch Unit only from a user action. */
    fun microphonePermissionContract(): ActivityResultContract<Unit, Boolean> =
        object : ActivityResultContract<Unit, Boolean>() {
            private val delegate = Wearables.RequestPermissionContract()
            override fun createIntent(context: Context, input: Unit): Intent =
                delegate.createIntent(context, Permission.MICROPHONE)
            override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
                delegate.parseResult(resultCode, intent).getOrNull() == PermissionStatus.Granted
            override fun getSynchronousResult(context: Context, input: Unit): SynchronousResult<Boolean>? =
                delegate.getSynchronousResult(context, Permission.MICROPHONE)?.let {
                    SynchronousResult(it.value.getOrNull() == PermissionStatus.Granted)
                }
        }

    override suspend fun prepare() = withContext(Dispatchers.Main.immediate) {
        lock.withLock {
            if (readiness.value.ready) return@withLock
            teardown()
            initialize()
            if (Wearables.registrationState.value != RegistrationState.REGISTERED) {
                fail(FailureCode.AuthRequired, "Register this app with Meta AI first")
            }
            if (Wearables.checkPermissionStatus(Permission.MICROPHONE).getOrNull() != PermissionStatus.Granted) {
                fail(FailureCode.PermissionDenied, "Allow the glasses microphone from the phone")
            }
            if (Wearables.devices.value.none { Wearables.devicesMetadata[it]?.value?.isDisplayCapable() == true }) {
                fail(FailureCode.UnsupportedDevice, "Connect display-capable Meta glasses")
            }
            val current = Wearables.createSession(AutoDeviceSelector(filter = {
                it.isDisplayCapable() && it.linkState == LinkState.CONNECTED &&
                    it.compatibility == DeviceCompatibility.COMPATIBLE
            })).fold(
                onSuccess = { it },
                onFailure = { _, _ -> fail(FailureCode.UnsupportedDevice, "Unable to create a glasses session") },
            )
            session = current
            sessionsCreated++
            val epoch = generation
            jobs += scope.launch(Dispatchers.Main.immediate) {
                current.deviceInfo.collect { device ->
                    if (generation == epoch && device.linkState == LinkState.DISCONNECTED) {
                        teardown()
                        state.value = DeviceReadiness(failure = failure(Stage.Setup, FailureCode.UnsupportedDevice, "Glasses disconnected"))
                    }
                }
            }
            jobs += scope.launch(Dispatchers.Main.immediate) {
                current.errors.collect {
                    if (generation == epoch) {
                        teardown()
                        state.value = DeviceReadiness(failure = failure(Stage.Setup, FailureCode.UnsupportedDevice, "Glasses session disconnected or needs an update"))
                    }
                }
            }
            jobs += scope.launch(Dispatchers.Main.immediate) {
                current.state.collect { value ->
                    if (generation == epoch && value != DeviceSessionState.STARTED) {
                        state.value = state.value.copy(connected = false, speechReady = false, displayReady = false)
                    }
                    if (generation == epoch && value == DeviceSessionState.STOPPED) {
                        teardown()
                        state.value = DeviceReadiness(failure = failure(Stage.Setup, FailureCode.UnsupportedDevice, "Glasses session stopped; reconnect to continue"))
                    }
                    if (generation == epoch && value == DeviceSessionState.STARTED && speech != null) {
                        state.value = DeviceReadiness(true, true, display?.state?.value == DisplayState.STARTED)
                    }
                }
            }
            try {
                current.start()
                withTimeout(12_000) {
                    current.state.first { it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED }
                    if (current.state.value != DeviceSessionState.STARTED) fail(FailureCode.UnsupportedDevice, "Glasses session stopped during startup")
                    state.value = DeviceReadiness(connected = true)
                    display = current.addDisplay().fold(
                        onSuccess = { it },
                        onFailure = { _, _ -> fail(FailureCode.DisplayUnavailable, "Display capability is unavailable") },
                    )
                    speech = current.addSpeech().fold(
                        onSuccess = { it },
                        onFailure = { _, _ -> fail(FailureCode.SpeechUnavailable, "Experimental speech capability is unavailable") },
                    )
                    // DAT Display starts asynchronously from an initial STOPPED state.
                    display!!.state.first { it == DisplayState.STARTED }
                    state.value = DeviceReadiness(true, true, true)
                    jobs += scope.launch(Dispatchers.Main.immediate) {
                        display?.state?.collect { value ->
                            if (epoch == generation) state.value = state.value.copy(displayReady = value == DisplayState.STARTED)
                        }
                    }
                }
            } catch (error: Throwable) {
                teardown()
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                val problem = (error as? AppException)?.failure
                    ?: failure(Stage.Setup, FailureCode.Timeout, "Glasses startup timed out")
                state.value = DeviceReadiness(failure = problem)
                throw AppException(problem, error)
            }
        }
    }

    override suspend fun close() = withContext(Dispatchers.Main.immediate) {
        lock.withLock { teardown(); state.value = DeviceReadiness() }
    }

    /** DAT retains its transcription StateFlow across stop/start; renew only Speech per turn. */
    internal suspend fun freshSpeech(): Speech = withContext(Dispatchers.Main.immediate) {
        lock.withLock {
            val current = session
            if (!state.value.ready || current == null || current.state.value != DeviceSessionState.STARTED) {
                throw AppException(failure(Stage.Speech, FailureCode.SpeechUnavailable, "Prepare the glasses first"))
            }
            speech?.stop()
            speech = null
            current.removeSpeech().onFailure { _, _ -> speechFailure("Unable to reset glasses speech") }
            current.addSpeech().fold(
                onSuccess = { fresh -> speech = fresh; fresh },
                onFailure = { _, _ -> speechFailure("Unable to attach glasses speech") },
            )
        }
    }

    private fun speechFailure(message: String): Nothing {
        val problem = failure(Stage.Speech, FailureCode.SpeechUnavailable, message)
        state.value = state.value.copy(speechReady = false, failure = problem)
        throw AppException(problem)
    }

    private fun teardown() {
        generation++
        jobs.forEach { it.cancel() }
        jobs.clear()
        speech?.stop()
        session?.removeSpeech()
        session?.removeDisplay()
        speech = null
        display = null
        session?.stop()
        session = null
    }

    private fun fail(code: FailureCode, message: String): Nothing {
        val stage = when (code) {
            FailureCode.SpeechUnavailable -> Stage.Speech
            FailureCode.DisplayUnavailable -> Stage.Display
            else -> Stage.Setup
        }
        val problem = failure(stage, code, message)
        state.value = DeviceReadiness(failure = problem)
        throw AppException(problem)
    }
}

internal fun failure(stage: Stage, code: FailureCode, message: String, turnId: Long? = null) =
    AppFailure(stage, code, true, message, turnId = turnId)
