package com.vmodal.smartglass.bridge

import com.vmodal.smartglass.LiveStreamTarget
import com.vmodal.smartglass.SmartGlassConfig
import com.vmodal.smartglass.StreamingMetrics
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object ContractJson {
    const val VERSION = 1
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun coreConfig(value: SmartGlassConfig): ByteArray {
        val rec = CoreConfigRecord(
            encodedQueueMaxBytes = value.buffering.encodedQueueMaxBytes,
            encodedQueueMaxDurationMs = value.buffering.encodedQueueMaxDurationMs,
            eventQueueCapacity = value.buffering.eventQueueCapacity,
            srtLatencyMs = value.reconnect.srtLatencyMs,
            srtPeerLatencyMs = value.reconnect.srtPeerLatencyMs,
            reconnectDelaysMs = value.reconnect.delaysMs,
            reconnectJitterPercent = value.reconnect.jitterPercent,
            disconnectTimeoutMs = value.disconnectTimeoutMs,
            maxSampleBytes = value.buffering.maxEncodedSampleBytes,
            blockPoolCapacity = value.buffering.blockPoolCapacity,
        )
        return json.encodeToString(CoreConfigRecord.serializer(), rec).encodeToByteArray()
    }

    fun target(value: LiveStreamTarget): ByteArray = json.encodeToString(
        TargetRecord.serializer(),
        TargetRecord(
            sessionId = value.sessionId,
            url = value.url,
            streamId = value.streamId,
            passphrase = value.passphrase,
            expiresAtEpochMs = value.expiresAtEpochMs,
        ),
    ).encodeToByteArray()

    fun event(value: ByteArray): NativeEvent {
        val rec = json.decodeFromString(NativeEventRecord.serializer(), value.decodeToString())
        require(rec.contractVersion == VERSION) { "native event contract mismatch" }
        return NativeEvent(
            kind = NativeEventKind.valueOf(rec.kind),
            terminal = rec.terminal,
            code = rec.code,
            value = rec.value,
            message = rec.message,
        )
    }

    fun metrics(value: ByteArray): StreamingMetrics {
        val rec = json.decodeFromString(NativeMetricsRecord.serializer(), value.decodeToString())
        require(rec.contractVersion == VERSION) { "native metrics contract mismatch" }
        return StreamingMetrics(
            acceptedSamples = rec.acceptedSamples,
            droppedSamples = rec.droppedSamples,
            keyframeRequests = rec.keyframeRequests,
            sentBytes = rec.sentBytes,
            reconnects = rec.reconnects,
            rttMs = rec.rttMs,
            retransmittedPackets = rec.retransmittedPackets,
            queueBytes = rec.queueBytes,
            queueDepth = rec.queueDepth,
            uptimeMs = rec.uptimeMs,
        )
    }
}

@Serializable
private data class CoreConfigRecord(
    val contractVersion: Int = ContractJson.VERSION,
    val encodedQueueMaxBytes: Int,
    val encodedQueueMaxDurationMs: Long,
    val eventQueueCapacity: Int,
    val srtLatencyMs: Long,
    val srtPeerLatencyMs: Long,
    val reconnectDelaysMs: List<Long>,
    val reconnectJitterPercent: Int,
    val disconnectTimeoutMs: Long,
    val mpegTsProgramNumber: Int = 1,
    val videoPid: Int = 0x0100,
    val pmtPid: Int = 0x1000,
    val tableIntervalMs: Long = 500,
    val maxSampleBytes: Int,
    val blockPoolCapacity: Int,
)

@Serializable
private data class TargetRecord(
    val contractVersion: Int = ContractJson.VERSION,
    val sessionId: String,
    val url: String,
    val streamId: String,
    val passphrase: String,
    val expiresAtEpochMs: Long? = null,
)

@Serializable
private data class NativeEventRecord(
    val contractVersion: Int,
    val kind: String,
    val terminal: Boolean,
    val code: Int,
    val value: Long,
    val message: String,
)

@Serializable
private data class NativeMetricsRecord(
    val contractVersion: Int,
    val acceptedSamples: Long = 0,
    val droppedSamples: Long = 0,
    val keyframeRequests: Long = 0,
    val sentBytes: Long = 0,
    val reconnects: Long = 0,
    val rttMs: Long = 0,
    val retransmittedPackets: Long = 0,
    val queueBytes: Long = 0,
    val queueDepth: Long = 0,
    val uptimeMs: Long = 0,
)
