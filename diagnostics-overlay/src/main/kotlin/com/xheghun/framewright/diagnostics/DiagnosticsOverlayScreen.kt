package com.xheghun.framewright.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xheghun.analytics.CodecImplementationType
import com.xheghun.analytics.DrmKeyState
import com.xheghun.analytics.DrmScheme
import com.xheghun.analytics.DrmSessionState
import com.xheghun.analytics.PlayerState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

const val DIAGNOSTICS_OVERLAY_TAG = "diagnostics_overlay"

@Composable
fun DiagnosticsOverlayScreen(
    state: DiagnosticsOverlayState,
    onAction: (DiagnosticsOverlayAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isVisible) return

    val overlayTitle = stringResource(R.string.diagnostics_overlay_title)
    Surface(
        modifier =
            modifier
                .heightIn(max = 640.dp)
                .testTag(DIAGNOSTICS_OVERLAY_TAG)
                .semantics { paneTitle = overlayTitle },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OverlayHeader(onClose = { onAction(DiagnosticsOverlayAction.Close) })
            OverlaySection(title = stringResource(R.string.playback_section_title)) {
                MetricRow(stringResource(R.string.player_state), state.playerState.displayName())
                MetricRow(stringResource(R.string.time_to_first_frame), state.timeToFirstFrameMs.formatDuration())
                MetricRow(stringResource(R.string.resolution), formatResolution(state.videoWidth, state.videoHeight))
                MetricRow(stringResource(R.string.video_bitrate), state.videoBitrateBps.formatBitrate())
                MetricRow(stringResource(R.string.last_observed_buffer), state.lastObservedBufferMs.formatDuration())
                MetricRow(
                    stringResource(R.string.rebuffering),
                    if (state.isRebuffering) stringResource(R.string.yes) else stringResource(R.string.no),
                )
                MetricRow(
                    stringResource(R.string.rebuffer_summary),
                    stringResource(
                        R.string.rebuffer_summary_value,
                        state.rebufferCount,
                        state.totalRebufferDurationMs.formatDuration(),
                    ),
                )
                MetricRow(stringResource(R.string.dropped_frames), state.droppedFrames.toString())
            }
            OverlaySection(title = stringResource(R.string.video_pipeline_section_title)) {
                MetricRow(stringResource(R.string.codec), state.videoCodecs ?: state.videoMimeType.orUnknown())
                MetricRow(stringResource(R.string.decoder), state.decoderName.orUnknown())
                MetricRow(stringResource(R.string.decoder_type), state.decoderImplementationType.displayName())
                MetricRow(stringResource(R.string.framewright_bandwidth), state.framewrightBandwidthBps.formatBitrate())
                MetricRow(stringResource(R.string.media3_bandwidth), state.media3BandwidthBps.formatBitrate())
            }
            OverlaySection(title = stringResource(R.string.drm_section_title)) {
                MetricRow(stringResource(R.string.drm_scheme), state.drmScheme.displayName(state.sessionId != null))
                MetricRow(
                    stringResource(R.string.drm_session),
                    state.drmSessionState?.displayName() ?: state.drmSessionEvent?.name.displayName(),
                )
                MetricRow(stringResource(R.string.drm_key_state), state.drmKeyState.displayName())
                MetricRow(stringResource(R.string.drm_security_level), state.drmSecurityLevel.orUnknown())
                MetricRow(
                    stringResource(R.string.drm_hdcp),
                    formatHdcp(state.drmHdcpLevel, state.drmMaxHdcpLevel),
                )
                MetricRow(stringResource(R.string.drm_expiration), state.drmExpirationTimeMs.formatExpiration())
                MetricRow(
                    stringResource(R.string.drm_last_request),
                    formatDrmRequest(state),
                )
                if (state.drmErrorCode != null || state.drmErrorMessage != null) {
                    MetricRow(
                        stringResource(R.string.drm_error),
                        listOfNotNull(state.drmErrorCode, state.drmErrorMessage).joinToString(" · "),
                        valueColor = MaterialTheme.colorScheme.error,
                    )
                }
            }
            state.latestIssue?.let { LatestIssue(it) }
        }
    }
}

@Composable
private fun OverlayHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.diagnostics_overlay_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        TextButton(onClick = onClose) {
            Text(stringResource(R.string.close_diagnostics_overlay))
        }
    }
}

@Composable
private fun OverlaySection(
    title: String,
    content: @Composable () -> Unit,
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
    content()
}

@Composable
private fun MetricRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.44f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.56f),
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LatestIssue(issue: DiagnosticIssue) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text(
        text =
            stringResource(
                if (issue.kind == DiagnosticIssueKind.PLAYBACK) {
                    R.string.latest_playback_error
                } else {
                    R.string.latest_load_error
                },
            ),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.error,
        fontWeight = FontWeight.SemiBold,
    )
    MetricRow(stringResource(R.string.error_code), issue.code, MaterialTheme.colorScheme.error)
    MetricRow(stringResource(R.string.error_message), issue.message.orUnknown(), MaterialTheme.colorScheme.error)
    if (issue.isFatal) {
        Text(
            text = stringResource(R.string.fatal_error),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun PlayerState?.displayName(): String =
    when (this) {
        PlayerState.IDLE -> "Idle"
        PlayerState.BUFFERING -> "Buffering"
        PlayerState.READY -> "Ready"
        PlayerState.ENDED -> "Ended"
        null -> UNKNOWN_VALUE
    }

private fun CodecImplementationType?.displayName(): String =
    when (this) {
        CodecImplementationType.HARDWARE_ACCELERATED -> "Hardware accelerated"
        CodecImplementationType.SOFTWARE_ONLY -> "Software only"
        CodecImplementationType.UNKNOWN, null -> UNKNOWN_VALUE
    }

private fun DrmScheme?.displayName(hasSession: Boolean): String =
    when (this) {
        DrmScheme.WIDEVINE -> "Widevine"
        DrmScheme.FAIRPLAY -> "FairPlay"
        DrmScheme.PLAYREADY -> "PlayReady"
        DrmScheme.OTHER -> "Other"
        null -> if (hasSession) "Clear content" else UNKNOWN_VALUE
    }

private fun DrmSessionState.displayName(): String = name.displayName()

private fun DrmKeyState?.displayName(): String = this?.name.displayName()

private fun String?.displayName(): String =
    this
        ?.lowercase(Locale.ROOT)
        ?.replace('_', ' ')
        ?.replaceFirstChar(Char::uppercase)
        ?: UNKNOWN_VALUE

private fun String?.orUnknown(): String = this?.takeIf(String::isNotBlank) ?: UNKNOWN_VALUE

private fun Long?.formatDuration(): String = this?.let { String.format(Locale.ROOT, "%.2f s", it / 1_000.0) } ?: UNKNOWN_VALUE

private fun Long?.formatBitrate(): String =
    when {
        this == null -> UNKNOWN_VALUE
        this >= 1_000_000 -> String.format(Locale.ROOT, "%.2f Mbps", this / 1_000_000.0)
        this >= 1_000 -> String.format(Locale.ROOT, "%.0f kbps", this / 1_000.0)
        else -> "$this bps"
    }

private fun Long?.formatExpiration(): String =
    this?.let {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(it))
    } ?: UNKNOWN_VALUE

private fun formatResolution(
    width: Int?,
    height: Int?,
): String = if (width != null && height != null) "$width×$height" else UNKNOWN_VALUE

private fun formatHdcp(
    currentLevel: String?,
    maximumLevel: String?,
): String =
    when {
        currentLevel != null && maximumLevel != null -> "$currentLevel / max $maximumLevel"
        currentLevel != null -> currentLevel
        maximumLevel != null -> "max $maximumLevel"
        else -> UNKNOWN_VALUE
    }

private fun formatDrmRequest(state: DiagnosticsOverlayState): String {
    val requestKind = state.drmRequestKind?.name.displayName()
    if (requestKind == UNKNOWN_VALUE) return UNKNOWN_VALUE
    val licenseType = state.drmLicenseRequestType?.name?.displayName()
    val result =
        when (state.drmRequestSuccessful) {
            true -> "Succeeded"
            false -> "Failed"
            null -> null
        }
    return listOfNotNull(requestKind, licenseType, result).joinToString(" · ")
}

private const val UNKNOWN_VALUE = "—"

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun DiagnosticsOverlayScreenPreview() {
    MaterialTheme {
        DiagnosticsOverlayScreen(
            state =
                DiagnosticsOverlayState(
                    isVisible = true,
                    sessionId = "preview-playback-session",
                    playerState = PlayerState.READY,
                    timeToFirstFrameMs = 812,
                    videoWidth = 1920,
                    videoHeight = 1080,
                    videoBitrateBps = 5_200_000,
                    videoCodecs = "avc1.640028",
                    decoderName = "c2.qti.avc.decoder",
                    decoderImplementationType = CodecImplementationType.HARDWARE_ACCELERATED,
                    framewrightBandwidthBps = 8_400_000,
                    media3BandwidthBps = 8_900_000,
                    lastObservedBufferMs = 12_600,
                    rebufferCount = 1,
                    totalRebufferDurationMs = 740,
                    drmScheme = DrmScheme.WIDEVINE,
                    drmSessionState = DrmSessionState.OPENED_WITH_KEYS,
                    drmKeyState = DrmKeyState.USABLE,
                    drmSecurityLevel = "L1",
                    drmHdcpLevel = "HDCP_V2_2",
                    drmMaxHdcpLevel = "HDCP_V2_3",
                    droppedFrames = 3,
                ),
            onAction = {},
        )
    }
}
