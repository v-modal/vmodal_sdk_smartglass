package com.vmodal.smartglass

sealed class SmartGlassError(
    val code: String,
    message: String,
    val recoverable: Boolean,
    val safeCauseCode: String? = null,
) : RuntimeException(message) {
    class RegistrationRequired : SmartGlassError("REGISTRATION_REQUIRED", "Meta registration is required", true)
    class PermissionDenied : SmartGlassError("PERMISSION_DENIED", "Camera permission was denied", true)
    class DeviceUnavailable : SmartGlassError("DEVICE_UNAVAILABLE", "No compatible glasses are available", true)
    class DeviceSessionFailed(causeCode: String? = null) : SmartGlassError("DEVICE_SESSION_FAILED", "The glasses session failed", true, causeCode)
    class UnsupportedInputFormat : SmartGlassError("UNSUPPORTED_INPUT_FORMAT", "The DAT frame format is unsupported", false)
    class HardwareCodecUnavailable : SmartGlassError("HARDWARE_CODEC_UNAVAILABLE", "No compatible hardware H.264 encoder is available", false)
    class CodecConfigurationFailed(causeCode: String? = null) : SmartGlassError("CODEC_CONFIGURATION_FAILED", "Hardware codec configuration failed", false, causeCode)
    class CodecRuntimeFailed(causeCode: String? = null) : SmartGlassError("CODEC_RUNTIME_FAILED", "The hardware codec failed", false, causeCode)
    class NativeLibraryLoadFailed(causeCode: String? = null) : SmartGlassError("NATIVE_LIBRARY_LOAD_FAILED", "The native streaming core could not be loaded", false, causeCode)
    class CoreContractMismatch : SmartGlassError("CORE_CONTRACT_MISMATCH", "The native contract version is unsupported", false)
    class InvalidTarget : SmartGlassError("INVALID_TARGET", "The live target is invalid", false)
    class TargetExpired : SmartGlassError("TARGET_EXPIRED", "The live target is expired", true)
    class TargetRejected : SmartGlassError("TARGET_REJECTED", "The live target was rejected", true)
    class TransportFailed : SmartGlassError("TRANSPORT_FAILED", "The live transport failed", true)
    class TransportTimeout : SmartGlassError("TRANSPORT_TIMEOUT", "The live transport timed out", false)
    class ThermalLimit : SmartGlassError("THERMAL_LIMIT", "The device thermal limit was reached", false)
    class Cancelled : SmartGlassError("CANCELLED", "The streaming session was cancelled", true)
    class InternalInvariantViolation(causeCode: String? = null) : SmartGlassError("INTERNAL_INVARIANT", "An internal invariant failed", false, causeCode)
}
