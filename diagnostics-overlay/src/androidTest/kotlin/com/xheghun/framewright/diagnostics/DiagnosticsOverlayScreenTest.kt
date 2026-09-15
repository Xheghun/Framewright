package com.xheghun.framewright.diagnostics

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.xheghun.analytics.CodecImplementationType
import com.xheghun.analytics.DrmKeyState
import com.xheghun.analytics.DrmScheme
import com.xheghun.analytics.DrmSessionState
import com.xheghun.analytics.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DiagnosticsOverlayScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val robot by lazy { DiagnosticsOverlayRobot(composeTestRule) }

    @Test
    fun hiddenStateDoesNotRenderOverlay() {
        robot
            .setContent(DiagnosticsOverlayState(isVisible = false))
            .assertOverlayIsHidden()
    }

    @Test
    fun populatedStateShowsPlaybackPipelineBandwidthAndDrmDiagnostics() {
        robot
            .setContent(populatedDiagnosticsState())
            .assertMetricIsVisible("1920×1080")
            .assertMetricIsVisible("5.20 Mbps")
            .assertMetricIsVisible("c2.qti.avc.decoder")
            .assertMetricIsVisible("Hardware accelerated")
            .assertMetricIsVisible("8.40 Mbps")
            .assertMetricIsVisible("Widevine")
            .assertMetricIsVisible("Opened with keys")
            .assertMetricIsVisible("L1")
            .assertMetricIsVisible("HTTP 503")
    }

    @Test
    fun closeButtonDispatchesCloseAction() {
        var receivedAction: DiagnosticsOverlayAction? = null

        robot
            .setContent(populatedDiagnosticsState()) { receivedAction = it }
            .closeOverlay()

        assertEquals(DiagnosticsOverlayAction.Close, receivedAction)
    }

    private fun populatedDiagnosticsState() =
        DiagnosticsOverlayState(
            isVisible = true,
            sessionId = "widevine-playback-session",
            playerState = PlayerState.READY,
            timeToFirstFrameMs = 860,
            videoWidth = 1920,
            videoHeight = 1080,
            videoBitrateBps = 5_200_000,
            videoCodecs = "avc1.640028",
            decoderName = "c2.qti.avc.decoder",
            decoderImplementationType = CodecImplementationType.HARDWARE_ACCELERATED,
            framewrightBandwidthBps = 8_400_000,
            media3BandwidthBps = 8_900_000,
            lastObservedBufferMs = 12_400,
            rebufferCount = 1,
            totalRebufferDurationMs = 740,
            drmScheme = DrmScheme.WIDEVINE,
            drmSessionState = DrmSessionState.OPENED_WITH_KEYS,
            drmKeyState = DrmKeyState.USABLE,
            drmSecurityLevel = "L1",
            drmHdcpLevel = "HDCP_V2_2",
            drmMaxHdcpLevel = "HDCP_V2_3",
            droppedFrames = 3,
            latestIssue =
                DiagnosticIssue(
                    kind = DiagnosticIssueKind.LOAD,
                    code = "HTTP 503",
                    message = "Service unavailable",
                    isFatal = false,
                ),
        )
}

private class DiagnosticsOverlayRobot(
    private val rule: ComposeContentTestRule,
) {
    fun setContent(
        state: DiagnosticsOverlayState,
        onAction: (DiagnosticsOverlayAction) -> Unit = {},
    ) = apply {
        rule.setContent {
            MaterialTheme {
                DiagnosticsOverlayScreen(state = state, onAction = onAction)
            }
        }
    }

    fun assertOverlayIsHidden() =
        apply {
            rule.onNodeWithTag(DIAGNOSTICS_OVERLAY_TAG).assertDoesNotExist()
        }

    fun assertMetricIsVisible(value: String) =
        apply {
            rule.onNodeWithText(value).performScrollTo().assertIsDisplayed()
        }

    fun closeOverlay() =
        apply {
            rule.onNodeWithText("Close").performClick()
        }
}
