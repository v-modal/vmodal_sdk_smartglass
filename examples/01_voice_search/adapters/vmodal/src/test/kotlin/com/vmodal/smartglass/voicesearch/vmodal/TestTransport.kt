package com.vmodal.smartglass.voicesearch.vmodal

import com.vmodal.sdk.*
import com.vmodal.smartglass.voicesearch.core.*
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Real published SDK request encoding/response decoding with an injectable native transport. */
internal class TestTransport : CancellableVmodalTransport {
    val requests = mutableListOf<VmodalRequest>()
    var searchBody = """{"data":[{"filename":"rank1.mp4","ts_unix":"0000000002000"}],"cnt_total":4}"""
    var groupsBody = """{"data":[{"mode":"vid_file","group_name":"videos","lancedb_versions":["v2","v7","bad"]}]}"""
    var bulkBody = """{"records":[{"input_index":0,"found":true,"url_pre_signed":"https://images.test/first"}]}"""
    var image = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1)
    var imageFailures = 0
    var imageStatus = 403
    var searchStatus = 200
    var holdSearch = false
    var holdImage = false
    var pending: (() -> Unit)? = null
    var cancelled = 0
    var downloads = 0

    override fun execute(request: VmodalRequest): VmodalResponse = response(request)

    override fun executeAsync(request: VmodalRequest, callback: VmodalTransportCallback): VmodalCancelHandle {
        requests += request
        val active = AtomicBoolean(true)
        val deliver = { if (active.compareAndSet(true, false)) callback.onSuccess(response(request)) }
        if ((holdSearch && request.path.endsWith("/search")) || (holdImage && request.path.endsWith("/get_image"))) {
            pending = deliver
        } else deliver()
        return VmodalCancelHandle {
            if (active.compareAndSet(true, false)) {
                cancelled++
                callback.onFailure(CancellationException("test cancellation"))
            }
        }
    }

    private fun response(request: VmodalRequest): VmodalResponse = when {
        request.path.endsWith("/me") -> VmodalResponse(200, """{"user_id":"account-a","tenant_id":"tenant-a"}""")
        request.path.endsWith("/groups") -> VmodalResponse(200, groupsBody)
        request.path.endsWith("/search") -> VmodalResponse(searchStatus, searchBody)
        request.path.endsWith("/get_url_bulk") -> VmodalResponse(200, bulkBody)
        request.path.endsWith("/get_image") -> {
            downloads++
            if (downloads <= imageFailures) VmodalResponse(imageStatus, """{"detail":"URL expired"}""")
            else VmodalResponse(200, bytes = image)
        }
        else -> error("Unexpected SDK request ${request.path}")
    }

    fun body(suffix: String): Map<*, *> = requests.last { it.path.endsWith(suffix) }.jsonBody as Map<*, *>
}

internal fun gateway(transport: TestTransport, maxBytes: Int = 8 * 1024 * 1024) =
    VmodalSessionGateway("https://gateway.test", maxBytes, 20_000) { transport }

internal suspend fun prepared(transport: TestTransport): Pair<VmodalSessionGateway, SearchQuery> {
    val gateway = gateway(transport)
    val scope = gateway.connect("test-api-key", 3, "videos", "default")
    return gateway to SearchQuery(8, 4, scope, "Find white shirt in my collection")
}

internal suspend fun expectFailure(code: FailureCode, block: suspend () -> Unit) {
    val error = runCatching { block() }.exceptionOrNull()
    check(error is AppException) { "Expected AppException, got $error" }
    check(error.failure.code == code) { "Expected $code, got ${error.failure.code}" }
}
