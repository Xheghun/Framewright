package com.xheghun.framewright.media3

import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
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
import com.xheghun.analytics.FormatSnapshot
import com.xheghun.analytics.LoadErrorClass
import com.xheghun.analytics.SessionEndReason
import com.xheghun.analytics.TrackSwitchReason
import com.xheghun.analytics.TrackType
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private const val SEEK_BUFFERING_CALLBACK_WINDOW_MS = 1_000L

@UnstableApi
internal class FramewrightMedia3EventAdapter(
    private val player: Media3PlayerBridge,
    private val clock: FramewrightClock,
    private val eventIdGenerator: () -> String,
    private val onTerminalState: (SessionEndReason) -> Unit,
    private val uriSanitizer: (String) -> String = { it },
    private val includeErrorMessages: Boolean = false,
    private val onDiagnosticsError: (Throwable) -> Unit = {},
    private val decoderAccelerationResolver: (String) -> Boolean? = ::isHardwareAccelerated,
) : AbstractPlayerEventSource() {
    private var pipeline: DiagnosticEventPipeline? = null
    private var sessionId = ""
    private var prepareStartedAtMs = 0L
    private var hasRenderedFirstFrame = false
    private var rebufferStartedAtMs: Long? = null
    private var videoMimeType = "unknown"
    private var audioMimeType = "unknown"
    private var seekStartedAtMs: Long? = null
    private var selectedVideoFormat: FormatSnapshot? = null
    private var availableVideoFormats: List<FormatSnapshot> = emptyList()
    private var latestBandwidthEstimateBps = 0L
    private val retryCountByLoadTaskId = mutableMapOf<Long, Int>()

    fun markPrepareStart() {
        prepareStartedAtMs = clock.elapsedRealtimeMs()
        hasRenderedFirstFrame = false
        rebufferStartedAtMs = null
        videoMimeType = "unknown"
        audioMimeType = "unknown"
        seekStartedAtMs = null
        selectedVideoFormat = null
        availableVideoFormats = emptyList()
        latestBandwidthEstimateBps = 0
        retryCountByLoadTaskId.clear()
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

            override fun onPositionDiscontinuity(
                eventTime: AnalyticsListener.EventTime,
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                handlePositionDiscontinuity(reason)
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
                handleVideoInputFormatChanged(format)
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

            override fun onTracksChanged(
                eventTime: AnalyticsListener.EventTime,
                tracks: Tracks,
            ) {
                handleAvailableVideoFormats(
                    tracks.groups
                        .filter { it.type == C.TRACK_TYPE_VIDEO }
                        .flatMap { group ->
                            (0 until group.length)
                                .filter(group::isTrackSupported)
                                .map(group::getTrackFormat)
                        },
                )
            }

            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long,
            ) {
                handleBandwidthEstimate(bitrateEstimate)
            }

            override fun onDownstreamFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                mediaLoadData: MediaLoadData,
            ) {
                handleDownstreamFormatChanged(
                    mediaLoadData.trackType,
                    mediaLoadData.trackFormat,
                    mediaLoadData.trackSelectionReason,
                )
            }

            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: IOException,
                wasCanceled: Boolean,
            ) {
                handleLoadError(loadEventInfo.loadTaskId, loadEventInfo.uri.toString(), error, wasCanceled)
            }

            override fun onLoadStarted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                retryCount: Int,
            ) {
                handleLoadStarted(loadEventInfo.loadTaskId, retryCount)
            }

            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
            ) {
                handleLoadFinished(loadEventInfo.loadTaskId)
            }

            override fun onLoadCanceled(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
            ) {
                handleLoadFinished(loadEventInfo.loadTaskId)
            }

            override fun onPlayerError(
                eventTime: AnalyticsListener.EventTime,
                error: PlaybackException,
            ) {
                handlePlayerError(error.errorCodeName, error.message, error.cause?.javaClass?.name)
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
            Player.STATE_READY -> {
                seekStartedAtMs = null
                finishOpenRebuffer()
            }
            Player.STATE_ENDED -> {
                finishOpenRebuffer()
                onTerminalState(SessionEndReason.PLAYBACK_ENDED)
            }
        }
    }

    internal fun handlePositionDiscontinuity(reason: Int) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK) return
        finishOpenRebuffer()
        seekStartedAtMs = clock.elapsedRealtimeMs()
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

    internal fun handleVideoInputFormatChanged(format: Format) {
        handleInputFormatChanged(TrackType.VIDEO, format.sampleMimeType)
        val inferredReason =
            if (selectedVideoFormat == null) {
                C.SELECTION_REASON_INITIAL
            } else {
                C.SELECTION_REASON_ADAPTIVE
            }
        handleVideoFormatSelected(format, inferredReason)
    }

    internal fun handleDecoderInitialized(
        decoderName: String,
        trackType: TrackType,
        initializationDurationMs: Long,
    ) {
        val mimeType = if (trackType == TrackType.VIDEO) videoMimeType else audioMimeType
        publish(decoderEvent(decoderName, mimeType, trackType, initializationDurationMs))
    }

    internal fun handleAvailableVideoFormats(formats: List<Format>) {
        availableVideoFormats =
            formats
                .map(Format::toSnapshot)
                .distinct()
                .sortedBy(FormatSnapshot::bitrate)
    }

    internal fun handleBandwidthEstimate(bitrateEstimateBps: Long) {
        latestBandwidthEstimateBps = bitrateEstimateBps.coerceAtLeast(0)
    }

    internal fun handleDownstreamFormatChanged(
        media3TrackType: Int,
        format: Format?,
        media3SelectionReason: Int,
    ) {
        if (media3TrackType != C.TRACK_TYPE_VIDEO || format == null) return
        handleVideoFormatSelected(format, media3SelectionReason)
    }

    internal fun handleVideoFormatSelected(
        format: Format,
        media3SelectionReason: Int,
    ) {
        val nextFormat = format.toSnapshot()
        val previousFormat = selectedVideoFormat
        if (nextFormat == previousFormat) return
        selectedVideoFormat = nextFormat
        val ladder =
            (availableVideoFormats + nextFormat)
                .distinct()
                .sortedBy(FormatSnapshot::bitrate)
        availableVideoFormats = ladder
        publish(
            DiagnosticEvent.TrackSwitch(
                metadata = metadata(),
                fromFormat = previousFormat,
                toFormat = nextFormat,
                reason = trackSwitchReason(previousFormat, nextFormat, media3SelectionReason),
                estimatedBandwidthBps = latestBandwidthEstimateBps,
                bufferedDurationMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0),
                availableVideoFormats = ladder,
            ),
        )
    }

    internal fun handleLoadStarted(
        loadTaskId: Long,
        retryCount: Int,
    ) {
        retryCountByLoadTaskId[loadTaskId] = retryCount.coerceAtLeast(0)
    }

    internal fun handleLoadFinished(loadTaskId: Long) {
        retryCountByLoadTaskId.remove(loadTaskId)
    }

    internal fun handleLoadError(
        loadTaskId: Long,
        uri: String,
        error: IOException,
        wasCanceled: Boolean,
    ) {
        val retryCount = retryCountByLoadTaskId[loadTaskId] ?: 0
        if (wasCanceled) retryCountByLoadTaskId.remove(loadTaskId)
        publish(
            DiagnosticEvent.LoadError(
                metadata = metadata(),
                uri = sanitizeUri(uri),
                httpStatus = extractHttpStatus(error),
                errorClass = classifyLoadError(error),
                retryCount = retryCount,
                wasCanceled = wasCanceled,
                errorMessage = error.message.takeIf { includeErrorMessages },
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
                errorMessage = errorMessage.takeIf { includeErrorMessages },
                cause = cause,
                isFatal = true,
            ),
        )
        onTerminalState(SessionEndReason.ERROR)
    }

    private fun startRebufferIfNeeded() {
        if (!hasRenderedFirstFrame || rebufferStartedAtMs != null) return
        val seekStartedAt = seekStartedAtMs
        if (seekStartedAt != null) {
            if (clock.elapsedRealtimeMs() - seekStartedAt <= SEEK_BUFFERING_CALLBACK_WINDOW_MS) return
            seekStartedAtMs = null
        }
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
    ): DiagnosticEvent.DecoderInit {
        val isHardwareAccelerated =
            runCatching { decoderAccelerationResolver(decoderName) }
                .onFailure(::reportDiagnosticsError)
                .getOrNull()
        return DiagnosticEvent.DecoderInit(
            metadata = metadata(),
            decoderName = decoderName,
            mimeType = mimeType,
            trackType = trackType,
            initializationDurationMs = initializationDurationMs,
            isHardwareAccelerated = isHardwareAccelerated,
        )
    }

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

    private fun sanitizeUri(uri: String): String =
        runCatching { uriSanitizer(uri) }
            .onFailure(::reportDiagnosticsError)
            .getOrDefault("<redacted>")

    private fun reportDiagnosticsError(error: Throwable) {
        runCatching { onDiagnosticsError(error) }
    }

    private fun trackSwitchReason(
        previousFormat: FormatSnapshot?,
        nextFormat: FormatSnapshot,
        media3SelectionReason: Int,
    ): TrackSwitchReason =
        when {
            previousFormat == null || media3SelectionReason == C.SELECTION_REASON_INITIAL -> TrackSwitchReason.INITIAL
            media3SelectionReason == C.SELECTION_REASON_MANUAL -> TrackSwitchReason.MANUAL_OVERRIDE
            media3SelectionReason == C.SELECTION_REASON_ADAPTIVE && nextFormat.bitrate > previousFormat.bitrate ->
                TrackSwitchReason.BANDWIDTH_INCREASE
            media3SelectionReason == C.SELECTION_REASON_ADAPTIVE && nextFormat.bitrate < previousFormat.bitrate ->
                TrackSwitchReason.BANDWIDTH_DECREASE
            else -> TrackSwitchReason.UNKNOWN
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
        retryCountByLoadTaskId.clear()
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

private val hardwareAccelerationByDecoderName: Map<String, Boolean> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.associate { codec ->
        codec.name to
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                codec.isHardwareAccelerated
            } else {
                !codec.name.startsWith("OMX.google.", ignoreCase = true) &&
                    !codec.name.startsWith("c2.android.", ignoreCase = true)
            }
    }
}

private fun isHardwareAccelerated(decoderName: String): Boolean? = hardwareAccelerationByDecoderName[decoderName]

@UnstableApi
private fun Format.toSnapshot() =
    FormatSnapshot(
        width = width.takeUnless { it == Format.NO_VALUE },
        height = height.takeUnless { it == Format.NO_VALUE },
        bitrate = bitrate.takeUnless { it == Format.NO_VALUE }?.coerceAtLeast(0) ?: 0,
        mimeType = sampleMimeType,
        codecs = codecs,
    )
