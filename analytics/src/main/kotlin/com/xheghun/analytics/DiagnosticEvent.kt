package com.xheghun.analytics

const val DIAGNOSTIC_SCHEMA_VERSION: Int = 2

enum class EventType {
    SESSION_START,
    SESSION_END,
    RENDER_FIRST_FRAME,
    REBUFFER_START,
    REBUFFER_END,
    TRACK_SWITCH,
    DECODER_INIT,
    DROPPED_FRAMES,
    LOAD_ERROR,
    DRM_KEY_STATUS,
    DRM_SESSION_EVENT,
    DRM_REQUEST,
    DRM_EXPIRATION_UPDATE,
    BANDWIDTH_SAMPLE,
    PLAYBACK_ERROR,
}

enum class PlayerState { IDLE, BUFFERING, READY, ENDED }

enum class DrmScheme { WIDEVINE, FAIRPLAY, PLAYREADY, OTHER }

enum class TrackType { VIDEO, AUDIO, TEXT }

enum class TrackSwitchReason { BANDWIDTH_INCREASE, BANDWIDTH_DECREASE, BUFFER_HEALTH, MANUAL_OVERRIDE, INITIAL, UNKNOWN }

enum class LoadErrorClass { TIMEOUT, HTTP_4XX, HTTP_5XX, DNS, CONNECTION, UNKNOWN }

enum class SessionEndReason { USER_STOPPED, APP_BACKGROUNDED, PLAYBACK_ENDED, REPLACED, ERROR, RELEASED }

enum class DrmKeyState { USABLE, EXPIRED, OUTPUT_RESTRICTED, STATUS_PENDING, INTERNAL_ERROR, UNKNOWN }

enum class DrmSessionEventType { ACQUIRED, KEYS_LOADED, KEYS_RESTORED, KEYS_REMOVED, RELEASED, ERROR }

enum class DrmSessionState { RELEASED, ERROR, OPENING, OPENED, OPENED_WITH_KEYS, UNKNOWN }

enum class DrmRequestKind { LICENSE, PROVISIONING }

enum class DrmLicenseRequestType { INITIAL, RENEWAL, RELEASE, UPDATE, NONE, UNKNOWN }

enum class CodecImplementationType { HARDWARE_ACCELERATED, SOFTWARE_ONLY, UNKNOWN }

enum class CodecClassificationSource { PLATFORM, NAME_HEURISTIC, UNKNOWN }

enum class CodecFormatSupport { SUPPORTED, UNSUPPORTED, UNKNOWN }

data class DiagnosticEventMetadata(
    val sessionId: String,
    val eventId: String,
    val timestampMs: Long,
    val elapsedRealtimeMs: Long,
    val playerState: PlayerState? = null,
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(eventId.isNotBlank()) { "eventId must not be blank" }
        require(timestampMs >= 0) { "timestampMs must be non-negative" }
        require(elapsedRealtimeMs >= 0) { "elapsedRealtimeMs must be non-negative" }
    }
}

data class FormatSnapshot(
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Int,
    val mimeType: String? = null,
    val codecs: String? = null,
) {
    init {
        require(bitrate >= 0) { "bitrate must be non-negative" }
        require(width == null || width >= 0) { "width must be non-negative" }
        require(height == null || height >= 0) { "height must be non-negative" }
    }
}

data class IntRangeSnapshot(
    val lower: Int,
    val upper: Int,
) {
    init {
        require(lower <= upper) { "lower must not exceed upper" }
    }
}

data class DoubleRangeSnapshot(
    val lower: Double,
    val upper: Double,
) {
    init {
        require(lower.isFinite() && upper.isFinite()) { "range values must be finite" }
        require(lower <= upper) { "lower must not exceed upper" }
    }
}

data class CodecProfileLevelSnapshot(
    val profile: Int,
    val level: Int,
)

data class VideoCodecCapabilitiesSnapshot(
    val supportedWidths: IntRangeSnapshot,
    val supportedHeights: IntRangeSnapshot,
    val supportedBitratesBps: IntRangeSnapshot,
    val supportedFrameRates: DoubleRangeSnapshot,
    val widthAlignment: Int,
    val heightAlignment: Int,
) {
    init {
        require(widthAlignment > 0) { "widthAlignment must be greater than zero" }
        require(heightAlignment > 0) { "heightAlignment must be greater than zero" }
    }
}

data class DecoderCapabilitySnapshot(
    val canonicalName: String? = null,
    val implementationType: CodecImplementationType,
    val classificationSource: CodecClassificationSource,
    val isVendor: Boolean? = null,
    val isAlias: Boolean? = null,
    val profileLevels: List<CodecProfileLevelSnapshot> = emptyList(),
    val supportsAdaptivePlayback: Boolean,
    val supportsSecurePlayback: Boolean,
    val supportsTunneledPlayback: Boolean,
    val maxSupportedInstances: Int? = null,
    val selectedFormatSupport: CodecFormatSupport = CodecFormatSupport.UNKNOWN,
    val videoCapabilities: VideoCodecCapabilitiesSnapshot? = null,
) {
    init {
        require(maxSupportedInstances == null || maxSupportedInstances > 0) {
            "maxSupportedInstances must be greater than zero"
        }
    }
}

data class DecoderInspectionRequest(
    val decoderName: String,
    val mimeType: String,
    val format: FormatSnapshot? = null,
) {
    init {
        require(decoderName.isNotBlank()) { "decoderName must not be blank" }
        require(mimeType.isNotBlank()) { "mimeType must not be blank" }
    }
}

fun interface DecoderCapabilityResolver {
    fun resolve(request: DecoderInspectionRequest): DecoderCapabilitySnapshot?
}

sealed interface DiagnosticEvent {
    val metadata: DiagnosticEventMetadata
    val type: EventType

    data class SessionStart(
        override val metadata: DiagnosticEventMetadata,
        val mediaUri: String,
        val drmScheme: DrmScheme? = null,
        val deviceModel: String = "unknown",
        val osVersion: String = "unknown",
        val appVersion: String = "unknown",
    ) : DiagnosticEvent {
        override val type = EventType.SESSION_START
    }

    data class SessionEnd(
        override val metadata: DiagnosticEventMetadata,
        val durationMs: Long,
        val reason: SessionEndReason,
    ) : DiagnosticEvent {
        override val type = EventType.SESSION_END
    }

    data class RenderFirstFrame(
        override val metadata: DiagnosticEventMetadata,
        val elapsedSincePrepareMs: Long,
    ) : DiagnosticEvent {
        override val type = EventType.RENDER_FIRST_FRAME
    }

    data class RebufferStart(
        override val metadata: DiagnosticEventMetadata,
        val bufferedMsAtStart: Long,
    ) : DiagnosticEvent {
        override val type = EventType.REBUFFER_START
    }

    data class RebufferEnd(
        override val metadata: DiagnosticEventMetadata,
        val durationMs: Long,
    ) : DiagnosticEvent {
        override val type = EventType.REBUFFER_END
    }

    data class TrackSwitch(
        override val metadata: DiagnosticEventMetadata,
        val fromFormat: FormatSnapshot?,
        val toFormat: FormatSnapshot,
        val reason: TrackSwitchReason,
        val estimatedBandwidthBps: Long,
        val bufferedDurationMs: Long,
        val availableVideoFormats: List<FormatSnapshot> = emptyList(),
    ) : DiagnosticEvent {
        override val type = EventType.TRACK_SWITCH
    }

    data class DecoderInit(
        override val metadata: DiagnosticEventMetadata,
        val decoderName: String,
        val mimeType: String,
        val trackType: TrackType,
        val initializationDurationMs: Long,
        val isHardwareAccelerated: Boolean?,
        val capabilities: DecoderCapabilitySnapshot? = null,
    ) : DiagnosticEvent {
        override val type = EventType.DECODER_INIT
    }

    data class DroppedFrames(
        override val metadata: DiagnosticEventMetadata,
        val count: Int,
        val elapsedMs: Long,
    ) : DiagnosticEvent {
        override val type = EventType.DROPPED_FRAMES
    }

    data class LoadError(
        override val metadata: DiagnosticEventMetadata,
        val uri: String,
        val httpStatus: Int?,
        val errorClass: LoadErrorClass,
        val retryCount: Int,
        val wasCanceled: Boolean,
        val errorMessage: String?,
    ) : DiagnosticEvent {
        override val type = EventType.LOAD_ERROR
    }

    data class DrmKeyStatus(
        override val metadata: DiagnosticEventMetadata,
        val keyId: String,
        val status: DrmKeyState,
        val securityLevel: String,
        val expirationTimeMs: Long? = null,
        val hdcpLevel: String? = null,
        val maxHdcpLevel: String? = null,
        val hasNewUsableKey: Boolean? = null,
    ) : DiagnosticEvent {
        override val type = EventType.DRM_KEY_STATUS
    }

    data class DrmSessionEvent(
        override val metadata: DiagnosticEventMetadata,
        val eventType: DrmSessionEventType,
        val state: DrmSessionState? = null,
        val errorCode: String? = null,
        val errorMessage: String? = null,
    ) : DiagnosticEvent {
        override val type = EventType.DRM_SESSION_EVENT
    }

    data class DrmRequest(
        override val metadata: DiagnosticEventMetadata,
        val requestKind: DrmRequestKind,
        val licenseRequestType: DrmLicenseRequestType? = null,
        val attemptNumber: Int,
        val durationMs: Long,
        val successful: Boolean,
        val errorCode: String? = null,
        val errorMessage: String? = null,
    ) : DiagnosticEvent {
        init {
            require(attemptNumber > 0) { "attemptNumber must be greater than zero" }
            require(durationMs >= 0) { "durationMs must be non-negative" }
        }

        override val type = EventType.DRM_REQUEST
    }

    data class DrmExpirationUpdate(
        override val metadata: DiagnosticEventMetadata,
        val expirationTimeMs: Long,
    ) : DiagnosticEvent {
        init {
            require(expirationTimeMs >= 0) { "expirationTimeMs must be non-negative" }
        }

        override val type = EventType.DRM_EXPIRATION_UPDATE
    }

    data class BandwidthSample(
        override val metadata: DiagnosticEventMetadata,
        val segmentSizeBytes: Long,
        val downloadDurationMs: Long,
        val instantaneousBps: Long,
        val fastEstimateBps: Long,
        val slowEstimateBps: Long,
        val defaultEstimateBps: Long,
        val confidence: Double,
    ) : DiagnosticEvent {
        override val type = EventType.BANDWIDTH_SAMPLE
    }

    data class PlaybackError(
        override val metadata: DiagnosticEventMetadata,
        val errorCode: String,
        val errorMessage: String? = null,
        val cause: String? = null,
        val isFatal: Boolean = true,
    ) : DiagnosticEvent {
        override val type = EventType.PLAYBACK_ERROR
    }
}

val DiagnosticEvent.sessionId: String get() = metadata.sessionId
val DiagnosticEvent.eventId: String get() = metadata.eventId
val DiagnosticEvent.timestampMs: Long get() = metadata.timestampMs
val DiagnosticEvent.elapsedRealtimeMs: Long get() = metadata.elapsedRealtimeMs
