package com.xheghun.analytics

data class SessionSummary(
    val sessionId: String,
    val timeToFirstFrameMs: Long?,
    val rebufferCount: Int,
    val totalRebufferDurationMs: Long,
    val rebufferRatio: Double?,
    val startupFailed: Boolean,
    val trackSwitchesPerMinute: Double?,
    val averageDecoderInitializationMs: Double?,
    val droppedFrameCount: Long,
)

class SessionSummaryCalculator {
    fun calculate(snapshot: SessionSnapshot): SessionSummary {
        val events = snapshot.events
        val firstFrameIndex = events.indexOfFirst { it is DiagnosticEvent.RenderFirstFrame }
        val firstFrame = events.getOrNull(firstFrameIndex) as? DiagnosticEvent.RenderFirstFrame
        val sessionEnd = events.filterIsInstance<DiagnosticEvent.SessionEnd>().lastOrNull()
        val rebuffers =
            if (firstFrameIndex >=
                0
            ) {
                events.drop(firstFrameIndex + 1).filterIsInstance<DiagnosticEvent.RebufferEnd>()
            } else {
                emptyList()
            }
        val totalRebufferMs = rebuffers.sumOf { it.durationMs }
        val durationMs = sessionEnd?.durationMs?.takeIf { it > 0 }
        val switches = events.filterIsInstance<DiagnosticEvent.TrackSwitch>().size
        val decoderEvents = events.filterIsInstance<DiagnosticEvent.DecoderInit>()
        val startupFailed =
            events
                .take(if (firstFrameIndex >= 0) firstFrameIndex else events.size)
                .filterIsInstance<DiagnosticEvent.PlaybackError>()
                .any { it.isFatal }

        return SessionSummary(
            sessionId = snapshot.sessionId,
            timeToFirstFrameMs = firstFrame?.elapsedSincePrepareMs,
            rebufferCount = rebuffers.size,
            totalRebufferDurationMs = totalRebufferMs,
            rebufferRatio = durationMs?.let { totalRebufferMs.toDouble() / it },
            startupFailed = startupFailed,
            trackSwitchesPerMinute = durationMs?.let { switches * 60_000.0 / it },
            averageDecoderInitializationMs = decoderEvents.takeIf { it.isNotEmpty() }?.map { it.initializationDurationMs }?.average(),
            droppedFrameCount = events.filterIsInstance<DiagnosticEvent.DroppedFrames>().sumOf { it.count.toLong() },
        )
    }
}
