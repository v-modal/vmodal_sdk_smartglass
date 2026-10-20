package com.vmodal.smartglass.voicesearch.image

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vmodal.smartglass.voicesearch.core.*
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidImagePreparerTest {
    private val query = SearchQuery(1, 1, SessionScope(1, "user", "vid_file", "shirts", "default", 1, "vid_img"), "white shirt")
    private fun fixture(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        return try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray() }
        finally { bitmap.recycle() }
    }
    @Test fun portraitAndLandscapeFitWithoutCropping() = runBlocking {
        for ((w, h) in listOf(1200 to 600, 600 to 1200)) {
            val result = AndroidImagePreparer().prepare(EncodedImage(query, fixture(w, h), "image/jpeg"), ImageLimits())
            assertEquals(w.toDouble() / h, result.width.toDouble() / result.height, 0.01)
            assertTrue(result.width <= 600 && result.height <= 600)
            assertTrue(result.width.toLong() * result.height * 4 <= 1536 * 1024)
            assertTrue(result.bytes.size <= 2 * 1024 * 1024)
            assertEquals(query, result.query)
        }
    }
    @Test fun exifRotationIsApplied() = runBlocking {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "orientation.jpg")
        try {
            file.writeBytes(fixture(200, 100))
            ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "6"); saveAttributes() }
            val result = AndroidImagePreparer().prepare(EncodedImage(query, file.readBytes(), "image/jpeg"), ImageLimits())
            assertEquals(100, result.width)
            assertEquals(200, result.height)
        } finally { file.delete() }
    }
    @Test fun corruptOversizeAndOutputLimitAreRejected() = runBlocking {
        for ((bytes, limits, code) in listOf(
            Triple(byteArrayOf(1, 2, 3), ImageLimits(), FailureCode.TopImageUnavailable),
            Triple(fixture(200, 100), ImageLimits(maxSourcePixels = 100), FailureCode.ImageTooLarge),
            Triple(fixture(200, 100), ImageLimits(maxEncodedBytes = 1), FailureCode.ImageTooLarge),
        )) {
            val error = runCatching { AndroidImagePreparer().prepare(EncodedImage(query, bytes, "image/jpeg"), limits) }.exceptionOrNull()
            assertTrue(error is AppException)
            assertEquals(code, (error as AppException).failure.code)
        }
    }
    @Test fun cancellationPropagates() = runBlocking {
        val bytes = fixture(2400, 2400)
        val entered = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            entered.complete(Unit)
            AndroidImagePreparer().prepare(EncodedImage(query, bytes, "image/jpeg"), ImageLimits())
            fail("Cancelled work completed")
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }
}
