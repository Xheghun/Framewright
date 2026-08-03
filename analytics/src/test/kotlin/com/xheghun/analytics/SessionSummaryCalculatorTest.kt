package com.xheghun.analytics

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class SessionSummaryCalculatorTest {
    private val calculator = SessionSummaryCalculator()

    @Test
    fun `calculates metrics and excludes startup buffering`() {
        val playbackTimeline =
            listOf(
                DiagnosticEvent.RebufferEnd(
                    metadata(eventId = "startup-buffering-completed"),
                    durationMs = 500,
                ),
                DiagnosticEvent.RenderFirstFrame(
                    metadata(eventId = "first-video-frame-rendered"),
                    elapsedSincePrepareMs = 1_000,
                ),
                DiagnosticEvent.RebufferStart(
                    metadata(eventId = "playback-rebuffer-started"),
                    bufferedMsAtStart = 100,
                ),
                DiagnosticEvent.RebufferEnd(
                    metadata(eventId = "playback-rebuffer-ended"),
                    durationMs = 2_000,
                ),
                DiagnosticEvent.TrackSwitch(
                    metadata = metadata(eventId = "adaptive-track-switch"),
                    fromFormat = null,
                    toFormat = FormatSnapshot(bitrate = 1_000),
                    reason = TrackSwitchReason.INITIAL,
                    estimatedBandwidthBps = 2_000,
                    bufferedDurationMs = 500,
                ),
                DiagnosticEvent.DecoderInit(
                    metadata = metadata(eventId = "video-decoder-initialized"),
                    decoderName = "c2.android.avc.decoder",
                    mimeType = "video/avc",
                    trackType = TrackType.VIDEO,
                    initializationDurationMs = 20,
                    isHardwareAccelerated = true,
                ),
                DiagnosticEvent.DroppedFrames(
                    metadata(eventId = "dropped-video-frames"),
                    count = 4,
                    elapsedMs = 1_000,
                ),
                DiagnosticEvent.SessionEnd(
                    metadata(eventId = "playback-session-ended"),
                    durationMs = 60_000,
                    reason = SessionEndReason.PLAYBACK_ENDED,
                ),
            )

        val sessionSummary =
            calculator.calculate(
                SessionSnapshot(
                    sessionId = "completed-playback-session",
                    truncated = false,
                    events = playbackTimeline,
                ),
            )

        assertThat(sessionSummary.timeToFirstFrameMs).isEqualTo(1_000)
        assertThat(sessionSummary.rebufferCount).isEqualTo(1)
        assertThat(sessionSummary.totalRebufferDurationMs).isEqualTo(2_000)
        assertThat(sessionSummary.rebufferRatio).isEqualTo(2_000.0 / 60_000)
        assertThat(sessionSummary.trackSwitchesPerMinute).isEqualTo(1.0)
        assertThat(sessionSummary.averageDecoderInitializationMs).isEqualTo(20.0)
        assertThat(sessionSummary.droppedFrameCount).isEqualTo(4)
        assertThat(sessionSummary.startupFailed).isFalse()
    }

    @Test
    fun `reports startup failure and unavailable duration metrics`() {
        val fatalStartupError =
            DiagnosticEvent.PlaybackError(
                metadata = metadata(eventId = "fatal-startup-network-error"),
                errorCode = "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
                isFatal = true,
            )
        val startupFailureSummary =
            calculator.calculate(
                SessionSnapshot(
                    sessionId = "failed-startup-session",
                    truncated = false,
                    events = listOf(fatalStartupError),
                ),
            )

        assertThat(startupFailureSummary.startupFailed).isTrue()
        assertThat(startupFailureSummary.timeToFirstFrameMs).isNull()
        assertThat(startupFailureSummary.rebufferRatio).isNull()
        assertThat(startupFailureSummary.trackSwitchesPerMinute).isNull()
    }
}
