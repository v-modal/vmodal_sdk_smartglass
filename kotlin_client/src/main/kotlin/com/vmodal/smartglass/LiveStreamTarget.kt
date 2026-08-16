package com.vmodal.smartglass

import java.net.URI

data class LiveStreamTarget(
    val sessionId: String,
    val url: String,
    val streamId: String,
    val passphrase: String,
    val expiresAtEpochMs: Long? = null,
) {
    internal fun validate(nowMs: Long = System.currentTimeMillis()) {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "target session ID is invalid" }
        val uri = runCatching { URI(url) }.getOrNull()
        require(uri?.scheme == "srt" && !uri.host.isNullOrBlank() && uri.port > 0) {
            "target URL must be srt://host:port"
        }
        require(uri?.userInfo == null && uri?.query == null) {
            "target credentials and query must be separate"
        }
        require(streamId.isNotBlank() && streamId.length <= 512) { "target stream ID is invalid" }
        require(passphrase.length in 10..79) { "target passphrase length is invalid" }
        require(expiresAtEpochMs == null || expiresAtEpochMs > nowMs) { "target is expired" }
    }

    override fun toString(): String {
        val host = runCatching { URI(url).host }.getOrNull() ?: "<invalid>"
        return "LiveStreamTarget(sessionId=$sessionId, host=$host, expiresAtEpochMs=$expiresAtEpochMs, credentials=<redacted>)"
    }
}

fun interface LiveStreamTargetProvider {
    suspend fun acquire(reason: TargetReason): LiveStreamTarget
}

enum class TargetReason {
    INITIAL,
    EXPIRED,
    REJECTED,
}

fun interface DevicePermissionRequester {
    suspend fun requestCameraPermission(): Boolean
}
