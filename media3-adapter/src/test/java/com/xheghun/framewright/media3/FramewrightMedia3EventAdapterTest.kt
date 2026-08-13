package com.xheghun.framewright.media3

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.xheghun.analytics.DiagnosticEvent
import com.xheghun.analytics.DiagnosticEventPipeline
import com.xheghun.analytics.LoadErrorClass
import com.xheghun.analytics.SessionEndReason
import com.xheghun.analytics.TrackType
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@UnstableApi
class FramewrightMedia3EventAdapterTest {
    private val player = FakePlayerBridge()
    private val clock = FakeClock()
    private val pipeline = DiagnosticEventPipeline()
    private val terminalReasons = mutableListOf<SessionEndReason>()
    private val adapter =
        FramewrightMedia3EventAdapter(
            player = player,
            clock = clock,
            eventIdGenerator = sequentialIds(),
            onTerminalState = terminalReasons::add,
            decoderAccelerationResolver = { decoderName -> decoderName.startsWith("c2.qti") },
        )

    @Test
    fun `emits one first frame and excludes startup buffering`() {
        attachForPreparation()
        adapter.handlePlaybackStateChanged(Player.STATE_BUFFERING)
        clock.elapsedTime = 550
        adapter.handleRenderedFirstFrame()
        adapter.handleRenderedFirstFrame()

        val events = pipeline.snapshot("playback-session").events
        assertThat(events.filterIsInstance<DiagnosticEvent.RebufferStart>()).isEmpty()
        assertThat(events.filterIsInstance<DiagnosticEvent.RenderFirstFrame>().single().elapsedSincePrepareMs)
            .isEqualTo(450)
    }

    @Test
    fun `records rebuffer after first frame and closes it when ready`() {
        attachForPreparation()
        adapter.handleRenderedFirstFrame()
        player.currentPosition = 1_000
        player.bufferedPosition = 900
        clock.elapsedTime = 200
        adapter.handlePlaybackStateChanged(Player.STATE_BUFFERING)
        clock.elapsedTime = 700
        adapter.handlePlaybackStateChanged(Player.STATE_READY)

        val events = pipeline.snapshot("playback-session").events
        assertThat(events.filterIsInstance<DiagnosticEvent.RebufferStart>().single().bufferedMsAtStart).isEqualTo(0)
        assertThat(events.filterIsInstance<DiagnosticEvent.RebufferEnd>().single().durationMs).isEqualTo(500)
    }

    @Test
    fun `maps audio and video decoder metadata independently`() {
        attachForPreparation()
        adapter.handleInputFormatChanged(TrackType.VIDEO, "video/avc")
        adapter.handleInputFormatChanged(TrackType.AUDIO, "audio/mp4a-latm")
        adapter.handleDecoderInitialized("c2.qti.avc.decoder", TrackType.VIDEO, 20)
        adapter.handleDecoderInitialized("c2.android.aac.decoder", TrackType.AUDIO, 8)

        val decoders = pipeline.snapshot("playback-session").events.filterIsInstance<DiagnosticEvent.DecoderInit>()
        assertThat(decoders.map { it.trackType }).containsExactly(TrackType.VIDEO, TrackType.AUDIO)
        assertThat(decoders.map { it.mimeType }).containsExactly("video/avc", "audio/mp4a-latm")
        assertThat(decoders.first().isHardwareAccelerated).isEqualTo(true)
        assertThat(decoders.last().isHardwareAccelerated).isEqualTo(false)
    }

    @Test
    fun `codec lookup failure reports diagnostics error and records unknown acceleration`() {
        val reportedErrors = mutableListOf<Throwable>()
        val failingCodecAdapter =
            FramewrightMedia3EventAdapter(
                player = player,
                clock = clock,
                eventIdGenerator = sequentialIds(),
                onTerminalState = terminalReasons::add,
                onDiagnosticsError = reportedErrors::add,
                decoderAccelerationResolver = { error("Codec service unavailable") },
            )
        failingCodecAdapter.attach(pipeline, "playback-session")
        failingCodecAdapter.markPrepareStart()

        failingCodecAdapter.handleDecoderInitialized("vendor.decoder", TrackType.VIDEO, 10)

        val decoder =
            pipeline
                .snapshot("playback-session")
                .events
                .filterIsInstance<DiagnosticEvent.DecoderInit>()
                .single()
        assertThat(decoder.isHardwareAccelerated).isEqualTo(null)
        assertThat(reportedErrors.size).isEqualTo(1)
    }

    @Test
    fun `classifies network errors and preserves cancellation`() {
        attachForPreparation()
        adapter.handleLoadError(1, "https://example.test/timeout.ts", SocketTimeoutException("timeout"), false)
        adapter.handleLoadError(2, "https://example.test/dns.ts", UnknownHostException("dns"), false)
        adapter.handleLoadError(3, "https://example.test/connect.ts", ConnectException("refused"), true)

        val errors = pipeline.snapshot("playback-session").events.filterIsInstance<DiagnosticEvent.LoadError>()
        assertThat(errors.map { it.errorClass })
            .containsExactly(LoadErrorClass.TIMEOUT, LoadErrorClass.DNS, LoadErrorClass.CONNECTION)
        assertThat(errors.last().wasCanceled).isTrue()
        assertThat(errors.map { it.errorMessage }.all { it == null }).isTrue()
    }

    @Test
    fun `records retry count supplied by load start callback`() {
        attachForPreparation()
        adapter.handleLoadStarted(loadTaskId = 42, retryCount = 3)

        adapter.handleLoadError(42, "https://example.test/segment.ts", SocketTimeoutException("timeout"), false)

        val error =
            pipeline
                .snapshot("playback-session")
                .events
                .filterIsInstance<DiagnosticEvent.LoadError>()
                .single()
        assertThat(error.retryCount).isEqualTo(3)
    }

    @Test
    fun `does not count buffering caused by a seek as rebuffering`() {
        attachForPreparation()
        adapter.handleRenderedFirstFrame()

        adapter.handlePositionDiscontinuity(Player.DISCONTINUITY_REASON_SEEK)
        adapter.handlePlaybackStateChanged(Player.STATE_BUFFERING)
        adapter.handlePlaybackStateChanged(Player.STATE_READY)

        val events = pipeline.snapshot("playback-session").events
        assertThat(events.filterIsInstance<DiagnosticEvent.RebufferStart>()).isEmpty()
    }

    @Test
    fun `buffered seek does not suppress a later playback rebuffer`() {
        attachForPreparation()
        adapter.handleRenderedFirstFrame()
        player.playerState = Player.STATE_READY

        adapter.handlePositionDiscontinuity(Player.DISCONTINUITY_REASON_SEEK)
        clock.elapsedTime += 1_001
        adapter.handlePlaybackStateChanged(Player.STATE_BUFFERING)

        val rebuffers = pipeline.snapshot("playback-session").events.filterIsInstance<DiagnosticEvent.RebufferStart>()
        assertThat(rebuffers.size).isEqualTo(1)
    }

    @Test
    fun `records dropped frames and playback completion`() {
        attachForPreparation()

        adapter.handleDroppedFrames(droppedFrames = 7, elapsedMs = 1_000)
        adapter.handlePlaybackStateChanged(Player.STATE_ENDED)

        val droppedFrames =
            pipeline
                .snapshot("playback-session")
                .events
                .filterIsInstance<DiagnosticEvent.DroppedFrames>()
                .single()
        assertThat(droppedFrames.count).isEqualTo(7)
        assertThat(terminalReasons).containsExactly(SessionEndReason.PLAYBACK_ENDED)
    }

    @Test
    fun `fatal player error closes rebuffer before terminal callback`() {
        attachForPreparation()
        adapter.handleRenderedFirstFrame()
        adapter.handlePlaybackStateChanged(Player.STATE_BUFFERING)
        clock.elapsedTime = 400

        adapter.handlePlayerError("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", "Network failed", "timeout")

        val events = pipeline.snapshot("playback-session").events
        assertThat(events.takeLast(2).map { it.type.name }).containsExactly("REBUFFER_END", "PLAYBACK_ERROR")
        assertThat(terminalReasons).containsExactly(SessionEndReason.ERROR)
    }

    private fun attachForPreparation() {
        adapter.attach(pipeline, "playback-session")
        adapter.markPrepareStart()
    }
}
