package com.xheghun.framewright.diagnostics

import com.xheghun.analytics.CodecImplementationType
import com.xheghun.analytics.DrmKeyState
import com.xheghun.analytics.DrmLicenseRequestType
import com.xheghun.analytics.DrmRequestKind
import com.xheghun.analytics.DrmScheme
import com.xheghun.analytics.DrmSessionEventType
import com.xheghun.analytics.DrmSessionState
import com.xheghun.analytics.PlayerState

data class DiagnosticsOverlayState(
    val isVisible: Boolean = false,
    val sessionId: String? = null,
    val hasEnded: Boolean = false,
    val playerState: PlayerState? = null,
    val timeToFirstFrameMs: Long? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoBitrateBps: Long? = null,
    val videoMimeType: String? = null,
    val videoCodecs: String? = null,
    val decoderName: String? = null,
    val decoderImplementationType: CodecImplementationType? = null,
    val framewrightBandwidthBps: Long? = null,
    val media3BandwidthBps: Long? = null,
    val lastObservedBufferMs: Long? = null,
    val isRebuffering: Boolean = false,
    val rebufferCount: Int = 0,
    val totalRebufferDurationMs: Long = 0,
    val drmScheme: DrmScheme? = null,
    val drmSessionEvent: DrmSessionEventType? = null,
    val drmSessionState: DrmSessionState? = null,
    val drmKeyState: DrmKeyState? = null,
    val drmSecurityLevel: String? = null,
    val drmHdcpLevel: String? = null,
    val drmMaxHdcpLevel: String? = null,
    val drmExpirationTimeMs: Long? = null,
    val drmRequestKind: DrmRequestKind? = null,
    val drmLicenseRequestType: DrmLicenseRequestType? = null,
    val drmRequestSuccessful: Boolean? = null,
    val drmErrorCode: String? = null,
    val drmErrorMessage: String? = null,
    val droppedFrames: Int = 0,
    val latestIssue: DiagnosticIssue? = null,
)

data class DiagnosticIssue(
    val kind: DiagnosticIssueKind,
    val code: String,
    val message: String?,
    val isFatal: Boolean,
)

enum class DiagnosticIssueKind {
    LOAD,
    PLAYBACK,
}

sealed interface DiagnosticsOverlayAction {
    data object ToggleVisibility : DiagnosticsOverlayAction

    data object Close : DiagnosticsOverlayAction
}
