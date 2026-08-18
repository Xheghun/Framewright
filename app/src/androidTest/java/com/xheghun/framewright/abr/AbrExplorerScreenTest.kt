package com.xheghun.framewright.abr

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.xheghun.analytics.TrackSwitchReason
import com.xheghun.framewright.ui.theme.FramewrightTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AbrExplorerScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val robot by lazy { AbrExplorerRobot(composeTestRule) }

    @Test
    fun emptyStateShowsAllThreeExplorerPanes() {
        robot
            .setContent(AbrExplorerState(isVisible = true))
            .assertEmptyTimeline()
            .assertBitrateLadder()
            .assertDecisionLog()
    }

    @Test
    fun populatedStateShowsEstimatorChartSelectedTrackAndDecision() {
        robot
            .setContent(populatedState())
            .assertEstimatorLegends()
            .assertTimelineDescription("Bandwidth chart containing 2 samples")
            .assertSelectedTrack("1280×720 · 2.00 Mbps")
            .assertDecision("Adaptive upshift")
    }

    @Test
    fun closeButtonDispatchesCloseAction() {
        var receivedAction: AbrExplorerAction? = null
        robot
            .setContent(AbrExplorerState(isVisible = true)) { receivedAction = it }
            .close()

        assertEquals(AbrExplorerAction.Close, receivedAction)
    }

    private fun populatedState() =
        AbrExplorerState(
            isVisible = true,
            elapsedDurationMs = 4_000,
            bandwidthPoints =
                listOf(
                    BandwidthPointUi("sample-1", 1_000, 2_000_000, 2_400_000, 0.4),
                    BandwidthPointUi("sample-2", 3_000, 3_000_000, 2_800_000, 0.8),
                ),
            bitrateTracks =
                listOf(
                    BitrateTrackUi("480", "854×480 · 900 kbps", 900_000, false),
                    BitrateTrackUi("720", "1280×720 · 2.00 Mbps", 2_000_000, true),
                ),
            decisions =
                listOf(
                    AbrDecisionUi(
                        "switch-1",
                        3_000,
                        "Adaptive upshift",
                        "854×480 · 900 kbps → 1280×720 · 2.00 Mbps",
                        TrackSwitchReason.BANDWIDTH_INCREASE,
                    ),
                ),
        )
}

private class AbrExplorerRobot(
    private val rule: ComposeContentTestRule,
) {
    fun setContent(
        state: AbrExplorerState,
        onAction: (AbrExplorerAction) -> Unit = {},
    ) = apply {
        rule.setContent {
            FramewrightTheme {
                AbrExplorerScreen(state = state, onAction = onAction)
            }
        }
    }

    fun assertEmptyTimeline() =
        apply {
            rule.onNodeWithTag(ABR_EMPTY_TIMELINE_TAG).assertIsDisplayed()
        }

    fun assertBitrateLadder() =
        apply {
            rule.onNodeWithTag(ABR_BITRATE_LADDER_TAG).assertIsDisplayed()
        }

    fun assertDecisionLog() =
        apply {
            rule.onNodeWithTag(ABR_DECISION_LOG_TAG).assertIsDisplayed()
        }

    fun assertEstimatorLegends() =
        apply {
            rule.onNodeWithText("Framewright").assertIsDisplayed()
            rule.onNodeWithText("Media3 default").assertIsDisplayed()
        }

    fun assertTimelineDescription(description: String) =
        apply {
            rule.onNode(hasContentDescription(description)).assertIsDisplayed()
        }

    fun assertSelectedTrack(label: String) =
        apply {
            rule.onNodeWithText(label).assertIsDisplayed()
            rule.onNodeWithText("Selected").assertIsDisplayed()
        }

    fun assertDecision(title: String) =
        apply {
            rule.onNodeWithText(title).assertIsDisplayed()
        }

    fun close() =
        apply {
            rule.onNodeWithText("Close").performClick()
        }
}
