package com.xheghun.framewright.diagnostics

import com.xheghun.analytics.CodecImplementationType
import com.xheghun.analytics.DiagnosticEvent
import com.xheghun.analytics.DrmSessionEventType
import com.xheghun.analytics.DrmSessionState
import com.xheghun.analytics.PlayerState
import com.xheghun.analytics.TrackType

internal object DiagnosticsOverlayReducer {
    fun reduce(
        currentState: DiagnosticsOverlayState,
        event: DiagnosticEvent,
    ): DiagnosticsOverlayState {
        if (event is DiagnosticEvent.SessionStart) {
            return DiagnosticsOverlayState(
                isVisible = currentState.isVisible,
                sessionId = event.metadata.sessionId,
                playerState = event.metadata.playerState,
                drmScheme = event.drmScheme,
            )
        }
        if (
            currentState.sessionId == null ||
            currentState.hasEnded ||
            currentState.sessionId != event.metadata.sessionId
        ) {
            return currentState
        }

        val stateWithLatestPlayerState =
            event.metadata.playerState?.let { currentState.copy(playerState = it) } ?: currentState

        return when (event) {
            is DiagnosticEvent.SessionStart -> error("SessionStart is handled before session filtering")
            is DiagnosticEvent.SessionEnd ->
                stateWithLatestPlayerState.copy(
                    hasEnded = true,
                    playerState = event.metadata.playerState ?: PlayerState.ENDED,
                    isRebuffering = false,
                )
            is DiagnosticEvent.RenderFirstFrame ->
                stateWithLatestPlayerState.copy(timeToFirstFrameMs = event.elapsedSincePrepareMs)
            is DiagnosticEvent.RebufferStart ->
                stateWithLatestPlayerState.copy(
                    lastObservedBufferMs = event.bufferedMsAtStart,
                    isRebuffering = true,
                )
            is DiagnosticEvent.RebufferEnd ->
                stateWithLatestPlayerState.copy(
                    isRebuffering = false,
                    rebufferCount = stateWithLatestPlayerState.rebufferCount + 1,
                    totalRebufferDurationMs = stateWithLatestPlayerState.totalRebufferDurationMs + event.durationMs,
                )
            is DiagnosticEvent.TrackSwitch ->
                stateWithLatestPlayerState.copy(
                    videoWidth = event.toFormat.width,
                    videoHeight = event.toFormat.height,
                    videoBitrateBps = event.toFormat.bitrate.toLong(),
                    videoMimeType = event.toFormat.mimeType,
                    videoCodecs = event.toFormat.codecs,
                    lastObservedBufferMs = event.bufferedDurationMs,
                )
            is DiagnosticEvent.DecoderInit -> {
                if (event.trackType != TrackType.VIDEO) {
                    stateWithLatestPlayerState
                } else {
                    stateWithLatestPlayerState.copy(
                        videoMimeType = event.mimeType,
                        decoderName = event.decoderName,
                        decoderImplementationType =
                            event.capabilities?.implementationType
                                ?: event.isHardwareAccelerated.toCodecImplementationType(),
                    )
                }
            }
            is DiagnosticEvent.DroppedFrames ->
                stateWithLatestPlayerState.copy(
                    droppedFrames = stateWithLatestPlayerState.droppedFrames + event.count.coerceAtLeast(0),
                )
            is DiagnosticEvent.LoadError ->
                stateWithLatestPlayerState.copy(
                    latestIssue =
                        DiagnosticIssue(
                            kind = DiagnosticIssueKind.LOAD,
                            code = event.httpStatus?.let { "HTTP $it" } ?: event.errorClass.name,
                            message = event.errorMessage,
                            isFatal = false,
                        ),
                )
            is DiagnosticEvent.DrmKeyStatus ->
                stateWithLatestPlayerState.copy(
                    drmKeyState = event.status,
                    drmSecurityLevel = event.securityLevel,
                    drmExpirationTimeMs = event.expirationTimeMs ?: stateWithLatestPlayerState.drmExpirationTimeMs,
                    drmHdcpLevel = event.hdcpLevel,
                    drmMaxHdcpLevel = event.maxHdcpLevel,
                )
            is DiagnosticEvent.DrmSessionEvent ->
                stateWithLatestPlayerState.copy(
                    drmSessionEvent = event.eventType,
                    drmSessionState =
                        event.state
                            ?: when (event.eventType) {
                                DrmSessionEventType.RELEASED -> DrmSessionState.RELEASED
                                DrmSessionEventType.ERROR -> DrmSessionState.ERROR
                                else -> stateWithLatestPlayerState.drmSessionState
                            },
                    drmErrorCode = event.errorCode,
                    drmErrorMessage = event.errorMessage,
                )
            is DiagnosticEvent.DrmRequest ->
                stateWithLatestPlayerState.copy(
                    drmRequestKind = event.requestKind,
                    drmLicenseRequestType = event.licenseRequestType,
                    drmRequestSuccessful = event.successful,
                    drmErrorCode = if (event.successful) null else event.errorCode,
                    drmErrorMessage = if (event.successful) null else event.errorMessage,
                )
            is DiagnosticEvent.DrmExpirationUpdate ->
                stateWithLatestPlayerState.copy(drmExpirationTimeMs = event.expirationTimeMs)
            is DiagnosticEvent.BandwidthSample ->
                stateWithLatestPlayerState.copy(
                    framewrightBandwidthBps = minOf(event.fastEstimateBps, event.slowEstimateBps),
                    media3BandwidthBps = event.defaultEstimateBps,
                )
            is DiagnosticEvent.PlaybackError ->
                stateWithLatestPlayerState.copy(
                    latestIssue =
                        DiagnosticIssue(
                            kind = DiagnosticIssueKind.PLAYBACK,
                            code = event.errorCode,
                            message = event.errorMessage ?: event.cause,
                            isFatal = event.isFatal,
                        ),
                )
        }
    }

    private fun Boolean?.toCodecImplementationType(): CodecImplementationType =
        when (this) {
            true -> CodecImplementationType.HARDWARE_ACCELERATED
            false -> CodecImplementationType.SOFTWARE_ONLY
            null -> CodecImplementationType.UNKNOWN
        }
}
