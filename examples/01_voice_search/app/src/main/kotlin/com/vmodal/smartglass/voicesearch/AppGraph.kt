package com.vmodal.smartglass.voicesearch

import android.content.Context
import com.vmodal.smartglass.voicesearch.core.*
import com.vmodal.smartglass.voicesearch.image.AndroidImagePreparer
import com.vmodal.smartglass.voicesearch.meta.*
import com.vmodal.smartglass.voicesearch.vmodal.VmodalSessionGateway
import kotlinx.coroutines.CoroutineScope

/** The sole composition root. SDK and Meta sessions have the same foreground lifetime. */
class AppGraph(context: Context, scope: CoroutineScope, val vmodal: VmodalSessionGateway = VmodalSessionGateway()) {
    val meta = MetaDeviceGateway(context, scope)
    val controller = VoiceSearchController(scope, meta, MetaSpeechAdapter(meta), vmodal.search,
        vmodal.images, AndroidImagePreparer(), MetaDisplayAdapter(meta))
    suspend fun logout() { try { controller.reset() } finally { vmodal.close() } }
    suspend fun close() { try { controller.close() } finally { vmodal.close() } }
}
