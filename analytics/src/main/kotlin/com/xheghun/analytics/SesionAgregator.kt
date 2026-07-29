package com.xheghun.analytics

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.mutableListOf
import kotlin.concurrent.withLock

private const val SCHEMA_VERSION = 1
private const val DEFAULT_MAX_EVENTS_PER_SESSION = 5_000

@Serializable
data class ExportedSession(
    val schemaVersion: Int = SCHEMA_VERSION,
    val sessionId: String,
    val truncated: Boolean,
    val events: List<DiagnosticEvent>,
)

sealed class ExportResult {
    data class Success(val json: String) : ExportResult()
    data class Failure(val sessionId: String, val cause: Throwable) : ExportResult()
}

class SessionAggregator( private val maxEventsPerSession: Int = DEFAULT_MAX_EVENTS_PER_SESSION) {
    private val eventsBySession = ConcurrentHashMap<String, CopyOnWriteArrayList<DiagnosticEvent>>()
    private val locksBySession = ConcurrentHashMap<String, ReentrantLock>()
    private val truncatedSessions = ConcurrentHashMap.newKeySet<String>()
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    fun record(event: DiagnosticEvent) {
        val lock = locksBySession.getOrPut(event.sessionId) { ReentrantLock() }
        lock.withLock {
            val list = eventsBySession.getOrPut(event.sessionId) {  CopyOnWriteArrayList<DiagnosticEvent>() }
            list.add(event)
            if (list.size > maxEventsPerSession) {
                val overflow = list.size - maxEventsPerSession
                repeat(overflow) { list.removeAt(0) }
                truncatedSessions.add(event.sessionId)
            }
        }
    }

    fun eventsFor(sessionId: String): List<DiagnosticEvent> {
        val lock = locksBySession[sessionId] ?: return emptyList()
        return lock.withLock { eventsBySession[sessionId]?.toList() ?: emptyList() }
    }

    fun isTruncated(sessionId: String): Boolean = sessionId in truncatedSessions


    fun exportSessionJson(sessionId: String): ExportResult {
        return try {
            val exported = ExportedSession(
                sessionId = sessionId,
                truncated = isTruncated(sessionId),
                events = eventsFor(sessionId)
            )
            ExportResult.Success(json.encodeToString(exported))
        } catch (e: SerializationException) {
            ExportResult.Failure(sessionId, e)
        }
    }

    fun clear(sessionId: String) {
        val lock = locksBySession[sessionId]
        lock?.withLock { eventsBySession.remove(sessionId) }
        truncatedSessions.remove(sessionId)
        locksBySession.remove(sessionId)
    }
}
