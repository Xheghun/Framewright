package com.xheghun.framewright.media3

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
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
        assertThat(decoders.first().isHardwareAccelerated).isTrue()
        assertThat(decoders.last().isHardwareAccelerated).isFalse()
    }

    @Test
    fun `classifies network errors and preserves cancellation`() {
        attachForPreparation()
        adapter.handleLoadError("https://example.test/timeout.ts", SocketTimeoutException("timeout"), false)
        adapter.handleLoadError("https://example.test/dns.ts", UnknownHostException("dns"), false)
        adapter.handleLoadError("https://example.test/connect.ts", ConnectException("refused"), true)

        val errors = pipeline.snapshot("playback-session").events.filterIsInstance<DiagnosticEvent.LoadError>()
        assertThat(errors.map { it.errorClass })
            .containsExactly(LoadErrorClass.TIMEOUT, LoadErrorClass.DNS, LoadErrorClass.CONNECTION)
        assertThat(errors.last().wasCanceled).isTrue()
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
