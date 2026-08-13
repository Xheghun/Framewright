package com.xheghun.framewright.media3

import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.xheghun.analytics.AbstractPlayerEventSource
import com.xheghun.analytics.DiagnosticEvent
import com.xheghun.analytics.DiagnosticEventMetadata
import com.xheghun.analytics.DiagnosticEventPipeline
import com.xheghun.analytics.LoadErrorClass
import com.xheghun.analytics.SessionEndReason
import com.xheghun.analytics.TrackType
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@UnstableApi
internal class FramewrightMedia3EventAdapter(
    private val player: Media3PlayerBridge,
    private val clock: FramewrightClock,
    private val eventIdGenerator: () -> String,
    private val onTerminalState: (SessionEndReason) -> Unit,
    private val decoderAccelerationResolver: (String) -> Boolean = ::isHardwareAccelerated,
) : AbstractPlayerEventSource() {
    private var pipeline: DiagnosticEventPipeline? = null
    private var sessionId = ""
    private var prepareStartedAtMs = 0L
    private var hasRenderedFirstFrame = false
    private var rebufferStartedAtMs: Long? = null
    private var videoMimeType = "unknown"
    private var audioMimeType = "unknown"

    fun markPrepareStart() {
        prepareStartedAtMs = clock.elapsedRealtimeMs()
        hasRenderedFirstFrame = false
        rebufferStartedAtMs = null
        videoMimeType = "unknown"
        audioMimeType = "unknown"
    }

    fun finishOpenRebuffer() {
        val startedAt = rebufferStartedAtMs ?: return
        rebufferStartedAtMs = null
        publish(
            DiagnosticEvent.RebufferEnd(
                metadata(),
                durationMs = (clock.elapsedRealtimeMs() - startedAt).coerceAtLeast(0),
            ),
        )
    }

    private val listener =
        object : AnalyticsListener {
            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long,
            ) {
                handleRenderedFirstFrame()
            }

            override fun onPlaybackStateChanged(
                eventTime: AnalyticsListener.EventTime,
                state: Int,
            ) {
                handlePlaybackStateChanged(state)
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                handleDroppedFrames(droppedFrames, elapsedMs)
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                handleInputFormatChanged(TrackType.VIDEO, format.sampleMimeType)
            }

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                handleInputFormatChanged(TrackType.AUDIO, format.sampleMimeType)
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                handleDecoderInitialized(decoderName, TrackType.VIDEO, initializationDurationMs)
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                handleDecoderInitialized(decoderName, TrackType.AUDIO, initializationDurationMs)
            }

            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: IOException,
                wasCanceled: Boolean,
            ) {
                handleLoadError(loadEventInfo.uri.toString(), error, wasCanceled)
            }

            override fun onPlayerError(
                eventTime: AnalyticsListener.EventTime,
                error: PlaybackException,
            ) {
                handlePlayerError(error.errorCodeName, error.message, error.cause?.message)
            }
        }

    internal fun handleRenderedFirstFrame() {
        if (hasRenderedFirstFrame) return
        hasRenderedFirstFrame = true
        publish(
            DiagnosticEvent.RenderFirstFrame(
                metadata(),
                elapsedSincePrepareMs = (clock.elapsedRealtimeMs() - prepareStartedAtMs).coerceAtLeast(0),
            ),
        )
    }

    internal fun handlePlaybackStateChanged(state: Int) {
        when (state) {
            Player.STATE_BUFFERING -> startRebufferIfNeeded()
            Player.STATE_READY -> finishOpenRebuffer()
            Player.STATE_ENDED -> {
                finishOpenRebuffer()
                onTerminalState(SessionEndReason.PLAYBACK_ENDED)
            }
        }
    }

    internal fun handleDroppedFrames(
        droppedFrames: Int,
        elapsedMs: Long,
    ) {
        publish(DiagnosticEvent.DroppedFrames(metadata(), droppedFrames, elapsedMs))
    }

    internal fun handleInputFormatChanged(
        trackType: TrackType,
        mimeType: String?,
    ) {
        when (trackType) {
            TrackType.VIDEO -> videoMimeType = mimeType ?: "unknown"
            TrackType.AUDIO -> audioMimeType = mimeType ?: "unknown"
            TrackType.TEXT -> Unit
        }
    }

    internal fun handleDecoderInitialized(
        decoderName: String,
        trackType: TrackType,
        initializationDurationMs: Long,
    ) {
        val mimeType = if (trackType == TrackType.VIDEO) videoMimeType else audioMimeType
        publish(decoderEvent(decoderName, mimeType, trackType, initializationDurationMs))
    }

    internal fun handleLoadError(
        uri: String,
        error: IOException,
        wasCanceled: Boolean,
    ) {
        publish(
            DiagnosticEvent.LoadError(
                metadata = metadata(),
                uri = uri,
                httpStatus = extractHttpStatus(error),
                errorClass = classifyLoadError(error),
                retryCount = 0,
                wasCanceled = wasCanceled,
                errorMessage = error.message,
            ),
        )
    }

    internal fun handlePlayerError(
        errorCode: String,
        errorMessage: String?,
        cause: String?,
    ) {
        finishOpenRebuffer()
        publish(
            DiagnosticEvent.PlaybackError(
                metadata = metadata(),
                errorCode = errorCode,
                errorMessage = errorMessage,
                cause = cause,
                isFatal = true,
            ),
        )
        onTerminalState(SessionEndReason.ERROR)
    }

    private fun startRebufferIfNeeded() {
        if (!hasRenderedFirstFrame || rebufferStartedAtMs != null) return
        rebufferStartedAtMs = clock.elapsedRealtimeMs()
        publish(
            DiagnosticEvent.RebufferStart(
                metadata(),
                bufferedMsAtStart = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0),
            ),
        )
    }

    private fun decoderEvent(
        decoderName: String,
        mimeType: String,
        trackType: TrackType,
        initializationDurationMs: Long,
    ) = DiagnosticEvent.DecoderInit(
        metadata = metadata(),
        decoderName = decoderName,
        mimeType = mimeType,
        trackType = trackType,
        initializationDurationMs = initializationDurationMs,
        isHardwareAccelerated = decoderAccelerationResolver(decoderName),
    )

    private fun metadata() =
        DiagnosticEventMetadata(
            sessionId = sessionId,
            eventId = eventIdGenerator(),
            timestampMs = clock.wallTimeMs(),
            elapsedRealtimeMs = clock.elapsedRealtimeMs(),
            playerState = player.playerState.toAnalyticsPlayerState(),
        )

    private fun publish(event: DiagnosticEvent) {
        pipeline?.tryPublish(event)
    }

    override fun onAttach(
        pipeline: DiagnosticEventPipeline,
        sessionId: String,
    ) {
        this.pipeline = pipeline
        this.sessionId = sessionId
        player.addAnalyticsListener(listener)
    }

    override fun onDetach() {
        player.removeAnalyticsListener(listener)
        pipeline = null
    }

    private fun classifyLoadError(error: IOException): LoadErrorClass =
        when {
            error is SocketTimeoutException -> LoadErrorClass.TIMEOUT
            error is UnknownHostException -> LoadErrorClass.DNS
            error is ConnectException || error is NoRouteToHostException -> LoadErrorClass.CONNECTION
            extractHttpStatus(error)?.let { it in 400..499 } == true -> LoadErrorClass.HTTP_4XX
            extractHttpStatus(error)?.let { it in 500..599 } == true -> LoadErrorClass.HTTP_5XX
            else -> LoadErrorClass.UNKNOWN
        }

    private fun extractHttpStatus(error: IOException): Int? = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
}

private fun isHardwareAccelerated(decoderName: String): Boolean {
    val codec = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { it.name == decoderName }
    return when {
        codec == null -> false
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> codec.isHardwareAccelerated
        else ->
            !decoderName.startsWith("OMX.google.", ignoreCase = true) &&
                !decoderName.startsWith("c2.android.", ignoreCase = true)
    }
}
