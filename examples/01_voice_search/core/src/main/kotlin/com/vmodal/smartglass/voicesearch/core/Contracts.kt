package com.vmodal.smartglass.voicesearch.core

/** Immutable, authenticated collection snapshot. Credentials never cross this boundary. */
data class SessionScope(
    val sessionGeneration: Long,
    val accountRef: String,
    val mode: String,
    val groupName: String,
    val streamName: String,
    val versionLancedb: Long,
    val imageModality: String,
) {
    init {
        require(sessionGeneration >= 0 && versionLancedb >= 0)
        require(listOf(accountRef, mode, groupName, streamName, imageModality).all { it.isNotBlank() })
        require(mode == "vid_file" && imageModality == "vid_img") {
            "Only the validated vid_file/vid_img profile is supported"
        }
    }
}

data class VoiceTurn(val turnId: Long, val sessionGeneration: Long, val locale: String)

data class TranscriptEvent(
    val turnId: Long,
    val rawText: String,
    val isFinal: Boolean,
    val locale: String,
    val confidence: Double? = null,
)

data class SearchQuery(
    val requestId: Long,
    val turnId: Long,
    val scope: SessionScope,
    val text: String,
    val rawText: String = text,
) {
    init { require(text.isNotBlank() && text == normalizeTranscript(text)) }
}

fun normalizeTranscript(text: String): String = text.trim().replace(Regex("\\s+"), " ")

data class RankedImageRef(
    val query: SearchQuery,
    val filename: String,
    val streamName: String = query.scope.streamName,
    val timestamp: String? = null,
    val caption: String? = null,
    val score: Double? = null,
    val rank: Int = 1,
) {
    init { require(rank == 1 && filename.isNotBlank() && streamName.isNotBlank()) }
}

sealed interface SearchOutcome {
    data class Match(val ref: RankedImageRef) : SearchOutcome
    data object Empty : SearchOutcome
    data object TopHitUnresolvable : SearchOutcome
}

/** Buffers have one owner and must not be mutated by their recipient. Never persist source URLs. */
data class EncodedImage(val query: SearchQuery, val bytes: ByteArray, val mimeType: String) {
    init { require(bytes.isNotEmpty() && bytes.size <= 8 * 1024 * 1024) }
}

data class PreparedImage(
    val query: SearchQuery,
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val mimeType: String,
) {
    init {
        require(width > 0 && height > 0 && bytes.isNotEmpty())
        require(mimeType == "image/jpeg" || mimeType == "image/png")
    }
}

data class ImageLimits(
    val maxSide: Int = 600,
    val maxSourcePixels: Long = 24_000_000,
    val maxEncodedBytes: Int = 2 * 1024 * 1024,
    val maxDecodedBytes: Long = 1536 * 1024,
) {
    init { require(maxSide > 0 && maxSourcePixels > 0 && maxEncodedBytes > 0 && maxDecodedBytes > 0) }
}

enum class ScreenKind {
    SetupRequired, Ready, Listening, Finalizing, Searching, ResolvingImage,
    PreparingImage, SendingDisplay, ShowingImage, Empty, Error, Disconnected,
}

enum class Action { Listen, Cancel, Retry, Dismiss }

data class ScreenModel(
    val screenId: Long,
    val sessionGeneration: Long,
    val query: SearchQuery? = null,
    val kind: ScreenKind,
    val status: String,
    val transcript: String? = null,
    val image: PreparedImage? = null,
    val caption: String? = null,
    val actions: Set<Action> = emptySet(),
    val failure: AppFailure? = null,
)

data class ScreenAction(
    val screenId: Long,
    val sessionGeneration: Long,
    val requestId: Long? = null,
    val action: Action,
)

enum class Stage { Setup, Speech, Search, Image, Prepare, Display }
enum class FailureCode {
    NoSpeech, SpeechUnavailable, PermissionDenied, UnsupportedDevice, NotIndexed,
    AuthRequired, SearchFailed, TopImageUnavailable, ImageTooLarge, DisplayUnavailable, Timeout,
}

data class AppFailure(
    val stage: Stage,
    val code: FailureCode,
    val retryable: Boolean,
    val message: String,
    val requestId: Long? = null,
    val turnId: Long? = null,
)

class AppException(val failure: AppFailure, cause: Throwable? = null) : Exception(failure.message, cause)

data class DeviceReadiness(
    val connected: Boolean = false,
    val speechReady: Boolean = false,
    val displayReady: Boolean = false,
    val failure: AppFailure? = null,
) { val ready: Boolean get() = connected && speechReady && displayReady }

sealed interface DisplayResult {
    data object Accepted : DisplayResult
    data object Unavailable : DisplayResult
    data class Failed(val failure: AppFailure) : DisplayResult
}

data class Deadlines(val listeningMs: Long = 15_000, val requestMs: Long = 20_000, val displayMs: Long = 5_000) {
    init { require(listeningMs > 0 && requestMs > 0 && displayMs > 0) }
}
