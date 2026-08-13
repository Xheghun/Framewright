package com.xheghun.framewright.storage

import androidx.room.withTransaction
import com.xheghun.analytics.CodecResult
import com.xheghun.analytics.DIAGNOSTIC_SCHEMA_VERSION
import com.xheghun.analytics.DiagnosticEvent
import com.xheghun.analytics.DiagnosticEventJsonCodec

internal fun interface DiagnosticBatchPersistence {
    suspend fun write(events: List<DiagnosticEvent>): StorageResult<Unit>
}

internal class RoomDiagnosticPersistence(
    private val database: FramewrightDatabase,
    private val codec: DiagnosticEventJsonCodec,
) : DiagnosticBatchPersistence {
    override suspend fun write(events: List<DiagnosticEvent>): StorageResult<Unit> {
        val encodedEvents =
            events.map { event ->
                when (val encoded = codec.encodeEvent(event)) {
                    is CodecResult.Success -> event to encoded.data
                    is CodecResult.Failure -> return StorageResult.Failure(StorageError.SERIALIZATION)
                }
            }
        return try {
            database.withTransaction {
                val dao = database.diagnosticDao()
                val nextSequenceNumbers = mutableMapOf<String, Long>()
                encodedEvents.forEach { (event, eventJson) ->
                    if (event is DiagnosticEvent.SessionStart) {
                        dao.insertSession(event.toEntity())
                    }
                    val sequence =
                        nextSequenceNumbers.getOrPut(event.metadata.sessionId) {
                            dao.nextSequenceNumber(event.metadata.sessionId)
                        }
                    val inserted =
                        dao.insertEvent(
                            DiagnosticEventEntity(
                                eventId = event.metadata.eventId,
                                sessionId = event.metadata.sessionId,
                                sequenceNumber = sequence,
                                schemaVersion = DIAGNOSTIC_SCHEMA_VERSION,
                                timestampMs = event.metadata.timestampMs,
                                type = event.type.name,
                                eventJson = eventJson,
                            ),
                        )
                    if (inserted != -1L) nextSequenceNumbers[event.metadata.sessionId] = sequence + 1
                    if (event is DiagnosticEvent.SessionEnd) {
                        dao.finishSession(event.metadata.sessionId, event.metadata.timestampMs, event.reason.name)
                    }
                }
                StorageResult.Success(Unit)
            }
        } catch (error: Exception) {
            StorageResult.Failure(StorageError.DATABASE, error)
        }
    }
}

private fun DiagnosticEvent.SessionStart.toEntity() =
    PlaybackSessionEntity(
        sessionId = metadata.sessionId,
        schemaVersion = DIAGNOSTIC_SCHEMA_VERSION,
        startedAtMs = metadata.timestampMs,
        endedAtMs = null,
        mediaUri = mediaUri,
        drmScheme = drmScheme?.name,
        deviceModel = deviceModel,
        osVersion = osVersion,
        appVersion = appVersion,
        endReason = null,
    )
