package com.vmodal.smartglass.bridge

import com.vmodal.smartglass.LiveStreamTarget
import com.vmodal.smartglass.SmartGlassConfig
import com.vmodal.smartglass.SmartGlassError
import com.vmodal.smartglass.StreamingMetrics
import java.nio.ByteBuffer

internal class JniCoreBridge : CoreBridge {
    init {
        try {
            System.loadLibrary("vmodal_smartglass_core")
        } catch (error: LinkageError) {
            throw SmartGlassError.NativeLibraryLoadFailed(error.javaClass.simpleName)
        }
    }

    override fun create(config: SmartGlassConfig): Long {
        val handle = nativeCreate(ContractJson.coreConfig(config))
        if (handle == 0L) throw SmartGlassError.CoreContractMismatch()
        return handle
    }

    override fun start(handle: Long, target: LiveStreamTarget) {
        checkStatus(nativeStart(handle, ContractJson.target(target)))
    }

    override fun pushVideo(handle: Long, sample: EncodedVideoSample): PushResult {
        require(sample.buffer.isDirect) { "encoded sample buffer must be direct" }
        val value = nativePushVideo(
            handle,
            sample.buffer,
            sample.offset,
            sample.length,
            sample.ptsUs,
            sample.flags,
        )
        if (value < 0) throw coreError(-value)
        return PushResult.entries.firstOrNull { it.value == value }
            ?: throw SmartGlassError.InternalInvariantViolation("UNKNOWN_PUSH_RESULT")
    }

    override fun updateTarget(handle: Long, target: LiveStreamTarget) {
        checkStatus(nativeUpdateTarget(handle, ContractJson.target(target)))
    }

    override fun pollEvent(handle: Long, timeoutMs: Int): NativeEvent? =
        nativePollEvent(handle, timeoutMs)?.let(ContractJson::event)

    override fun stats(handle: Long): StreamingMetrics = nativeStats(handle)
        ?.let(ContractJson::metrics)
        ?: throw SmartGlassError.InternalInvariantViolation("NATIVE_STATS_UNAVAILABLE")

    override fun stop(handle: Long, reasonCode: Int) {
        checkStatus(nativeStop(handle, reasonCode))
    }

    override fun destroy(handle: Long) = nativeDestroy(handle)

    private fun checkStatus(status: Int) {
        if (status < 0) throw coreError(-status)
    }

    private fun coreError(code: Int): SmartGlassError = when (code) {
        1001 -> SmartGlassError.CoreContractMismatch()
        1003 -> SmartGlassError.InvalidTarget()
        1004 -> SmartGlassError.TargetExpired()
        1010 -> SmartGlassError.TransportFailed()
        1011 -> SmartGlassError.TransportTimeout()
        1012 -> SmartGlassError.Cancelled()
        else -> SmartGlassError.InternalInvariantViolation("CORE_$code")
    }

    private external fun nativeCreate(config: ByteArray): Long
    private external fun nativeStart(handle: Long, target: ByteArray): Int
    private external fun nativePushVideo(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        length: Int,
        ptsUs: Long,
        flags: Int,
    ): Int
    private external fun nativeUpdateTarget(handle: Long, target: ByteArray): Int
    private external fun nativePollEvent(handle: Long, timeoutMs: Int): ByteArray?
    private external fun nativeStats(handle: Long): ByteArray?
    private external fun nativeStop(handle: Long, reasonCode: Int): Int
    private external fun nativeDestroy(handle: Long)
}
