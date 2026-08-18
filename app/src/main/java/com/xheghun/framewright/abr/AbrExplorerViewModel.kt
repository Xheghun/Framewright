package com.xheghun.framewright.abr

import androidx.lifecycle.ViewModel
import com.xheghun.analytics.DiagnosticEvent
import com.xheghun.analytics.FormatSnapshot
import com.xheghun.analytics.TrackSwitchReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

class AbrExplorerViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(AbrExplorerState())
    val state = mutableState.asStateFlow()

    private var sessionStartedAtElapsedMs: Long? = null

    fun onAction(action: AbrExplorerAction) {
        when (action) {
            AbrExplorerAction.ToggleVisibility -> mutableState.update { it.copy(isVisible = !it.isVisible) }
            AbrExplorerAction.Close -> mutableState.update { it.copy(isVisible = false) }
        }
    }

    fun onDiagnosticEvent(event: DiagnosticEvent) {
        when (event) {
            is DiagnosticEvent.SessionStart -> startSession(event)
            is DiagnosticEvent.BandwidthSample -> addBandwidthSample(event)
            is DiagnosticEvent.TrackSwitch -> addTrackSwitch(event)
            is DiagnosticEvent.SessionEnd -> finishSession(event)
            else -> Unit
        }
    }

    private fun startSession(event: DiagnosticEvent.SessionStart) {
        sessionStartedAtElapsedMs = event.metadata.elapsedRealtimeMs
        mutableState.update {
            AbrExplorerState(
                isVisible = it.isVisible,
                sessionId = event.metadata.sessionId,
            )
        }
    }

    private fun addBandwidthSample(event: DiagnosticEvent.BandwidthSample) {
        if (!accepts(event)) return
        val elapsedMs = relativeElapsed(event.metadata.elapsedRealtimeMs)
        val point =
            BandwidthPointUi(
                eventId = event.metadata.eventId,
                elapsedMs = elapsedMs,
                customEstimateBps = minOf(event.fastEstimateBps, event.slowEstimateBps),
                defaultEstimateBps = event.defaultEstimateBps,
                confidence = event.confidence,
            )
        mutableState.update {
            it.copy(
                elapsedDurationMs = maxOf(it.elapsedDurationMs, elapsedMs),
                bandwidthPoints = (it.bandwidthPoints + point).takeLast(MAX_BANDWIDTH_POINTS),
            )
        }
    }

    private fun addTrackSwitch(event: DiagnosticEvent.TrackSwitch) {
        if (!accepts(event)) return
        val elapsedMs = relativeElapsed(event.metadata.elapsedRealtimeMs)
        val availableFormats =
            (event.availableVideoFormats + listOfNotNull(event.fromFormat, event.toFormat))
                .distinct()
                .sortedBy(FormatSnapshot::bitrate)
        val tracks = availableFormats.map { it.toTrackUi(selected = it == event.toFormat) }
        val decision = event.toDecisionUi(elapsedMs)
        mutableState.update {
            it.copy(
                elapsedDurationMs = maxOf(it.elapsedDurationMs, elapsedMs),
                bitrateTracks = tracks,
                decisions = (it.decisions + decision).takeLast(MAX_TRACK_DECISIONS),
            )
        }
    }

    private fun finishSession(event: DiagnosticEvent.SessionEnd) {
        if (!accepts(event)) return
        mutableState.update {
            it.copy(elapsedDurationMs = maxOf(it.elapsedDurationMs, event.durationMs))
        }
    }

    private fun accepts(event: DiagnosticEvent): Boolean {
        val stateSessionId = mutableState.value.sessionId
        if (stateSessionId != null) return stateSessionId == event.metadata.sessionId
        sessionStartedAtElapsedMs = event.metadata.elapsedRealtimeMs
        mutableState.update { it.copy(sessionId = event.metadata.sessionId) }
        return true
    }

    private fun relativeElapsed(eventElapsedMs: Long): Long =
        (eventElapsedMs - (sessionStartedAtElapsedMs ?: eventElapsedMs)).coerceAtLeast(0)

    private fun FormatSnapshot.toTrackUi(selected: Boolean): BitrateTrackUi =
        BitrateTrackUi(
            id = listOf(width, height, bitrate, mimeType, codecs).joinToString(separator = ":"),
            label = formatLabel(),
            bitrateBps = bitrate,
            isSelected = selected,
        )

    private fun FormatSnapshot.formatLabel(): String {
        val resolution = if (width != null && height != null) "$width×$height" else "Unknown resolution"
        return "$resolution · ${formatBitrate(bitrate.toLong())}"
    }

    private fun DiagnosticEvent.TrackSwitch.toDecisionUi(elapsedMs: Long): AbrDecisionUi {
        val title =
            when (reason) {
                TrackSwitchReason.INITIAL -> "Initial video selection"
                TrackSwitchReason.MANUAL_OVERRIDE -> "Manual video selection"
                TrackSwitchReason.BANDWIDTH_INCREASE -> "Adaptive upshift"
                TrackSwitchReason.BANDWIDTH_DECREASE -> "Adaptive downshift"
                TrackSwitchReason.BUFFER_HEALTH -> "Buffer-health switch"
                TrackSwitchReason.UNKNOWN -> "Video selection changed"
            }
        val previous = fromFormat?.formatLabel() ?: "None"
        val details =
            "$previous → ${toFormat.formatLabel()} · estimate ${formatBitrate(estimatedBandwidthBps)} · buffer $bufferedDurationMs ms"
        return AbrDecisionUi(metadata.eventId, elapsedMs, title, details, reason)
    }

    private fun formatBitrate(bitrateBps: Long): String =
        when {
            bitrateBps >= 1_000_000 -> String.format(Locale.ROOT, "%.2f Mbps", bitrateBps / 1_000_000.0)
            bitrateBps > 0 -> String.format(Locale.ROOT, "%.0f kbps", bitrateBps / 1_000.0)
            else -> "unknown bandwidth"
        }

    private companion object {
        const val MAX_BANDWIDTH_POINTS = 600
        const val MAX_TRACK_DECISIONS = 100
    }
}
