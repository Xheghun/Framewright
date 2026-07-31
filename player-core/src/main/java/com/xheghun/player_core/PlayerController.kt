package com.xheghun.player_core

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.xheghun.analytics.DiagnosticEvent
import com.xheghun.analytics.DiagnosticEventBus
import com.xheghun.analytics.DrmScheme
import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID

/**
 * Public contract for playback. UI code and diagnostics code depend on this interface,
 * never on ExoPlayer directly — that's what lets media-lab substitute a fixture-driven
 * fake implementation for reproducible bug scenarios without touching a single UI class.
 */
interface FramewrightPlayerController {
    val events: SharedFlow<DiagnosticEvent>

    fun prepare(
        mediaUri: String,
        drmScheme: DrmScheme? = null,
    )

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun release()
}

class FramewrightExoPlayerController(
    private val context: Context,
) : FramewrightPlayerController {
    private val bus =
        DiagnosticEventBus(
            onEventDropped = { event ->
                // TODO: route to a real logger once app is implemented;
                Log.w("Framewright", "Dropped diagnostic event: ${event.eventId}")
            },
        )

    override val events = bus.events

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()
    private val adapter = FrameWrightMedia3EventAdapter(exoPlayer)

    private var currentSessionId: String? = null

    override fun prepare(
        mediaUri: String,
        drmScheme: DrmScheme?,
    ) {
        // Tear down any prior session cleanly before starting a new one — AbstractPlayerEventSource
        // enforces this at the adapter level (attach() while attached throws), so we must detach
        // first rather than relying on prepare() being "the first call ever."
        currentSessionId?.let { previousSessionId ->
            adapter.detach()
            bus.tryPublish(
                DiagnosticEvent.SessionEnd(
                    sessionId = previousSessionId,
                    eventId = UUID.randomUUID().toString(),
                    timestampMs = System.currentTimeMillis(),
                    // reason = com.xheghun.analytics.SessionEndReason.USER_STOPPED,
                    durationMs = 0,
                ),
            )
        }

        val sessionId = UUID.randomUUID().toString()
        currentSessionId = sessionId

        bus.tryPublish(
            DiagnosticEvent.SessionStart(
                sessionId = sessionId,
                eventId = UUID.randomUUID().toString(),
                timestampMs = System.currentTimeMillis(),
                mediaUri = mediaUri,
                drmScheme = drmScheme,
                deviceModel = Build.MODEL,
                osVersion = Build.VERSION.RELEASE,
                appVersion = appVersionName(),
            ),
        )

        adapter.attach(bus, sessionId)
        adapter.markPrepareStart()
        exoPlayer.setMediaItem(MediaItem.fromUri(mediaUri))
        exoPlayer.prepare()
    }

    override fun play() = exoPlayer.play()

    override fun pause() = exoPlayer.pause()

    override fun seekTo(positionMs: Long) = exoPlayer.seekTo(positionMs)

    override fun release() {
        currentSessionId?.let { sessionId ->
            bus.tryPublish(
                DiagnosticEvent.SessionEnd(
                    sessionId = sessionId,
                    eventId = UUID.randomUUID().toString(),
                    timestampMs = System.currentTimeMillis(),
                    durationMs = 0,
                    // reason = com.xheghun.analytics.SessionEndReason.APP_BACKGROUNDED
                ),
            )
        }
        adapter.detach()
        exoPlayer.release()
    }

    private fun appVersionName(): String =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

    /** Exposed for the diagnostics overlay's PlayerView binding — see the boundary-exception note above. */
    fun currentExoPlayer(): ExoPlayer = exoPlayer
}
