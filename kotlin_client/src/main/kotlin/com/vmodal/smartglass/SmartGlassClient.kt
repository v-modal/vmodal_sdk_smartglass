package com.vmodal.smartglass

import android.content.Context
import com.vmodal.smartglass.bridge.JniCoreBridge
import com.vmodal.smartglass.codec.HardwareVideoPipeline
import com.vmodal.smartglass.dat.MetaDatSource
import com.vmodal.smartglass.session.SessionCoordinator
import java.io.Closeable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class SmartGlassClient private constructor(
    private val coordinator: SessionCoordinator,
) : Closeable {
    val state: StateFlow<SmartGlassState> = coordinator.state
    val events: Flow<SmartGlassEvent> = coordinator.events
    val metrics: StateFlow<StreamingMetrics> = coordinator.metrics

    suspend fun start() = coordinator.start()

    suspend fun stop(reason: StopReason = StopReason.USER) = coordinator.stop(reason)

    suspend fun onBackgrounded() = coordinator.onBackgrounded()

    suspend fun closeAndJoin() = coordinator.closeAndJoin()

    override fun close() = coordinator.close()

    companion object {
        fun create(
            context: Context,
            targetProvider: LiveStreamTargetProvider,
            config: SmartGlassConfig = SmartGlassConfig(),
            permissionRequester: DevicePermissionRequester? = null,
        ): SmartGlassClient {
            config.validate()
            val core = JniCoreBridge()
            val dat = MetaDatSource(context.applicationContext, permissionRequester)
            val codec = HardwareVideoPipeline(config.buffering.rawFrameCapacity)
            return SmartGlassClient(
                SessionCoordinator(config, targetProvider, dat, codec, core),
            )
        }
    }
}
