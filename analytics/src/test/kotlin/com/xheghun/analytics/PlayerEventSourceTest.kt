package com.xheghun.analytics

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PlayerEventSourceTest {
    private class RecordingPlayerEventSource(
        var shouldFailOnAttach: Boolean = false,
    ) : AbstractPlayerEventSource() {
        var attachCallCount = 0
        var detachCallCount = 0

        override fun onAttach(
            pipeline: DiagnosticEventPipeline,
            sessionId: String,
        ) {
            if (shouldFailOnAttach) error("Simulated player event source attachment failure")
            attachCallCount++
        }

        override fun onDetach() {
            detachCallCount++
        }
    }

    @Test
    fun `guards attach and supports idempotent detach and reattach`() {
        val eventSource = RecordingPlayerEventSource()
        val pipeline = DiagnosticEventPipeline()
        val initialSessionId = "initial-playback-session"
        val replacementSessionId = "replacement-playback-session"
        eventSource.attach(pipeline, initialSessionId)

        assertThrows<IllegalStateException> { eventSource.attach(pipeline, initialSessionId) }
        eventSource.detach()
        eventSource.detach()
        eventSource.attach(pipeline, replacementSessionId)
        eventSource.detach()

        assertThat(eventSource.attachCallCount).isEqualTo(2)
        assertThat(eventSource.detachCallCount).isEqualTo(2)
    }

    @Test
    fun `failed attach rolls lifecycle state back`() {
        val eventSource = RecordingPlayerEventSource(shouldFailOnAttach = true)
        val pipeline = DiagnosticEventPipeline()
        val playbackSessionId = "playback-session-after-attachment-retry"
        assertThrows<IllegalStateException> { eventSource.attach(pipeline, playbackSessionId) }

        eventSource.shouldFailOnAttach = false
        eventSource.attach(pipeline, playbackSessionId)

        assertThat(eventSource.attachCallCount).isEqualTo(1)
    }
}
