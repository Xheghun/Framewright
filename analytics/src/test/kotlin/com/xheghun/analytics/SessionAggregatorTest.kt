package com.xheghun.analytics

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAggregatorTest {

    @Test
    fun `records events and exports them under the same session id`() {
        val aggregator = SessionAggregator()
        val sessionId = "session-1"

        aggregator.record(
            DiagnosticEvent.SessionStart(
                sessionId = sessionId, eventId = "e1", timestampMs = 1000L,
                mediaUri = "https://example.com/master.m3u8", drmScheme = DrmScheme.WIDEVINE,
                deviceModel = "Pixel 8", osVersion = "15", appVersion = "1.0.0"
            )
        )
        aggregator.record(
            DiagnosticEvent.RenderFirstFrame(
                sessionId = sessionId, eventId = "e2", timestampMs = 1450L,
                elapsedSincePrepareMs = 450L
            )
        )

        val events = aggregator.eventsFor(sessionId)
        assertEquals(2, events.size)
        assertTrue(events[0] is DiagnosticEvent.SessionStart)
        assertTrue(events[1] is DiagnosticEvent.RenderFirstFrame)
    }

    @Test
    fun `exported json round-trips through the schema`() {
        val aggregator = SessionAggregator()
        val sessionId = "session-2"
        aggregator.record(
            DiagnosticEvent.RebufferStart(
                sessionId = sessionId, eventId = "e1", timestampMs = 2000L,
                bufferedMsAtStart = 500L
            )
        )

        val exportedJson = aggregator.exportSessionJson(sessionId)
        val parsed = Json.decodeFromString<ExportedSession>(exportedJson)

        assertEquals(1, parsed.schemaVersion)
        assertEquals(sessionId, parsed.sessionId)
        assertEquals(1, parsed.events.size)
        assertTrue(parsed.events.first() is DiagnosticEvent.RebufferStart)
    }

    @Test
    fun `different sessions do not leak events into each other`() {
        val aggregator = SessionAggregator()
        aggregator.record(DiagnosticEvent.SessionStart(
            sessionId = "a", eventId = "e1", timestampMs = 0L,
            mediaUri = "uri-a", drmScheme = null, deviceModel = "x", osVersion = "y", appVersion = "z"
        ))
        aggregator.record(DiagnosticEvent.SessionStart(
            sessionId = "b", eventId = "e2", timestampMs = 0L,
            mediaUri = "uri-b", drmScheme = null, deviceModel = "x", osVersion = "y", appVersion = "z"
        ))

        assertEquals(1, aggregator.eventsFor("a").size)
        assertEquals(1, aggregator.eventsFor("b").size)
    }

    @Test
    fun `clear removes events for a session`() {
        val aggregator = SessionAggregator()
        aggregator.record(DiagnosticEvent.PlaybackError(
            sessionId = "s", eventId = "e1", timestampMs = 0L,
            errorCode = "SOURCE_ERROR", cause = null, isFatal = true
        ))
        aggregator.clear("s")
        assertTrue(aggregator.eventsFor("s").isEmpty())
    }
}