package com.vmodal.smartglass.voicesearch.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.vmodal.smartglass.voicesearch.core.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.sqrt

/** Inspect before decoding, sample before allocation, and fit the entire image without cropping. */
class AndroidImagePreparer : ImagePreparePort {
    override suspend fun prepare(encoded: EncodedImage, limits: ImageLimits): PreparedImage = withContext(Dispatchers.Default) {
        fun fail(code: FailureCode, message: String): Nothing = throw AppException(
            AppFailure(Stage.Prepare, code, true, message, encoded.query.requestId),
        )
        currentCoroutineContext().ensureActive()
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded.bytes, 0, encoded.bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0 || opts.outMimeType !in setOf("image/jpeg", "image/png")) {
            fail(FailureCode.TopImageUnavailable, "The first image is corrupt or unsupported")
        }
        if (opts.outWidth.toLong() * opts.outHeight > limits.maxSourcePixels) {
            fail(FailureCode.ImageTooLarge, "The first image exceeds the source pixel limit")
        }
        val side = min(limits.maxSide, sqrt(limits.maxDecodedBytes / 4.0).toInt())
        if (side < 1) fail(FailureCode.ImageTooLarge, "The preview allocation limit is too small")
        var sample = 1
        // Bound the sampled intermediate allocation to at most four preview allocations.
        while (opts.outWidth.toLong() * opts.outHeight / sample / sample * 4 > limits.maxDecodedBytes * 4 ||
            opts.outWidth / sample > side * 2 || opts.outHeight / sample > side * 2) sample *= 2
        opts.inJustDecodeBounds = false
        opts.inSampleSize = sample
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888
        var bitmap: Bitmap? = null
        try {
            bitmap = BitmapFactory.decodeByteArray(encoded.bytes, 0, encoded.bytes.size, opts)
                ?: fail(FailureCode.TopImageUnavailable, "The first image could not be decoded")
            if (bitmap.allocationByteCount.toLong() > limits.maxDecodedBytes * 4) {
                fail(FailureCode.ImageTooLarge, "Sampled image exceeds the working allocation limit")
            }
            currentCoroutineContext().ensureActive()
            val exif = ExifInterface(ByteArrayInputStream(encoded.bytes))
            val matrix = Matrix().apply {
                if (exif.isFlipped) postScale(-1f, 1f)
                postRotate(exif.rotationDegrees.toFloat())
            }
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) bitmap.recycle()
                bitmap = rotated
            }
            val scale = min(1.0, min(side.toDouble() / bitmap.width, side.toDouble() / bitmap.height))
            val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val fitted = Bitmap.createScaledBitmap(bitmap, width, height, true)
            if (fitted !== bitmap) bitmap.recycle()
            bitmap = fitted
            currentCoroutineContext().ensureActive()
            if (bitmap.allocationByteCount > limits.maxDecodedBytes) fail(FailureCode.ImageTooLarge, "Preview exceeds allocation limit")
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) {
                fail(FailureCode.TopImageUnavailable, "Preview encoding failed")
            }
            if (output.size() > limits.maxEncodedBytes) fail(FailureCode.ImageTooLarge, "Preview exceeds encoded byte limit")
            currentCoroutineContext().ensureActive()
            PreparedImage(encoded.query, output.toByteArray(), width, height, "image/jpeg")
        } finally {
            bitmap?.recycle()
        }
    }
}
