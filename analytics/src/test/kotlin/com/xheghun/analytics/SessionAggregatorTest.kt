package com.xheghun.analytics

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


class SessionAggregatorTest {

    private fun startEvent(sessionId: String, eventId: String) = DiagnosticEvent.SessionStart(
        sessionId = sessionId, eventId = eventId, timestampMs = 0L,
        mediaUri = "uri", drmScheme = null, deviceModel = "x", osVersion = "y", appVersion = "z"
    )

    @Test
    fun `records events and exports them under the same session id`() {
        val aggregator = SessionAggregator()
        val sessionId = "session-1"

        aggregator.record(startEvent(sessionId, "e1"))
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

        val result = aggregator.exportSessionJson(sessionId)
        assertTrue(result is ExportResult.Success)
        val parsed = Json.decodeFromString<ExportedSession>((result as ExportResult.Success).json)

        assertEquals(1, parsed.schemaVersion)
        assertEquals(sessionId, parsed.sessionId)
        assertFalse(parsed.truncated)
        assertEquals(1, parsed.events.size)
        assertTrue(parsed.events.first() is DiagnosticEvent.RebufferStart)
    }

    @Test
    fun `different sessions do not leak events into each other`() {
        val aggregator = SessionAggregator()
        aggregator.record(startEvent("a", "e1"))
        aggregator.record(startEvent("b", "e2"))

        assertEquals(1, aggregator.eventsFor("a").size)
        assertEquals(1, aggregator.eventsFor("b").size)
    }

    @Test
    fun `clear removes events for a session`() {
        val aggregator = SessionAggregator()
        aggregator.record(
            DiagnosticEvent.PlaybackError(
                sessionId = "s", eventId = "e1", timestampMs = 0L,
                errorCode = "SOURCE_ERROR", cause = null, isFatal = true
            )
        )
        aggregator.clear("s")
        assertTrue(aggregator.eventsFor("s").isEmpty())
    }

    @Test
    fun `session exceeding max events is truncated and oldest events are evicted`() {
        val aggregator = SessionAggregator(maxEventsPerSession = 10)
        val sessionId = "long-session"

        repeat(15) { i ->
            aggregator.record(startEvent(sessionId, "e$i"))
        }

        val events = aggregator.eventsFor(sessionId)
        assertEquals(10, events.size)
        assertTrue(aggregator.isTruncated(sessionId))
        // oldest 5 (e0..e4) should have been evicted; e5 should be the first remaining
        assertEquals("e5", events.first().eventId)
        assertEquals("e14", events.last().eventId)

        val result = aggregator.exportSessionJson(sessionId) as ExportResult.Success
        val parsed = Json.decodeFromString<ExportedSession>(result.json)
        assertTrue(parsed.truncated)
    }

    @Test
    fun `exportSession for unknown session returns an empty, non-truncated success`() {
        val aggregator = SessionAggregator()
        val result = aggregator.exportSessionJson("never-recorded") as ExportResult.Success
        val parsed = Json.decodeFromString<ExportedSession>(result.json)
        assertTrue(parsed.events.isEmpty())
        assertFalse(parsed.truncated)
    }

    @Test
    fun `concurrent record calls from many coroutines lose no events`() = runBlocking {
        val aggregator = SessionAggregator(maxEventsPerSession = 10_000)
        val sessionId = "concurrent-session"
        val coroutineCount = 50
        val eventsPerCoroutine = 100

        val jobs = (0 until coroutineCount).map { coroutineIndex ->
            async {
                repeat(eventsPerCoroutine) { i ->
                    aggregator.record(startEvent(sessionId, "c$coroutineIndex-e$i"))
                }
            }
        }
        jobs.awaitAll()

        assertEquals(coroutineCount * eventsPerCoroutine, aggregator.eventsFor(sessionId).size)
    }
}