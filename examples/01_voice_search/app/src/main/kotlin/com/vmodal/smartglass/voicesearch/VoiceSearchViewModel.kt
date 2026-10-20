package com.vmodal.smartglass.voicesearch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vmodal.smartglass.voicesearch.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class VoiceSearchViewModel(application: Application) : AndroidViewModel(application) {
    private val graphScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val graph = AppGraph(application, graphScope)
    val screen = graph.controller.state
    private val choices = MutableStateFlow<List<String>>(emptyList())
    val collections = choices.asStateFlow()
    private val message = MutableStateFlow("Authenticate and register display glasses to begin")
    val setupMessage = message.asStateFlow()
    private var generation = 0L
    private var setup: Job? = null
    private var cleanup: Job? = null

    fun authenticate(key: String) {
        setup?.cancel()
        val epoch = ++generation
        choices.value = emptyList()
        setup = viewModelScope.launch {
            cleanup?.join()
            graph.logout()
            runSetup {
                val result = graph.vmodal.authenticate(key, epoch)
                if (epoch == generation) {
                    choices.value = result.map { it.name }
                    message.value = if (result.isEmpty()) "No visible video collections" else "Select a collection and stream, then Connect"
                }
            }
        }
    }

    fun connect(collection: String, stream: String) {
        setup?.cancel()
        setup = viewModelScope.launch {
            cleanup?.join()
            runSetup {
                graph.controller.setup(graph.vmodal.selectCollection(collection, stream.trim()))
                message.value = "Glasses preparation requested"
            }
        }
    }

    fun background() {
        setup?.cancel()
        cleanup = viewModelScope.launch { graph.controller.background() }
    }

    fun logout() {
        ++generation
        setup?.cancel()
        choices.value = emptyList()
        cleanup = viewModelScope.launch {
            graph.logout()
            message.value = "Signed out"
        }
    }

    fun reportPermission(granted: Boolean) {
        message.value = if (granted) "Glasses microphone allowed; select Connect" else "Glasses microphone permission denied"
    }

    fun reportSetupFailure() { message.value = "Unable to register Meta glasses; check Meta AI setup" }

    private suspend fun runSetup(block: suspend () -> Unit) {
        try { block() }
        catch (error: CancellationException) { throw error }
        catch (error: AppException) { message.value = error.failure.message }
        catch (_: Exception) { message.value = "Setup failed; check account, collection, and glasses" }
    }

    override fun onCleared() {
        // viewModelScope is cancelled before this callback; cleanup needs its own bounded owner.
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        owner.launch {
            try { withTimeout(15_000) { graph.close() } }
            finally { graphScope.cancel(); owner.cancel() }
        }
    }
}
