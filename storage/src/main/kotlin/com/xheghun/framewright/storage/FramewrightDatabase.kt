package com.xheghun.framewright.storage

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "playback_sessions")
internal data class PlaybackSessionEntity(
    @androidx.room.PrimaryKey val sessionId: String,
    val schemaVersion: Int,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val mediaUri: String,
    val drmScheme: String?,
    val deviceModel: String,
    val osVersion: String,
    val appVersion: String,
    val endReason: String?,
)

@Entity(
    tableName = "diagnostic_events",
    foreignKeys =
        [
            ForeignKey(
                entity = PlaybackSessionEntity::class,
                parentColumns = ["sessionId"],
                childColumns = ["sessionId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices =
        [
            Index("sessionId"),
            Index(value = ["sessionId", "sequenceNumber"], unique = true),
        ],
)
internal data class DiagnosticEventEntity(
    @androidx.room.PrimaryKey val eventId: String,
    val sessionId: String,
    val sequenceNumber: Long,
    val schemaVersion: Int,
    val timestampMs: Long,
    val type: String,
    val eventJson: String,
)

internal data class StoredSessionRow(
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
)

@Dao
internal interface DiagnosticDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSession(session: PlaybackSessionEntity): Long

    @Query("UPDATE playback_sessions SET endedAtMs = :endedAtMs, endReason = :reason WHERE sessionId = :sessionId")
    suspend fun finishSession(
        sessionId: String,
        endedAtMs: Long,
        reason: String,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(event: DiagnosticEventEntity): Long

    @Query("SELECT COALESCE(MAX(sequenceNumber) + 1, 0) FROM diagnostic_events WHERE sessionId = :sessionId")
    suspend fun nextSequenceNumber(sessionId: String): Long

    @Query(
        """
        SELECT s.sessionId, s.startedAtMs, s.endedAtMs, s.mediaUri, s.drmScheme,
               s.deviceModel, s.osVersion, s.appVersion, s.endReason,
               COUNT(e.eventId) AS eventCount
        FROM playback_sessions s
        LEFT JOIN diagnostic_events e ON e.sessionId = s.sessionId
        GROUP BY s.sessionId
        ORDER BY s.startedAtMs DESC, s.sessionId DESC
        """,
    )
    suspend fun listSessions(): List<StoredSessionRow>

    @Query("SELECT * FROM playback_sessions WHERE sessionId = :sessionId")
    suspend fun findSession(sessionId: String): PlaybackSessionEntity?

    @Query("SELECT * FROM diagnostic_events WHERE sessionId = :sessionId ORDER BY sequenceNumber ASC")
    suspend fun eventsForSession(sessionId: String): List<DiagnosticEventEntity>

    @Query("DELETE FROM playback_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String): Int
}

@Database(
    entities = [PlaybackSessionEntity::class, DiagnosticEventEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class FramewrightDatabase : RoomDatabase() {
    abstract fun diagnosticDao(): DiagnosticDao
}
