package com.xheghun.framewright.media3

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.xheghun.analytics.AbstractPlayerEventSource
import com.xheghun.analytics.CodecResult
import com.xheghun.analytics.DiagnosticEvent
import com.xheghun.analytics.DiagnosticEventPipeline
import com.xheghun.analytics.PlayerEventSource
import com.xheghun.analytics.SessionEndReason
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class Media3DiagnosticsSessionTest {
    private val player = FakePlayerBridge()
    private val clock = FakeClock()

    @Test
    fun `tracked prepare records lifecycle and returns host result`() {
        val diagnostics = createDiagnostics()

        val preparationResult =
            diagnostics.trackPrepare(MediaSessionInfo("https://example.test/master.m3u8")) {
                "host-prepare-result"
            }
        clock.elapsedTime = 1_100
        diagnostics.endSession(SessionEndReason.USER_STOPPED)

        val snapshot = requireNotNull(diagnostics.currentSnapshot())
        assertThat(preparationResult).isEqualTo("host-prepare-result")
        assertThat(snapshot.session.events.map { it.type.name })
            .containsExactly("SESSION_START", "SESSION_END")
        val sessionEnd = snapshot.session.events.last() as DiagnosticEvent.SessionEnd
        assertThat(sessionEnd.durationMs).isEqualTo(1_000)
        assertThat(sessionEnd.reason).isEqualTo(SessionEndReason.USER_STOPPED)
        assertThat(diagnostics.exportCurrentSession() is CodecResult.Success).isTrue()
    }

    @Test
    fun `new prepare replaces active session`() {
        val diagnostics = createDiagnostics()
        diagnostics.trackPrepare(MediaSessionInfo("https://example.test/first.m3u8")) {}
        val firstSessionId = requireNotNull(diagnostics.currentSnapshot()).session.sessionId

        diagnostics.trackPrepare(MediaSessionInfo("https://example.test/second.mpd")) {}

        val secondSnapshot = requireNotNull(diagnostics.currentSnapshot())
        assertThat(secondSnapshot.session.sessionId == firstSessionId).isFalse()
    }

    @Test
    fun `prepare failure records error closes session and rethrows`() {
        val diagnostics = createDiagnostics()

        val thrown =
            assertThrows<IllegalStateException> {
                diagnostics.trackPrepare(MediaSessionInfo("https://example.test/broken.mpd")) {
                    error("Media item rejected")
                }
            }

        val events = requireNotNull(diagnostics.currentSnapshot()).session.events
        assertThat(thrown.message).isEqualTo("Media item rejected")
        assertThat(events.filterIsInstance<DiagnosticEvent.PlaybackError>().single().errorCode)
            .isEqualTo("HOST_PREPARE_FAILED")
        assertThat((events.last() as DiagnosticEvent.SessionEnd).reason).isEqualTo(SessionEndReason.ERROR)
    }

    @Test
    fun `close is idempotent and rejects future preparations`() {
        val diagnostics = createDiagnostics()
        assertThat(diagnostics.currentSnapshot()).isNull()
        diagnostics.trackPrepare(MediaSessionInfo("https://example.test/video.m3u8")) {}

        diagnostics.close()
        diagnostics.close()

        val sessionEnds = requireNotNull(diagnostics.currentSnapshot()).session.events.filterIsInstance<DiagnosticEvent.SessionEnd>()
        assertThat(sessionEnds.size).isEqualTo(1)
        assertThat(sessionEnds.single().reason).isEqualTo(SessionEndReason.RELEASED)
        assertThrows<IllegalStateException> {
            diagnostics.trackPrepare(MediaSessionInfo("https://example.test/another.m3u8")) {}
        }
    }

    @Test
    fun `contributors follow each tracked session lifecycle`() {
        val contributor = RecordingContributor()
        val diagnostics = createDiagnostics(listOf(contributor))

        diagnostics.trackPrepare(MediaSessionInfo("https://example.test/video.m3u8")) {}
        diagnostics.endSession()

        assertThat(contributor.attachCount).isEqualTo(1)
        assertThat(contributor.detachCount).isEqualTo(1)
        assertThat(contributor.attachedSessionIds.size).isEqualTo(1)
    }

    @Test
    fun `diagnostics failures do not block host preparation or session termination`() {
        val reportedErrors = mutableListOf<Throwable>()
        val failingAttachContributor = FailingAttachContributor()
        val failingDetachContributor = FailingDetachContributor()
        val healthyContributor = RecordingContributor()
        val diagnostics =
            createDiagnostics(
                contributors = listOf(failingAttachContributor, failingDetachContributor, healthyContributor),
                configuration = Media3DiagnosticsConfiguration(onDiagnosticsError = reportedErrors::add),
            )

        var hostPrepareCalled = false
        diagnostics.trackPrepare(MediaSessionInfo("https://example.test/video.m3u8")) {
            hostPrepareCalled = true
        }
        diagnostics.endSession()

        val sessionEnds = requireNotNull(diagnostics.currentSnapshot()).session.events.filterIsInstance<DiagnosticEvent.SessionEnd>()
        assertThat(hostPrepareCalled).isTrue()
        assertThat(healthyContributor.detachCount).isEqualTo(1)
        assertThat(player.listener).isNull()
        assertThat(sessionEnds.size).isEqualTo(1)
        assertThat(reportedErrors.size).isEqualTo(2)
    }

    @Test
    fun `new preparation clears the previously retained session`() {
        val pipeline = DiagnosticEventPipeline()
        val diagnostics = createDiagnostics(pipeline = pipeline)
        diagnostics.trackPrepare(MediaSessionInfo("https://example.test/first.m3u8")) {}
        val firstSessionId = requireNotNull(diagnostics.currentSnapshot()).session.sessionId

        diagnostics.trackPrepare(MediaSessionInfo("https://example.test/second.m3u8")) {}

        assertThat(pipeline.snapshot(firstSessionId).events.isEmpty()).isTrue()
    }

    @Test
    fun `session start stores a redacted media URI`() {
        val diagnostics = createDiagnostics()

        diagnostics.trackPrepare(MediaSessionInfo("https://user:password@example.test/master.m3u8?token=secret#fragment")) {}

        val sessionStart =
            requireNotNull(diagnostics.currentSnapshot())
                .session.events
                .filterIsInstance<DiagnosticEvent.SessionStart>()
                .single()
        assertThat(sessionStart.mediaUri).isEqualTo("https://example.test/master.m3u8")
    }

    @Test
    fun `public operations reject calls from the wrong thread`() {
        val diagnostics =
            createSessionForTest(
                player = player,
                deviceInfo = DeviceInfo("Pixel 9", "16", "1.0"),
                clock = clock,
                eventIdGenerator = sequentialIds(),
                verifyThread = { error("Wrong player thread") },
            )

        val thrown = assertThrows<IllegalStateException> { diagnostics.currentSnapshot() }

        assertThat(thrown.message).isEqualTo("Wrong player thread")
    }

    private fun createDiagnostics(
        contributors: List<PlayerEventSource> = emptyList(),
        configuration: Media3DiagnosticsConfiguration = Media3DiagnosticsConfiguration(),
        pipeline: DiagnosticEventPipeline = DiagnosticEventPipeline(),
    ): Media3DiagnosticsSession =
        createSessionForTest(
            player = player,
            deviceInfo = DeviceInfo("Pixel 9", "16", "1.0"),
            contributors = contributors,
            configuration = configuration,
            clock = clock,
            eventIdGenerator = sequentialIds(),
            pipeline = pipeline,
        )

    private class RecordingContributor : AbstractPlayerEventSource() {
        var attachCount = 0
        var detachCount = 0
        val attachedSessionIds = mutableListOf<String>()

        override fun onAttach(
            pipeline: DiagnosticEventPipeline,
            sessionId: String,
        ) {
            attachCount++
            attachedSessionIds += sessionId
        }

        override fun onDetach() {
            detachCount++
        }
    }

    private class FailingAttachContributor : AbstractPlayerEventSource() {
        override fun onAttach(
            pipeline: DiagnosticEventPipeline,
            sessionId: String,
        ) {
            error("Contributor attachment failed")
        }

        override fun onDetach() = Unit
    }

    private class FailingDetachContributor : AbstractPlayerEventSource() {
        override fun onAttach(
            pipeline: DiagnosticEventPipeline,
            sessionId: String,
        ) = Unit

        override fun onDetach() {
            error("Contributor detachment failed")
        }
    }
}
