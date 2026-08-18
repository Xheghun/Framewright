package com.xheghun.framewright.abr

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.xheghun.analytics.DiagnosticEvent
import com.xheghun.analytics.DiagnosticEventMetadata
import com.xheghun.analytics.FormatSnapshot
import com.xheghun.analytics.TrackSwitchReason
import org.junit.jupiter.api.Test

class AbrExplorerViewModelTest {
    private val viewModel = AbrExplorerViewModel()

    @Test
    fun `session start clears previous diagnostics while preserving visibility`() {
        viewModel.onAction(AbrExplorerAction.ToggleVisibility)
        viewModel.onDiagnosticEvent(sessionStart("first-session", elapsedMs = 1_000))
        viewModel.onDiagnosticEvent(bandwidthSample("first-session", "sample-1", elapsedMs = 2_000))

        viewModel.onDiagnosticEvent(sessionStart("replacement-session", elapsedMs = 5_000))

        val state = viewModel.state.value
        assertThat(state.isVisible).isTrue()
        assertThat(state.sessionId).isEqualTo("replacement-session")
        assertThat(state.bandwidthPoints).isEqualTo(emptyList())
        assertThat(state.bitrateTracks).isEqualTo(emptyList())
        assertThat(state.decisions).isEqualTo(emptyList())
        assertThat(state.elapsedDurationMs).isEqualTo(0)
    }

    @Test
    fun `bandwidth and track events create coordinated explorer state`() {
        viewModel.onDiagnosticEvent(sessionStart("playback-session", elapsedMs = 1_000))
        viewModel.onDiagnosticEvent(bandwidthSample("playback-session", "sample-1", elapsedMs = 3_000))
        viewModel.onDiagnosticEvent(
            trackSwitch(
                sessionId = "playback-session",
                eventId = "switch-1",
                elapsedMs = 4_000,
                from = FormatSnapshot(854, 480, 900_000),
                to = FormatSnapshot(1280, 720, 2_000_000),
                reason = TrackSwitchReason.BANDWIDTH_INCREASE,
            ),
        )

        val state = viewModel.state.value
        assertThat(state.bandwidthPoints.single().elapsedMs).isEqualTo(2_000)
        assertThat(state.bandwidthPoints.single().customEstimateBps).isEqualTo(2_500_000)
        assertThat(state.bitrateTracks.map { it.bitrateBps }).containsExactly(900_000, 2_000_000, 5_000_000)
        assertThat(state.bitrateTracks.single { it.isSelected }.bitrateBps).isEqualTo(2_000_000)
        assertThat(state.decisions.single().title).isEqualTo("Adaptive upshift")
        assertThat(state.decisions.single().details).contains("estimate 3.20 Mbps")
        assertThat(state.elapsedDurationMs).isEqualTo(3_000)
    }

    @Test
    fun `live histories are bounded without affecting elapsed session time`() {
        viewModel.onDiagnosticEvent(sessionStart("long-session", elapsedMs = 1_000))
        repeat(605) { index ->
            viewModel.onDiagnosticEvent(
                bandwidthSample(
                    sessionId = "long-session",
                    eventId = "sample-$index",
                    elapsedMs = 2_000L + index,
                ),
            )
        }
        repeat(105) { index ->
            viewModel.onDiagnosticEvent(
                trackSwitch(
                    sessionId = "long-session",
                    eventId = "switch-$index",
                    elapsedMs = 3_000L + index,
                    from = FormatSnapshot(bitrate = 900_000 + index),
                    to = FormatSnapshot(bitrate = 1_000_000 + index),
                    reason = TrackSwitchReason.BANDWIDTH_INCREASE,
                ),
            )
        }

        val state = viewModel.state.value
        assertThat(state.bandwidthPoints.size).isEqualTo(600)
        assertThat(state.bandwidthPoints.first().eventId).isEqualTo("sample-5")
        assertThat(state.decisions.size).isEqualTo(100)
        assertThat(state.decisions.first().eventId).isEqualTo("switch-5")
        assertThat(state.elapsedDurationMs).isEqualTo(2_104)
    }

    @Test
    fun `stale session events and close action do not mutate active diagnostics`() {
        viewModel.onDiagnosticEvent(sessionStart("active-session", elapsedMs = 1_000))
        viewModel.onDiagnosticEvent(bandwidthSample("stale-session", "stale-sample", elapsedMs = 2_000))
        viewModel.onAction(AbrExplorerAction.ToggleVisibility)
        viewModel.onAction(AbrExplorerAction.Close)

        val state = viewModel.state.value
        assertThat(state.bandwidthPoints).isEqualTo(emptyList())
        assertThat(state.isVisible).isFalse()
    }

    private fun sessionStart(
        sessionId: String,
        elapsedMs: Long,
    ) = DiagnosticEvent.SessionStart(metadata(sessionId, "session-start", elapsedMs), "https://example.test/video.m3u8")

    private fun bandwidthSample(
        sessionId: String,
        eventId: String,
        elapsedMs: Long,
    ) = DiagnosticEvent.BandwidthSample(
        metadata(sessionId, eventId, elapsedMs),
        segmentSizeBytes = 64_000,
        downloadDurationMs = 500,
        instantaneousBps = 3_000_000,
        fastEstimateBps = 2_500_000,
        slowEstimateBps = 2_800_000,
        defaultEstimateBps = 3_100_000,
        confidence = 0.8,
    )

    private fun trackSwitch(
        sessionId: String,
        eventId: String,
        elapsedMs: Long,
        from: FormatSnapshot,
        to: FormatSnapshot,
        reason: TrackSwitchReason,
    ) = DiagnosticEvent.TrackSwitch(
        metadata = metadata(sessionId, eventId, elapsedMs),
        fromFormat = from,
        toFormat = to,
        reason = reason,
        estimatedBandwidthBps = 3_200_000,
        bufferedDurationMs = 8_400,
        availableVideoFormats =
            listOf(
                FormatSnapshot(1920, 1080, 5_000_000),
                from,
                to,
            ),
    )

    private fun metadata(
        sessionId: String,
        eventId: String,
        elapsedMs: Long,
    ) = DiagnosticEventMetadata(sessionId, eventId, timestampMs = elapsedMs, elapsedRealtimeMs = elapsedMs)
}
