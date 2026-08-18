package com.xheghun.framewright.abr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xheghun.framewright.R
import com.xheghun.framewright.ui.theme.FramewrightTheme
import java.util.Locale

internal const val ABR_EXPLORER_TAG = "abr_explorer"
internal const val ABR_TIMELINE_TAG = "abr_timeline"
internal const val ABR_EMPTY_TIMELINE_TAG = "abr_empty_timeline"
internal const val ABR_BITRATE_LADDER_TAG = "abr_bitrate_ladder"
internal const val ABR_DECISION_LOG_TAG = "abr_decision_log"

@Composable
fun AbrExplorerScreen(
    state: AbrExplorerState,
    onAction: (AbrExplorerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize().testTag(ABR_EXPLORER_TAG),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.abr_explorer_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.abr_session_elapsed, formatDuration(state.elapsedDurationMs)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { onAction(AbrExplorerAction.Close) }) {
                    Text(stringResource(R.string.close_abr_explorer))
                }
            }

            BandwidthTimeline(
                points = state.bandwidthPoints,
                elapsedDurationMs = state.elapsedDurationMs,
            )
            BitrateLadder(state.bitrateTracks)
            DecisionLog(
                decisions = state.decisions,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BandwidthTimeline(
    points: List<BandwidthPointUi>,
    elapsedDurationMs: Long,
) {
    val customColor = Color(0xFF00B8D4)
    val defaultColor = Color(0xFFFF9800)
    val chartDescription = stringResource(R.string.abr_timeline_description, points.size)
    val maximumBps = points.maxOfOrNull { maxOf(it.customEstimateBps, it.defaultEstimateBps) } ?: 0
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.bandwidth_timeline), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TimelineLegend(customColor, stringResource(R.string.custom_estimator))
                TimelineLegend(defaultColor, stringResource(R.string.media3_default_estimator))
            }
            if (points.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(150.dp).testTag(ABR_EMPTY_TIMELINE_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.waiting_for_bandwidth_samples),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.abr_chart_range, formatBitrate(maximumBps), formatDuration(elapsedDurationMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Canvas(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .testTag(ABR_TIMELINE_TAG)
                            .semantics { contentDescription = chartDescription },
                ) {
                    val maxTime = maxOf(elapsedDurationMs, points.last().elapsedMs, 1)
                    val maxValue = maximumBps.coerceAtLeast(1)
                    val chartWidth = size.width.coerceAtLeast(1f)
                    val chartHeight = size.height.coerceAtLeast(1f)
                    repeat(4) { index ->
                        val y = chartHeight * index / 3f
                        drawLine(
                            color = Color.Gray.copy(alpha = 0.2f),
                            start = Offset(0f, y),
                            end = Offset(chartWidth, y),
                        )
                    }
                    drawTimeline(points, customColor, chartWidth, chartHeight, maxTime, maxValue) { customEstimateBps }
                    drawTimeline(points, defaultColor, chartWidth, chartHeight, maxTime, maxValue) { defaultEstimateBps }
                }
            }
        }
    }
}

@Composable
private fun TimelineLegend(
    color: Color,
    label: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTimeline(
    points: List<BandwidthPointUi>,
    color: Color,
    chartWidth: Float,
    chartHeight: Float,
    maxTimeMs: Long,
    maxBitrateBps: Long,
    value: BandwidthPointUi.() -> Long,
) {
    val coordinates =
        points.map {
            Offset(
                x = chartWidth * (it.elapsedMs.toFloat() / maxTimeMs.toFloat()),
                y = chartHeight * (1f - it.value().toFloat() / maxBitrateBps.toFloat()),
            )
        }
    if (coordinates.size == 1) {
        drawCircle(color = color, radius = 5.dp.toPx(), center = coordinates.single())
        return
    }
    val path =
        Path().apply {
            coordinates.forEachIndexed { index, point ->
                if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
        }
    drawPath(
        path = path,
        color = color,
        style =
            androidx.compose.ui.graphics.drawscope
                .Stroke(width = 2.dp.toPx()),
    )
}

@Composable
private fun BitrateLadder(tracks: List<BitrateTrackUi>) {
    Card(modifier = Modifier.fillMaxWidth().testTag(ABR_BITRATE_LADDER_TAG)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.bitrate_ladder), style = MaterialTheme.typography.titleMedium)
            if (tracks.isEmpty()) {
                Text(
                    text = stringResource(R.string.waiting_for_video_tracks),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tracks, key = BitrateTrackUi::id) { track ->
                        Card(
                            colors =
                                CardDefaults.cardColors(
                                    containerColor =
                                        if (track.isSelected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                ),
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(track.label, fontWeight = if (track.isSelected) FontWeight.Bold else FontWeight.Normal)
                                if (track.isSelected) {
                                    Text(
                                        stringResource(R.string.selected_track),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DecisionLog(
    decisions: List<AbrDecisionUi>,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().testTag(ABR_DECISION_LOG_TAG)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.abr_decision_log), style = MaterialTheme.typography.titleMedium)
            if (decisions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.waiting_for_track_decisions),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(decisions.asReversed(), key = AbrDecisionUi::eventId) { decision ->
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(decision.title, fontWeight = FontWeight.SemiBold)
                                Text(formatDuration(decision.elapsedMs), style = MaterialTheme.typography.labelSmall)
                            }
                            Text(
                                decision.details,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatBitrate(bitrateBps: Long): String =
    when {
        bitrateBps >= 1_000_000 -> String.format(Locale.ROOT, "%.2f Mbps", bitrateBps / 1_000_000.0)
        bitrateBps > 0 -> String.format(Locale.ROOT, "%.0f kbps", bitrateBps / 1_000.0)
        else -> "0 bps"
    }

private fun formatDuration(durationMs: Long): String = String.format(Locale.ROOT, "%.1f s", durationMs / 1_000.0)

@Preview(showBackground = true)
@Composable
private fun AbrExplorerScreenPreview() {
    FramewrightTheme {
        AbrExplorerScreen(
            state =
                AbrExplorerState(
                    isVisible = true,
                    sessionId = "preview-session",
                    elapsedDurationMs = 18_000,
                    bandwidthPoints =
                        listOf(
                            BandwidthPointUi("sample-1", 2_000, 2_400_000, 3_000_000, 0.4),
                            BandwidthPointUi("sample-2", 8_000, 4_100_000, 3_700_000, 0.8),
                            BandwidthPointUi("sample-3", 16_000, 2_800_000, 3_200_000, 1.0),
                        ),
                    bitrateTracks =
                        listOf(
                            BitrateTrackUi("480", "854×480 · 900 kbps", 900_000, false),
                            BitrateTrackUi("720", "1280×720 · 2.00 Mbps", 2_000_000, true),
                            BitrateTrackUi("1080", "1920×1080 · 5.00 Mbps", 5_000_000, false),
                        ),
                    decisions =
                        listOf(
                            AbrDecisionUi(
                                "switch-1",
                                8_000,
                                "Adaptive upshift",
                                "854×480 · 900 kbps → 1280×720 · 2.00 Mbps · estimate 4.10 Mbps · buffer 8400 ms",
                                com.xheghun.analytics.TrackSwitchReason.BANDWIDTH_INCREASE,
                            ),
                        ),
                ),
            onAction = {},
        )
    }
}
