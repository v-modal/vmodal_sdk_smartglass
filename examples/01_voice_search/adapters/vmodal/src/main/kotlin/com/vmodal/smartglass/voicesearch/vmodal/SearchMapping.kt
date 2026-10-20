package com.vmodal.smartglass.voicesearch.vmodal

/**
 * Adapted from sdk_android examples/03_fullapp FullAppViewModel searchCandidates,
 * candidateFileName, strTimestamp13 and filename helpers, and 01_starter CookbookWorkflow.
 * Keep the full alias/timestamp dependency chain. A display title alone is deliberately
 * insufficient evidence of an image filename in this strict rank-one consumer.
 */
internal data class ImageCoordinates(
    val filename: String,
    val stream: String,
    val timestamp: String?,
    val caption: String?,
    val score: Double?,
)

internal fun imageCoordinates(value: Any?, fallbackStream: String): ImageCoordinates? {
    val row = (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: return null
    val alias = strFirst(row, "filename", "filename_sanitized", "video_filename", "video", "source_path", "path")
    val filename = strFilename(alias.ifBlank { strItemFilename(row) })
    if (filename.isBlank()) return null
    return ImageCoordinates(
        filename,
        strFirst(row, "stream", "stream_name").ifBlank { fallbackStream },
        strTimestamp13(strFirst(row, "ts_unix_13digits", "ts_unix", "timestamp_ms")).ifBlank { null },
        strFirst(row, "effective_title", "title", "text", "caption", "description", "text_agg_tok").ifBlank { null },
        listOf("score_ui", "score", "similarity", "image_score", "text_score")
            .firstNotNullOfOrNull { name -> (row[name] as? Number)?.toDouble()?.takeIf(Double::isFinite) },
    )
}

private fun strItemFilename(row: Map<String, Any?>): String {
    val id = strFirst(row, "item_id")
    val stream = strFirst(row, "stream", "stream_name")
    val unix = strFirst(row, "ts_unix", "ts_unix_13digits", "timestamp_ms")
    if (id.isBlank() || stream.isBlank() || unix.isBlank()) return ""
    // Require the proven complete frame identifier, rather than treating an arbitrary ID as a file.
    if (!id.startsWith("$stream-") || !id.endsWith("-$unix")) return ""
    return id.removePrefix("$stream-").removeSuffix("-$unix").trim()
}

internal fun strFilename(value: String): String = value.trim().replace('\\', '/').substringAfterLast('/').trim()

internal fun strTimestamp13(value: String): String {
    val digits = value.filter(Char::isDigit)
    return when {
        digits.length >= 13 -> digits.take(13)
        digits.length == 10 -> "${digits}000"
        digits.isNotBlank() -> digits.padStart(13, '0')
        else -> ""
    }
}

private fun strFirst(row: Map<String, Any?>, vararg names: String): String =
    names.firstNotNullOfOrNull { row[it]?.toString()?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()

/** Exact integer join: fractional/overflowing/nonfinite values must never alias input zero. */
internal fun intInputIndex(value: Any?): Int? = when (value) {
    is Number -> value.toDouble().takeIf { it.isFinite() && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }
        ?.let { number -> number.toInt().takeIf { it.toDouble() == number } }
    is String -> value.trim().toIntOrNull()
    else -> null
}
