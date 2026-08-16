package com.vmodal.smartglass.dat

import android.content.Context
import com.vmodal.smartglass.DevicePermissionRequester
import com.vmodal.smartglass.SmartGlassError
import com.vmodal.smartglass.VideoConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

internal class MetaDatSource(
    context: Context,
    permissionRequester: DevicePermissionRequester?,
) : DatSource {
    override val signals: Flow<DatSignal> = emptyFlow()

    override suspend fun open(video: VideoConfig) {
        throw SmartGlassError.DeviceUnavailable()
    }

    override suspend fun startCapture(onFrame: (DatFrame) -> Unit) = Unit
    override suspend fun stop() = Unit
}
