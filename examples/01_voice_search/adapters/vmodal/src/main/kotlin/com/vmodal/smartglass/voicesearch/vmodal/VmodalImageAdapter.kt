package com.vmodal.smartglass.voicesearch.vmodal

import com.vmodal.sdk.ApiError
import com.vmodal.sdk.ImageUrlRecord
import com.vmodal.smartglass.voicesearch.core.*

/** Temporary URLs never leave this adapter. Refresh only the same hit, at most once. */
class VmodalImageAdapter(private val gateway: VmodalSessionGateway) : ImageSourcePort {
    override suspend fun load(ref: RankedImageRef): EncodedImage = gateway.use(ref.query.scope, Stage.Image, ref.query) { client ->
        val scope = ref.query.scope
        val record = ImageUrlRecord(scope.mode, scope.groupName, scope.imageModality,
            ref.streamName, ref.filename, ref.timestamp).toMap()
        val images = client.coroutines().images
        repeat(2) { attempt ->
            val records = images.getUrlBulk(listOf(record)).records
            if (records.size != 1 || intInputIndex(records.single()["input_index"]) != 0) {
                throw unavailable(ref)
            }
            val row = records.single()
            val url = (row["url_pre_signed"] as? String)?.trim().orEmpty()
            if (row["found"] == false || url.isBlank()) throw unavailable(ref)
            val bytes = try {
                images.getImageFromUrl(url)
            } catch (error: ApiError) {
                if (attempt == 0 && error.statusCode in setOf(403, 404, 410)) return@repeat
                throw error
            }
            if (bytes.size > gateway.maxImageBytes) {
                throw failure(Stage.Image, FailureCode.ImageTooLarge, false, "The result image is too large.", ref.query)
            }
            val mime = strImageMime(bytes) ?: throw unavailable(ref)
            return@use EncodedImage(ref.query, bytes, mime)
        }
        throw unavailable(ref)
    }

    private fun unavailable(ref: RankedImageRef) = failure(Stage.Image, FailureCode.TopImageUnavailable,
        true, "The first result image is unavailable.", ref.query)
}

internal fun strImageMime(bytes: ByteArray): String? = when {
    bytes.size >= 8 && bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte) -> "image/png"
    bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
    bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
        bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "image/webp"
    else -> null
}
