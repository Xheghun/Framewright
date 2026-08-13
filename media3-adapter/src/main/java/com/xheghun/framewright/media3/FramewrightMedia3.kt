package com.xheghun.framewright.media3

import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.xheghun.analytics.CodecResult
import com.xheghun.analytics.DiagnosticEvent
import com.xheghun.analytics.DiagnosticEventMetadata
import com.xheghun.analytics.DiagnosticEventPipeline
import com.xheghun.analytics.DrmScheme
import com.xheghun.analytics.PlayerEventSource
import com.xheghun.analytics.SessionEndReason
import com.xheghun.analytics.SessionSnapshot
import com.xheghun.analytics.SessionSummary
import com.xheghun.analytics.SessionSummaryCalculator
import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID

data class MediaSessionInfo(
    val mediaUri: String,
    val drmScheme: DrmScheme? = null,
    val deviceModel: String? = null,
    val osVersion: String? = null,
    val appVersion: String? = null,
) {
    init {
        require(mediaUri.isNotBlank()) { "mediaUri must not be blank" }
    }
}

data class PlaybackSnapshot(
    val session: SessionSnapshot,
    val summary: SessionSummary,
    val playerState: com.xheghun.analytics.PlayerState,
    val positionMs: Long,
    val bufferedDurationMs: Long,
)

interface Media3DiagnosticsSession : AutoCloseable {
    val events: SharedFlow<DiagnosticEvent>

    fun <T> trackPrepare(
        sessionInfo: MediaSessionInfo,
        prepare: () -> T,
    ): T

    fun endSession(reason: SessionEndReason = SessionEndReason.USER_STOPPED)

    fun currentSnapshot(): PlaybackSnapshot?

    fun exportCurrentSession(): CodecResult<String>?

    override fun close()
}

object FramewrightMedia3 {
    fun attach(
        context: Context,
        player: ExoPlayer,
        contributors: List<PlayerEventSource> = emptyList(),
    ): Media3DiagnosticsSession {
        val androidDeviceInfo = AndroidDeviceInfo(context.applicationContext)
        return DefaultMedia3DiagnosticsSession(
            player = ExoPlayerBridge(player),
            deviceInfo = androidDeviceInfo.value,
            contributors = contributors,
            clock = SystemFramewrightClock,
            eventIdGenerator = { UUID.randomUUID().toString() },
            verifyThread = {
                check(Looper.myLooper() == player.applicationLooper) {
                    "Framewright must be called on ExoPlayer's application thread"
                }
            },
        )
    }
}

internal interface FramewrightClock {
    fun wallTimeMs(): Long

    fun elapsedRealtimeMs(): Long
}

private object SystemFramewrightClock : FramewrightClock {
    override fun wallTimeMs(): Long = System.currentTimeMillis()

    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}

internal data class DeviceInfo(
    val model: String,
    val osVersion: String,
    val appVersion: String,
)

private class AndroidDeviceInfo(
    context: Context,
) {
    val value =
        DeviceInfo(
            model = Build.MODEL,
            osVersion = Build.VERSION.RELEASE,
            appVersion =
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: "unknown",
        )
}

internal fun createSessionForTest(
    player: Media3PlayerBridge,
    deviceInfo: DeviceInfo,
    contributors: List<PlayerEventSource> = emptyList(),
    clock: FramewrightClock,
    eventIdGenerator: () -> String,
    verifyThread: () -> Unit = {},
): Media3DiagnosticsSession = DefaultMedia3DiagnosticsSession(player, deviceInfo, contributors, clock, eventIdGenerator, verifyThread)

private class DefaultMedia3DiagnosticsSession(
    private val player: Media3PlayerBridge,
    private val deviceInfo: DeviceInfo,
    private val contributors: List<PlayerEventSource>,
    private val clock: FramewrightClock,
    private val eventIdGenerator: () -> String,
    private val verifyThread: () -> Unit,
) : Media3DiagnosticsSession {
    private val pipeline = DiagnosticEventPipeline()
    private val summaryCalculator = SessionSummaryCalculator()
    private var activeSessionId: String? = null
    private var lastSessionId: String? = null
    private var sessionStartedAtElapsedMs = 0L
    private var closed = false
    private val adapter =
        FramewrightMedia3EventAdapter(
            player = player,
            clock = clock,
            eventIdGenerator = eventIdGenerator,
            onTerminalState = ::endSession,
        )

    override val events: SharedFlow<DiagnosticEvent> = pipeline.events

    override fun <T> trackPrepare(
        sessionInfo: MediaSessionInfo,
        prepare: () -> T,
    ): T {
        verifyUsable()
        activeSessionId?.let { finishSession(SessionEndReason.REPLACED) }
        val sessionId = eventIdGenerator()
        activeSessionId = sessionId
        lastSessionId = sessionId
        sessionStartedAtElapsedMs = clock.elapsedRealtimeMs()
        pipeline.tryPublish(
            DiagnosticEvent.SessionStart(
                metadata(sessionId),
                mediaUri = sessionInfo.mediaUri,
                drmScheme = sessionInfo.drmScheme,
                deviceModel = sessionInfo.deviceModel ?: deviceInfo.model,
                osVersion = sessionInfo.osVersion ?: deviceInfo.osVersion,
                appVersion = sessionInfo.appVersion ?: deviceInfo.appVersion,
            ),
        )
        val sources = listOf(adapter) + contributors
        try {
            sources.forEach { it.attach(pipeline, sessionId) }
            adapter.markPrepareStart()
            return prepare()
        } catch (error: Throwable) {
            pipeline.tryPublish(
                DiagnosticEvent.PlaybackError(
                    metadata(sessionId),
                    errorCode = "HOST_PREPARE_FAILED",
                    errorMessage = error.message,
                    cause = error::class.qualifiedName,
                    isFatal = true,
                ),
            )
            finishSession(SessionEndReason.ERROR)
            throw error
        }
    }

    override fun endSession(reason: SessionEndReason) {
        verifyUsable()
        finishSession(reason)
    }

    private fun finishSession(reason: SessionEndReason) {
        val sessionId = activeSessionId ?: return
        adapter.finishOpenRebuffer()
        (listOf(adapter) + contributors).forEach { it.detach() }
        pipeline.tryPublish(
            DiagnosticEvent.SessionEnd(
                metadata(sessionId),
                durationMs = (clock.elapsedRealtimeMs() - sessionStartedAtElapsedMs).coerceAtLeast(0),
                reason = reason,
            ),
        )
        activeSessionId = null
    }

    override fun currentSnapshot(): PlaybackSnapshot? {
        verifyThread()
        val sessionId = activeSessionId ?: lastSessionId ?: return null
        val session = pipeline.snapshot(sessionId)
        return PlaybackSnapshot(
            session = session,
            summary = summaryCalculator.calculate(session),
            playerState = player.playerState.toAnalyticsPlayerState(),
            positionMs = player.currentPosition.coerceAtLeast(0),
            bufferedDurationMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0),
        )
    }

    override fun exportCurrentSession(): CodecResult<String>? {
        verifyThread()
        val sessionId = activeSessionId ?: lastSessionId ?: return null
        return pipeline.exportSessionJson(sessionId)
    }

    override fun close() {
        verifyThread()
        if (closed) return
        finishSession(SessionEndReason.RELEASED)
        closed = true
    }

    private fun verifyUsable() {
        verifyThread()
        check(!closed) { "Media3DiagnosticsSession is closed" }
    }

    private fun metadata(sessionId: String) =
        DiagnosticEventMetadata(
            sessionId = sessionId,
            eventId = eventIdGenerator(),
            timestampMs = clock.wallTimeMs(),
            elapsedRealtimeMs = clock.elapsedRealtimeMs(),
            playerState = player.playerState.toAnalyticsPlayerState(),
        )
}

internal fun Int.toAnalyticsPlayerState(): com.xheghun.analytics.PlayerState =
    when (this) {
        Player.STATE_BUFFERING -> com.xheghun.analytics.PlayerState.BUFFERING
        Player.STATE_READY -> com.xheghun.analytics.PlayerState.READY
        Player.STATE_ENDED -> com.xheghun.analytics.PlayerState.ENDED
        else -> com.xheghun.analytics.PlayerState.IDLE
    }
