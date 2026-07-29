package com.xheghun.analytics

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private const val SCHEMA_VERSION = 1

@Serializable
data class ExportedSession(
    val schemaVersion: Int = SCHEMA_VERSION,
    val sessionId: String,
    val events: List<DiagnosticEvent>,
)

class SessionAggregator {
    private val eventsBySession = ConcurrentHashMap<String, CopyOnWriteArrayList<DiagnosticEvent>>()
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    fun record(event: DiagnosticEvent) {
        eventsBySession
            .getOrPut(event.sessionId) { CopyOnWriteArrayList() }
            .add(event)
    }

    fun eventsFor(sessionId: String): List<DiagnosticEvent> = eventsBySession[sessionId]?.toList() ?: emptyList()

    fun exportSessionJson(sessionId: String): String {
        val exported = ExportedSession(sessionId = sessionId, events = eventsFor(sessionId))
        return json.encodeToString(exported)
    }

    fun clear(sessionId: String) {
        eventsBySession.remove(sessionId)
    }
}
