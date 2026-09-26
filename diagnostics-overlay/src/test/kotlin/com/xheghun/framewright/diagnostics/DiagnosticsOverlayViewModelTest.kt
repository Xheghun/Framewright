package com.xheghun.framewright.diagnostics

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.xheghun.analytics.DiagnosticEvent
import com.xheghun.analytics.DiagnosticEventMetadata
import com.xheghun.analytics.DrmScheme
import com.xheghun.analytics.PlayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsOverlayViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `session start and visibility actions update state immediately`() {
        val viewModel = DiagnosticsOverlayViewModel(updateIntervalMs = 200)

        viewModel.onAction(DiagnosticsOverlayAction.ToggleVisibility)
        viewModel.onDiagnosticEvent(sessionStart("widevine-playback-session"))

        assertThat(viewModel.state.value.isVisible).isTrue()
        assertThat(viewModel.state.value.sessionId).isEqualTo("widevine-playback-session")
        assertThat(viewModel.state.value.drmScheme).isEqualTo(DrmScheme.WIDEVINE)

        viewModel.onAction(DiagnosticsOverlayAction.Close)

        assertThat(viewModel.state.value.isVisible).isFalse()
    }

    @Test
    fun `routine callback burst is published once after update interval`() =
        runTest(testDispatcher.scheduler) {
            val viewModel = DiagnosticsOverlayViewModel(updateIntervalMs = 200)
            viewModel.onDiagnosticEvent(sessionStart("video-playback-session"))

            viewModel.onDiagnosticEvent(bandwidthSample("video-playback-session", "first-bandwidth-sample", 4_000_000))
            viewModel.onDiagnosticEvent(bandwidthSample("video-playback-session", "second-bandwidth-sample", 6_000_000))

            assertThat(viewModel.state.value.media3BandwidthBps).isNull()
            advanceTimeBy(199)
            assertThat(viewModel.state.value.media3BandwidthBps).isNull()

            advanceTimeBy(1)
            runCurrent()

            assertThat(viewModel.state.value.media3BandwidthBps).isEqualTo(6_000_000)
        }

    @Test
    fun `fatal playback error publishes immediately with pending routine diagnostics`() {
        val viewModel = DiagnosticsOverlayViewModel(updateIntervalMs = 200)
        viewModel.onDiagnosticEvent(sessionStart("failing-playback-session"))
        viewModel.onDiagnosticEvent(
            bandwidthSample("failing-playback-session", "last-bandwidth-sample", 2_500_000),
        )

        viewModel.onDiagnosticEvent(
            DiagnosticEvent.PlaybackError(
                metadata("failing-playback-session", "fatal-decoder-error", PlayerState.IDLE),
                errorCode = "ERROR_CODE_DECODING_FAILED",
                errorMessage = "The video decoder stopped",
                isFatal = true,
            ),
        )

        assertThat(viewModel.state.value.media3BandwidthBps).isEqualTo(2_500_000)
        assertThat(
            viewModel.state.value.latestIssue
                ?.code,
        ).isEqualTo("ERROR_CODE_DECODING_FAILED")
        assertThat(
            viewModel.state.value.latestIssue
                ?.isFatal,
        ).isEqualTo(true)
    }

    private fun sessionStart(sessionId: String) =
        DiagnosticEvent.SessionStart(
            metadata(sessionId, "session-start", PlayerState.IDLE),
            mediaUri = "https://example.test/widevine-video.mpd",
            drmScheme = DrmScheme.WIDEVINE,
        )

    private fun bandwidthSample(
        sessionId: String,
        eventId: String,
        defaultEstimateBps: Long,
    ) = DiagnosticEvent.BandwidthSample(
        metadata(sessionId, eventId, PlayerState.READY),
        segmentSizeBytes = 256_000,
        downloadDurationMs = 400,
        instantaneousBps = defaultEstimateBps,
        fastEstimateBps = defaultEstimateBps - 200_000,
        slowEstimateBps = defaultEstimateBps - 100_000,
        defaultEstimateBps = defaultEstimateBps,
        confidence = 0.8,
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
