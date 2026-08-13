package com.xheghun.framewright.storage

import com.xheghun.analytics.CodecResult
import com.xheghun.analytics.DiagnosticEventJsonCodec
import com.xheghun.analytics.SessionSnapshot

internal class RoomDiagnosticSessionStore(
    private val database: FramewrightDatabase,
    private val codec: DiagnosticEventJsonCodec,
) : DiagnosticSessionStore {
    override suspend fun listSessions(): StorageResult<List<StoredDiagnosticSession>> =
        databaseCall {
            database.diagnosticDao().listSessions().map {
                StoredDiagnosticSession(
                    it.sessionId,
                    it.startedAtMs,
                    it.endedAtMs,
                    it.mediaUri,
                    it.drmScheme,
                    it.deviceModel,
                    it.osVersion,
                    it.appVersion,
                    it.endReason,
                    it.eventCount,
                )
            }
        }

    override suspend fun loadSession(sessionId: String): StorageResult<SessionSnapshot> {
        return try {
            val dao = database.diagnosticDao()
            if (dao.findSession(sessionId) == null) return StorageResult.Failure(StorageError.NOT_FOUND)
            val events =
                dao.eventsForSession(sessionId).map { entity ->
                    when (val decoded = codec.decodeEvent(entity.eventJson)) {
                        is CodecResult.Success -> decoded.data
                        is CodecResult.Failure -> return StorageResult.Failure(StorageError.SERIALIZATION)
                    }
                }
            StorageResult.Success(SessionSnapshot(sessionId = sessionId, truncated = false, events = events))
        } catch (error: Exception) {
            StorageResult.Failure(StorageError.DATABASE, error)
        }
    }

    override suspend fun deleteSession(sessionId: String): StorageResult<Unit> =
        try {
            if (database.diagnosticDao().deleteSession(sessionId) == 0) {
                StorageResult.Failure(StorageError.NOT_FOUND)
            } else {
                StorageResult.Success(Unit)
            }
        } catch (error: Exception) {
            StorageResult.Failure(StorageError.DATABASE, error)
        }

    override suspend fun exportSession(sessionId: String): StorageResult<String> =
        when (val loaded = loadSession(sessionId)) {
            is StorageResult.Failure -> loaded
            is StorageResult.Success ->
                when (val encoded = codec.encodeSession(loaded.data)) {
                    is CodecResult.Success -> StorageResult.Success(encoded.data)
                    is CodecResult.Failure -> StorageResult.Failure(StorageError.SERIALIZATION)
                }
        }

    private suspend fun <T> databaseCall(block: suspend () -> T): StorageResult<T> =
        try {
            StorageResult.Success(block())
        } catch (error: Exception) {
            StorageResult.Failure(StorageError.DATABASE, error)
        }
}
