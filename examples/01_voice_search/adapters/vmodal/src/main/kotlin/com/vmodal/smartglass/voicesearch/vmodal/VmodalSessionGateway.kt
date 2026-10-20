package com.vmodal.smartglass.voicesearch.vmodal

import com.vmodal.sdk.Client
import com.vmodal.sdk.MutableApiKeyProvider
import com.vmodal.sdk.OkHttpTransport
import com.vmodal.sdk.PUBLIC_GATEWAY_URL
import com.vmodal.sdk.SdkConfig
import com.vmodal.sdk.VmodalTransport
import com.vmodal.smartglass.voicesearch.core.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

data class CollectionChoice(val name: String, val latestVersion: Long?)

/**
 * Owns one authenticated SDK session. Identity and advertised versions follow the
 * sdk_android integration cookbook; only neutral records escape this module.
 * A new authentication/selection invalidates prior requests before network work starts.
 */
class VmodalSessionGateway internal constructor(
    private val baseUrl: String,
    internal val maxImageBytes: Int,
    private val requestTimeoutMs: Long,
    private val transportFactory: (SdkConfig) -> VmodalTransport,
) : AutoCloseable {
    constructor(
        baseUrl: String = PUBLIC_GATEWAY_URL,
        maxImageBytes: Int = 8 * 1024 * 1024,
        requestTimeoutMs: Long = 20_000,
    ) : this(baseUrl, maxImageBytes, requestTimeoutMs, { config ->
        OkHttpTransport(config, 8L * 1024 * 1024, 1024L * 1024, maxImageBytes.toLong())
    })

    init { require(maxImageBytes in 1..8 * 1024 * 1024 && requestTimeoutMs in 1..Int.MAX_VALUE.toLong()) }

    private val revision = AtomicLong()
    @Volatile private var session: Session? = null
    val search: SearchPort = VmodalSearchAdapter(this)
    val images: ImageSourcePort = VmodalImageAdapter(this)

    /** Authenticate and list collections visible to this credential; does not invent an index. */
    suspend fun authenticate(apiKey: String, sessionGeneration: Long): List<CollectionChoice> {
        require(sessionGeneration >= 0)
        val ticket = invalidate()
        var keys: MutableApiKeyProvider? = null
        try {
            return withTimeout(requestTimeoutMs) {
                keys = MutableApiKeyProvider(apiKey)
                val config = SdkConfig(baseUrl = baseUrl, apiKeyProvider = keys, maxRetries = 0,
                    timeoutMillis = requestTimeoutMs.toInt())
                val profile = client(config).coroutines().auth.me()
                val account = profile.userId?.trim().orEmpty()
                if (account.isBlank()) throw failure(Stage.Setup, FailureCode.AuthRequired, false, "Sign in again.")
                val configured = config.copy(userId = account, tenantId = profile.tenantId.orEmpty(), email = profile.email.orEmpty())
                val sdk = client(configured)
                val choices = sdk.coroutines().collections.listGroups("vid_file").data
                    .filter { it.mode == "vid_file" && it.groupName.isNotBlank() }
                    .map { CollectionChoice(it.groupName.trim(), it.latestLancedbVersion?.toLong()) }
                    .distinctBy(CollectionChoice::name)
                synchronized(this@VmodalSessionGateway) {
                    if (revision.get() != ticket) throw CancellationException("Session changed")
                    session = Session(sdk, requireNotNull(keys), sessionGeneration, account, choices)
                }
                choices
            }
        } catch (error: Exception) {
            keys?.clear()
            synchronized(this) {
                if (session?.keys === keys) session = null
            }
            throw mappedFailure(error, Stage.Setup)
        }
    }

    /** Freeze the selected visible collection and its exact advertised index version. */
    @Synchronized fun selectCollection(groupName: String, streamName: String): SessionScope {
        val current = session ?: throw failure(Stage.Setup, FailureCode.AuthRequired, false, "Sign in first.")
        require(streamName.isNotBlank())
        val choice = current.collections.firstOrNull { it.name == groupName.trim() }
            ?: throw failure(Stage.Setup, FailureCode.AuthRequired, false, "Choose a visible collection.")
        val version = choice.latestVersion
            ?: throw failure(Stage.Setup, FailureCode.NotIndexed, false, "This collection has no searchable index.")
        val scope = SessionScope(current.generation, current.account, "vid_file", choice.name,
            streamName.trim(), version, "vid_img")
        session = current.copy(scope = scope)
        return scope
    }

    suspend fun connect(apiKey: String, sessionGeneration: Long, groupName: String, streamName: String): SessionScope {
        val choices = authenticate(apiKey, sessionGeneration)
        return synchronized(this) {
            if (session?.collections !== choices) throw CancellationException("Session changed")
            selectCollection(groupName, streamName)
        }
    }

    internal suspend fun <T> use(scope: SessionScope, stage: Stage, query: SearchQuery, block: suspend (Client) -> T): T {
        val current = session ?: throw failure(stage, FailureCode.AuthRequired, false, "Sign in again.", query)
        if (current.scope != scope) throw CancellationException("Collection session changed")
        try {
            return withTimeout(requestTimeoutMs) {
                val result = block(current.client)
                if (session !== current) throw CancellationException("Collection session changed")
                result
            }
        } catch (error: Exception) {
            if (session !== current) throw CancellationException("Collection session changed")
            throw mappedFailure(error, stage, query)
        }
    }

    private fun client(config: SdkConfig): Client = Client(config, transportFactory(config))

    @Synchronized private fun invalidate(): Long {
        val ticket = revision.incrementAndGet()
        session?.keys?.clear()
        session = null
        return ticket
    }

    override fun close() { invalidate() }

    private data class Session(
        val client: Client,
        val keys: MutableApiKeyProvider,
        val generation: Long,
        val account: String,
        val collections: List<CollectionChoice>,
        val scope: SessionScope? = null,
    )
}
