package com.vmodal.smartglass.voicesearch

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: VoiceSearchViewModel by viewModels()
    private lateinit var phone: PhoneScreen
    private val bluetooth = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) registerGlasses() else model.reportPermission(false)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mic = registerForActivityResult(model.graph.meta.microphonePermissionContract(), model::reportPermission)
        phone = PhoneScreen(this)
        setContentView(phone)
        phone.authenticate.setOnClickListener { val key = phone.key.text.toString(); phone.key.text.clear(); model.authenticate(key) }
        phone.register.setOnClickListener { bluetooth.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)) }
        phone.microphone.setOnClickListener {
            try { model.graph.meta.initialize(); mic.launch(Unit) } catch (_: Exception) { model.reportSetupFailure() }
        }
        phone.connect.setOnClickListener {
            phone.collection.selectedItem?.toString()?.let { model.connect(it, phone.stream.text.toString()) }
        }
        phone.listen.setOnClickListener { model.graph.controller.listen() }
        phone.cancel.setOnClickListener { model.graph.controller.cancel() }
        phone.retry.setOnClickListener { model.graph.controller.retry() }
        phone.logout.setOnClickListener { model.logout() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.screen.collect(phone::render) }
                launch { model.collections.collect(phone::showCollections) }
                launch { model.setupMessage.collect { phone.setupStatus.text = it } }
            }
        }
    }
    private fun registerGlasses() {
        try { model.graph.meta.startRegistration(this) } catch (_: Exception) { model.reportSetupFailure() }
    }
    override fun onStop() {
        if (!isChangingConfigurations) model.background()
        super.onStop()
    }
    override fun onDestroy() { phone.release(); super.onDestroy() }
}
