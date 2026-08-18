package com.xheghun.framewright.abr

import androidx.compose.runtime.Stable
import com.xheghun.analytics.TrackSwitchReason

@Stable
data class AbrExplorerState(
    val isVisible: Boolean = false,
    val sessionId: String? = null,
    val elapsedDurationMs: Long = 0,
    val bandwidthPoints: List<BandwidthPointUi> = emptyList(),
    val bitrateTracks: List<BitrateTrackUi> = emptyList(),
    val decisions: List<AbrDecisionUi> = emptyList(),
)

data class BandwidthPointUi(
    val eventId: String,
    val elapsedMs: Long,
    val customEstimateBps: Long,
    val defaultEstimateBps: Long,
    val confidence: Double,
)

data class BitrateTrackUi(
    val id: String,
    val label: String,
    val bitrateBps: Int,
    val isSelected: Boolean,
)

data class AbrDecisionUi(
    val eventId: String,
    val elapsedMs: Long,
    val title: String,
    val details: String,
    val reason: TrackSwitchReason,
)

sealed interface AbrExplorerAction {
    data object ToggleVisibility : AbrExplorerAction

    data object Close : AbrExplorerAction
}
