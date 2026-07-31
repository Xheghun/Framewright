package com.xheghun.player_core

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.xheghun.analytics.TrackType
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.xheghun.analytics.AbstractPlayerEventSource
import com.xheghun.analytics.DiagnosticEvent
import com.xheghun.analytics.DiagnosticEventBus
import com.xheghun.analytics.LoadErrorClass
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID

/**
 * Translates Media3's AnalyticsListener callbacks into DiagnosticEvent instances and
 * publishes them to a DiagnosticEventBus. This is the ONLY class in the codebase that
 * imports both androidx.media3.* and com.xheghun.framewright.analytics.* — analytics
 * itself has zero Media3 dependency by design, and that boundary only holds if
 * translation stays confined to this one adapter.
 *
 * Deliberately NOT emitted here:
 * - TRACK_SWITCH: emitting this from AnalyticsListener alone would mean guessing at
 *   `reason` (bandwidth vs buffer-health vs manual override), since ExoPlayer's default
 *   TrackSelector doesn't expose why it switched. The bandwidth-monitor module's custom
 *   TrackSelector is the correct source for this event — see EDD §4.3.
 * - DRM_KEY_STATUS: owned by drm-inspector, which wires DrmSessionManager directly.
 * - BANDWIDTH_SAMPLE: owned by bandwidth-monitor's custom BandwidthMeter.
 *
 * NOTE ON MEDIA3 VERSION: AnalyticsListener signatures have shifted across minor Media3
 * releases before. Verify these against whatever media3-exoplayer version is actually
 * pinned in build.gradle.kts before assuming this compiles as-is.
 */
class Media3EventAdapter(
    private val exoPlayer: ExoPlayer
) : AbstractPlayerEventSource() {

    private var bus: DiagnosticEventBus? = null
    private var sessionId: String = ""

    private var prepareStartMs: Long = 0L
    private var hasRenderedFirstFrame = false
    private var rebufferStartMs: Long? = null

    /** Call immediately before ExoPlayer.prepare() so TTFF is measured from the right instant. */
    fun markPrepareStart() {
        prepareStartMs = System.currentTimeMillis()
        hasRenderedFirstFrame = false
        rebufferStartMs = null
    }

    private fun publish(event: DiagnosticEvent) {
        bus?.tryPublish(event)
    }

    private fun newEventId(): String = UUID.randomUUID().toString()

    private val listener = @UnstableApi object : AnalyticsListener {

        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long
        ) {
            hasRenderedFirstFrame = true
            publish(
                DiagnosticEvent.RenderFirstFrame(
                    sessionId = sessionId,
                    eventId = newEventId(),
                    timestampMs = System.currentTimeMillis(),
                    elapsedSincePrepareMs = System.currentTimeMillis() - prepareStartMs
                )
            )
        }

        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            // Only treat STATE_BUFFERING as a rebuffer if it happens AFTER the first frame —
            // initial buffering before first frame is TTFF's concern, not rebuffering's
            // (EDD §10: rebuffer ratio excludes initial buffering).
            when (state) {
                Player.STATE_BUFFERING -> {
                    if (hasRenderedFirstFrame && rebufferStartMs == null) {
                        rebufferStartMs = System.currentTimeMillis()
                        publish(
                            DiagnosticEvent.RebufferStart(
                                sessionId = sessionId,
                                eventId = newEventId(),
                                timestampMs = System.currentTimeMillis(),
                                bufferedMsAtStart = exoPlayer.bufferedPosition - exoPlayer.currentPosition
                            )
                        )
                    }
                }

                Player.STATE_READY -> {
                    val startedAt = rebufferStartMs
                    if (startedAt != null) {
                        rebufferStartMs = null
                        publish(
                            DiagnosticEvent.RebufferEnd(
                                sessionId = sessionId,
                                eventId = newEventId(),
                                timestampMs = System.currentTimeMillis(),
                                durationMs = System.currentTimeMillis() - startedAt
                            )
                        )
                    }
                }
            }
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long
        ) {
            publish(
                DiagnosticEvent.DroppedFrames(
                    sessionId = sessionId,
                    eventId = newEventId(),
                    timestampMs = System.currentTimeMillis(),
                    count = droppedFrames,
                    elapsedMs = elapsedMs
                )
            )
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            publish(decoderInitEvent(decoderName, initializationDurationMs, TrackType.VIDEO))
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            publish(decoderInitEvent(decoderName, initializationDurationMs, TrackType.VIDEO))
        }

        private fun decoderInitEvent(
            decoderName: String,
            initDurationMs: Long,
            trackType: TrackType
        ): DiagnosticEvent.DecoderInit = DiagnosticEvent.DecoderInit(
            sessionId = sessionId,
            eventId = newEventId(),
            timestampMs = System.currentTimeMillis(),
            codecName = decoderName,
            mimeType = "", // TODO: not exposed on this callback; cross-reference with
            // codec-inspector's MediaCodecList lookup once that module exists.
            initDurationMs = initDurationMs,
            hardwareAccelerated = false, // TODO: Media3 doesn't expose this here directly;
            // codec-inspector's CodecCapabilities lookup by
            // decoderName is the real source of truth.
            trackType = trackType,
            decoderName = "",
            isHardwareAccelerated = true
        )

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean
        ) {
            publish(
                DiagnosticEvent.LoadError(
                    sessionId = sessionId,
                    eventId = newEventId(),
                    timestampMs = System.currentTimeMillis(),
                    uri = loadEventInfo.uri.toString(),
                    httpStatus = extractHttpStatus(error),
                    errorClass = classifyLoadError(error).name,
                    // Real retry counting needs a custom LoadErrorHandlingPolicy (EDD §3.1) —
                    // this adapter sees each attempt independently and has no cross-attempt
                    // state. Leaving at 0 rather than fabricating a number.
                    retryCount = 0,
                    errorMessage = error.message,
                    wasCanceled = false
                )
            )
        }

        override fun onPlayerError(
            eventTime: AnalyticsListener.EventTime,
            error: PlaybackException
        ) {
            publish(
                DiagnosticEvent.PlaybackError(
                    sessionId = sessionId,
                    eventId = newEventId(),
                    timestampMs = System.currentTimeMillis(),
                    errorCode = error.errorCodeName,
                    cause = error.cause?.message,
                    isFatal = true
                )
            )
        }
    }

    private fun classifyLoadError(error: IOException): LoadErrorClass = when {
        error is SocketTimeoutException -> LoadErrorClass.TIMEOUT
        error is UnknownHostException -> LoadErrorClass.DNS
        extractHttpStatus(error)?.let { it in 400..499 } == true -> LoadErrorClass.HTTP_4XX
        extractHttpStatus(error)?.let { it in 500..599 } == true -> LoadErrorClass.HTTP_5XX
        else -> LoadErrorClass.UNKNOWN
    }

    private fun extractHttpStatus(error: IOException): Int? {
        return (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
    }

    override fun onAttach(bus: DiagnosticEventBus, sessionId: String) {
        this.bus = bus
        this.sessionId = sessionId
        exoPlayer.addAnalyticsListener(listener)
    }

    override fun onDetach() {
        exoPlayer.removeAnalyticsListener(listener)
        bus = null
    }
}