package com.xheghun.framewright.diagnostics

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.xheghun.analytics.CodecClassificationSource
import com.xheghun.analytics.CodecImplementationType
import com.xheghun.analytics.DecoderCapabilitySnapshot
import com.xheghun.analytics.DiagnosticEvent
import com.xheghun.analytics.DiagnosticEventMetadata
import com.xheghun.analytics.DrmKeyState
import com.xheghun.analytics.DrmScheme
import com.xheghun.analytics.DrmSessionEventType
import com.xheghun.analytics.DrmSessionState
import com.xheghun.analytics.FormatSnapshot
import com.xheghun.analytics.PlayerState
import com.xheghun.analytics.SessionEndReason
import com.xheghun.analytics.TrackSwitchReason
import com.xheghun.analytics.TrackType
import org.junit.jupiter.api.Test

class DiagnosticsOverlayReducerTest {
    @Test
    fun `new playback session clears previous metrics and preserves overlay visibility`() {
        val previousPlaybackState =
            DiagnosticsOverlayState(
                isVisible = true,
                sessionId = "previous-playback-session",
                videoBitrateBps = 4_000_000,
                droppedFrames = 12,
            )

        val newPlaybackState =
            DiagnosticsOverlayReducer.reduce(
                previousPlaybackState,
                sessionStart(
                    sessionId = "new-widevine-session",
                    drmScheme = DrmScheme.WIDEVINE,
                ),
            )

        assertThat(newPlaybackState.isVisible).isTrue()
        assertThat(newPlaybackState.sessionId).isEqualTo("new-widevine-session")
        assertThat(newPlaybackState.drmScheme).isEqualTo(DrmScheme.WIDEVINE)
        assertThat(newPlaybackState.videoBitrateBps).isNull()
        assertThat(newPlaybackState.droppedFrames).isEqualTo(0)
    }

    @Test
    fun `video playback events populate playback pipeline and bandwidth diagnostics`() {
        var playbackState =
            DiagnosticsOverlayReducer.reduce(
                DiagnosticsOverlayState(isVisible = true),
                sessionStart("clear-hls-session"),
            )
        playbackState =
            DiagnosticsOverlayReducer.reduce(
                playbackState,
                DiagnosticEvent.RenderFirstFrame(
                    metadata("clear-hls-session", "first-video-frame", PlayerState.READY),
                    elapsedSincePrepareMs = 860,
                ),
            )
        playbackState =
            DiagnosticsOverlayReducer.reduce(
                playbackState,
                DiagnosticEvent.TrackSwitch(
                    metadata("clear-hls-session", "initial-video-track", PlayerState.READY),
                    fromFormat = null,
                    toFormat =
                        FormatSnapshot(
                            width = 1920,
                            height = 1080,
                            bitrate = 5_200_000,
                            mimeType = "video/avc",
                            codecs = "avc1.640028",
                        ),
                    reason = TrackSwitchReason.INITIAL,
                    estimatedBandwidthBps = 8_000_000,
                    bufferedDurationMs = 12_400,
                ),
            )
        playbackState =
            DiagnosticsOverlayReducer.reduce(
                playbackState,
                DiagnosticEvent.DecoderInit(
                    metadata("clear-hls-session", "video-decoder-initialized", PlayerState.READY),
                    decoderName = "c2.qti.avc.decoder",
                    mimeType = "video/avc",
                    trackType = TrackType.VIDEO,
                    initializationDurationMs = 18,
                    isHardwareAccelerated = true,
                    capabilities =
                        DecoderCapabilitySnapshot(
                            implementationType = CodecImplementationType.HARDWARE_ACCELERATED,
                            classificationSource = CodecClassificationSource.PLATFORM,
                            supportsAdaptivePlayback = true,
                            supportsSecurePlayback = false,
                            supportsTunneledPlayback = false,
                        ),
                ),
            )
        playbackState =
            DiagnosticsOverlayReducer.reduce(
                playbackState,
                bandwidthSample("clear-hls-session"),
            )

        assertThat(playbackState.playerState).isEqualTo(PlayerState.READY)
        assertThat(playbackState.timeToFirstFrameMs).isEqualTo(860)
        assertThat(playbackState.videoWidth).isEqualTo(1920)
        assertThat(playbackState.videoHeight).isEqualTo(1080)
        assertThat(playbackState.videoBitrateBps).isEqualTo(5_200_000)
        assertThat(playbackState.videoCodecs).isEqualTo("avc1.640028")
        assertThat(playbackState.decoderName).isEqualTo("c2.qti.avc.decoder")
        assertThat(playbackState.decoderImplementationType).isEqualTo(CodecImplementationType.HARDWARE_ACCELERATED)
        assertThat(playbackState.lastObservedBufferMs).isEqualTo(12_400)
        assertThat(playbackState.framewrightBandwidthBps).isEqualTo(7_600_000)
        assertThat(playbackState.media3BandwidthBps).isEqualTo(8_300_000)
    }

    @Test
    fun `rebuffer dropped frame and drm events accumulate diagnostics`() {
        var playbackState =
            DiagnosticsOverlayReducer.reduce(
                DiagnosticsOverlayState(),
                sessionStart("widevine-playback-session", DrmScheme.WIDEVINE),
            )
        playbackState =
            DiagnosticsOverlayReducer.reduce(
                playbackState,
                DiagnosticEvent.RebufferStart(
                    metadata("widevine-playback-session", "rebuffer-start", PlayerState.BUFFERING),
                    bufferedMsAtStart = 250,
                ),
            )
        playbackState =
            DiagnosticsOverlayReducer.reduce(
                playbackState,
                DiagnosticEvent.RebufferEnd(
                    metadata("widevine-playback-session", "rebuffer-end", PlayerState.READY),
                    durationMs = 740,
                ),
            )
        playbackState =
            DiagnosticsOverlayReducer.reduce(
                playbackState,
                DiagnosticEvent.DroppedFrames(
                    metadata("widevine-playback-session", "first-dropped-frame-batch", PlayerState.READY),
                    count = 3,
                    elapsedMs = 1_000,
                ),
            )
        playbackState =
            DiagnosticsOverlayReducer.reduce(
                playbackState,
                DiagnosticEvent.DroppedFrames(
                    metadata("widevine-playback-session", "second-dropped-frame-batch", PlayerState.READY),
                    count = 2,
                    elapsedMs = 1_000,
                ),
            )
        playbackState =
            DiagnosticsOverlayReducer.reduce(
                playbackState,
                DiagnosticEvent.DrmSessionEvent(
                    metadata("widevine-playback-session", "widevine-keys-loaded", PlayerState.READY),
                    eventType = DrmSessionEventType.KEYS_LOADED,
                    state = DrmSessionState.OPENED_WITH_KEYS,
                ),
            )
        playbackState =
            DiagnosticsOverlayReducer.reduce(
                playbackState,
                DiagnosticEvent.DrmKeyStatus(
                    metadata("widevine-playback-session", "widevine-key-status", PlayerState.READY),
                    keyId = "video-content-key",
                    status = DrmKeyState.USABLE,
                    securityLevel = "L1",
                    expirationTimeMs = 1_800_000_000_000,
                    hdcpLevel = "HDCP_V2_2",
                    maxHdcpLevel = "HDCP_V2_3",
                ),
            )

        assertThat(playbackState.isRebuffering).isFalse()
        assertThat(playbackState.rebufferCount).isEqualTo(1)
        assertThat(playbackState.totalRebufferDurationMs).isEqualTo(740)
        assertThat(playbackState.droppedFrames).isEqualTo(5)
        assertThat(playbackState.drmSessionState).isEqualTo(DrmSessionState.OPENED_WITH_KEYS)
        assertThat(playbackState.drmKeyState).isEqualTo(DrmKeyState.USABLE)
        assertThat(playbackState.drmSecurityLevel).isEqualTo("L1")
        assertThat(playbackState.drmHdcpLevel).isEqualTo("HDCP_V2_2")
        assertThat(playbackState.drmMaxHdcpLevel).isEqualTo("HDCP_V2_3")
    }

    @Test
    fun `drm release callback replaces previously opened session state`() {
        var playbackState =
            DiagnosticsOverlayReducer.reduce(
                DiagnosticsOverlayState(),
                sessionStart("released-widevine-session", DrmScheme.WIDEVINE),
            )
        playbackState =
            DiagnosticsOverlayReducer.reduce(
                playbackState,
                DiagnosticEvent.DrmSessionEvent(
                    metadata("released-widevine-session", "widevine-keys-loaded", PlayerState.READY),
                    eventType = DrmSessionEventType.KEYS_LOADED,
                    state = DrmSessionState.OPENED_WITH_KEYS,
                ),
            )

        playbackState =
            DiagnosticsOverlayReducer.reduce(
                playbackState,
                DiagnosticEvent.DrmSessionEvent(
                    metadata("released-widevine-session", "widevine-session-released", PlayerState.IDLE),
                    eventType = DrmSessionEventType.RELEASED,
                ),
            )

        assertThat(playbackState.drmSessionEvent).isEqualTo(DrmSessionEventType.RELEASED)
        assertThat(playbackState.drmSessionState).isEqualTo(DrmSessionState.RELEASED)
    }

    @Test
    fun `stale and post-session callbacks cannot change final session values`() {
        var playbackState =
            DiagnosticsOverlayReducer.reduce(
                DiagnosticsOverlayState(),
                sessionStart("active-playback-session"),
            )
        playbackState = DiagnosticsOverlayReducer.reduce(playbackState, bandwidthSample("active-playback-session"))
        playbackState =
            DiagnosticsOverlayReducer.reduce(
                playbackState,
                DiagnosticEvent.SessionEnd(
                    metadata("active-playback-session", "playback-ended", PlayerState.ENDED),
                    durationMs = 30_000,
                    reason = SessionEndReason.PLAYBACK_ENDED,
                ),
            )
        val finalPlaybackState = playbackState

        val stateAfterLateCallback =
            DiagnosticsOverlayReducer.reduce(
                playbackState,
                bandwidthSample("active-playback-session", defaultEstimateBps = 99_000_000),
            )
        val stateAfterStaleSessionCallback =
            DiagnosticsOverlayReducer.reduce(
                playbackState,
                bandwidthSample("stale-playback-session", defaultEstimateBps = 88_000_000),
            )

        assertThat(finalPlaybackState.hasEnded).isTrue()
        assertThat(stateAfterLateCallback).isEqualTo(finalPlaybackState)
        assertThat(stateAfterStaleSessionCallback).isEqualTo(finalPlaybackState)
    }

    private fun sessionStart(
        sessionId: String,
        drmScheme: DrmScheme? = null,
    ) = DiagnosticEvent.SessionStart(
        metadata(sessionId, "session-start", PlayerState.IDLE),
        mediaUri = "https://example.test/video.m3u8",
        drmScheme = drmScheme,
    )

    private fun bandwidthSample(
        sessionId: String,
        defaultEstimateBps: Long = 8_300_000,
    ) = DiagnosticEvent.BandwidthSample(
        metadata(sessionId, "bandwidth-sample", PlayerState.READY),
        segmentSizeBytes = 512_000,
        downloadDurationMs = 500,
        instantaneousBps = 8_600_000,
        fastEstimateBps = 7_600_000,
        slowEstimateBps = 7_900_000,
        defaultEstimateBps = defaultEstimateBps,
        confidence = 0.9,
    )

    private fun metadata(
        sessionId: String,
        eventId: String,
        playerState: PlayerState,
    ) = DiagnosticEventMetadata(
        sessionId = sessionId,
        eventId = eventId,
        timestampMs = 1_000,
        elapsedRealtimeMs = 1_000,
        playerState = playerState,
    )
}
