package com.xheghun.framewright.storage

import com.xheghun.analytics.SessionSnapshot

data class StoredDiagnosticSession(
    val sessionId: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val mediaUri: String,
    val drmScheme: String?,
    val deviceModel: String,
    val osVersion: String,
    val appVersion: String,
    val endReason: String?,
    val eventCount: Int,
) {
    val isComplete: Boolean get() = endedAtMs != null
}

interface DiagnosticSessionStore {
    suspend fun listSessions(): StorageResult<List<StoredDiagnosticSession>>

    suspend fun loadSession(sessionId: String): StorageResult<SessionSnapshot>

    suspend fun deleteSession(sessionId: String): StorageResult<Unit>

    suspend fun exportSession(sessionId: String): StorageResult<String>
}
