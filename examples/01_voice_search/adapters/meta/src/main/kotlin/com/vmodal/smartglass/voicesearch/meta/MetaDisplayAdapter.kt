package com.vmodal.smartglass.voicesearch.meta

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.meta.wearable.dat.display.views.Direction
import com.meta.wearable.dat.display.views.ImageSize
import com.meta.wearable.dat.display.views.TextStyle
import com.vmodal.smartglass.voicesearch.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MetaDisplayAdapter(private val gateway: MetaDeviceGateway) : DisplayPort {
    private val lock = Mutex()
    private val events = MutableSharedFlow<ScreenAction>(extraBufferCapacity = 16)
    override val actions = events.asSharedFlow()
    @Volatile private var activeScreen: ScreenModel? = null
    private var bitmap: Bitmap? = null

    override suspend fun render(screen: ScreenModel): DisplayResult = lock.withLock {
        withContext(Dispatchers.Default) {
            val display = gateway.display ?: return@withContext DisplayResult.Unavailable
            if (!gateway.readiness.value.displayReady) return@withContext DisplayResult.Unavailable
            val epoch = gateway.generation
            val image = screen.image
            val next = image?.let {
                if (it.width > 600 || it.height > 600 || it.bytes.size > 2 * 1024 * 1024) {
                    return@withContext DisplayResult.Failed(failure(Stage.Display, FailureCode.ImageTooLarge, "Preview exceeds display limits"))
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(it.bytes, 0, it.bytes.size, bounds)
                if (bounds.outWidth != it.width || bounds.outHeight != it.height) {
                    return@withContext DisplayResult.Failed(failure(Stage.Display, FailureCode.TopImageUnavailable, "Preview dimensions are invalid"))
                }
                BitmapFactory.decodeByteArray(it.bytes, 0, it.bytes.size)
                    ?: return@withContext DisplayResult.Failed(failure(Stage.Display, FailureCode.TopImageUnavailable, "Unable to decode preview"))
            }
            var accepted = false
            var committed = false
            val previousScreen = activeScreen
            // A native click may arrive as soon as the device sees content, before sendContent resumes.
            activeScreen = screen
            try {
                display.sendContent {
                    flexBox(direction = Direction.COLUMN, gap = 8, padding = 16) {
                        text(screen.status.take(120), style = TextStyle.BODY)
                        screen.transcript?.takeIf { it.isNotBlank() }?.let { text(it.take(160), style = TextStyle.META) }
                        next?.let { image(bitmap = it, sizePreset = ImageSize.FILL) }
                        screen.caption?.takeIf { it.isNotBlank() }?.let { text(it.take(120), style = TextStyle.META) }
                        screen.actions.forEach { action ->
                            button(label = actionLabel(action), onClick = {
                                if (activeScreen?.screenId == screen.screenId && epoch == gateway.generation) {
                                    events.tryEmit(ScreenAction(screen.screenId, screen.sessionGeneration, screen.query?.requestId, action))
                                }
                            })
                        }
                    }
                }.fold(onSuccess = { accepted = it }, onFailure = { _, _ -> accepted = false })
                if (!accepted || epoch != gateway.generation || !gateway.readiness.value.displayReady) {
                    return@withContext DisplayResult.Failed(failure(Stage.Display, FailureCode.DisplayUnavailable, "Glasses rejected the display submission"))
                }
                bitmap?.recycle()
                bitmap = next
                committed = true
                DisplayResult.Accepted
            } finally {
                if (!committed) activeScreen = if (epoch == gateway.generation) previousScreen else null
                if (bitmap !== next) next?.recycle()
            }
        }
    }

    override suspend fun clear() = lock.withLock {
        activeScreen = null
        try { gateway.display?.clearDisplay() } finally { bitmap?.recycle(); bitmap = null }
        Unit
    }
}

internal fun actionLabel(action: Action): String = when (action) {
    Action.Listen -> "New search"
    Action.Cancel -> "Cancel"
    Action.Retry -> "Retry"
    Action.Dismiss -> "Dismiss"
}
