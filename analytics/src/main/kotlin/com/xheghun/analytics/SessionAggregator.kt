package com.xheghun.analytics

import java.util.concurrent.ConcurrentHashMap

private const val DEFAULT_MAX_EVENTS_PER_SESSION = 5_000

data class SessionSnapshot(
    val schemaVersion: Int = DIAGNOSTIC_SCHEMA_VERSION,
    val sessionId: String,
    val truncated: Boolean,
    val events: List<DiagnosticEvent>,
)

class SessionAggregator(
    private val maxEventsPerSession: Int = DEFAULT_MAX_EVENTS_PER_SESSION,
) {
    private data class SessionState(
        val events: MutableList<DiagnosticEvent> = mutableListOf(),
        var truncated: Boolean = false,
    )

    private val sessions = ConcurrentHashMap<String, SessionState>()

    init {
        require(maxEventsPerSession > 0) { "maxEventsPerSession must be greater than zero" }
    }

    fun record(event: DiagnosticEvent) {
        sessions.compute(event.sessionId) { _, current ->
            val state = current ?: SessionState()
            state.events += event
            val overflow = state.events.size - maxEventsPerSession
            if (overflow > 0) {
                state.events.subList(0, overflow).clear()
                state.truncated = true
            }
            state
        }
    }

    fun snapshot(sessionId: String): SessionSnapshot {
        var snapshot = SessionSnapshot(sessionId = sessionId, truncated = false, events = emptyList())
        sessions.computeIfPresent(sessionId) { _, state ->
            snapshot = SessionSnapshot(sessionId = sessionId, truncated = state.truncated, events = state.events.toList())
            state
        }
        return snapshot
    }

    fun eventsFor(sessionId: String): List<DiagnosticEvent> = snapshot(sessionId).events

    fun isTruncated(sessionId: String): Boolean = snapshot(sessionId).truncated

    fun exportSessionJson(
        sessionId: String,
        codec: DiagnosticEventJsonCodec = DiagnosticEventJsonCodec(),
    ): CodecResult<String> = codec.encodeSession(snapshot(sessionId))

    fun clear(sessionId: String) {
        sessions.computeIfPresent(sessionId) { _, _ -> null }
    }
}
