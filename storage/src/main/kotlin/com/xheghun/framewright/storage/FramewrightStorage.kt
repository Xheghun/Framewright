package com.xheghun.framewright.storage

import android.content.Context
import androidx.room.Room
import com.xheghun.analytics.DiagnosticEventJsonCodec

class FramewrightStorage private constructor(
    private val database: FramewrightDatabase,
    val eventSink: RoomDiagnosticEventSink,
    val sessionStore: DiagnosticSessionStore,
) {
    suspend fun close(): StorageResult<Unit> {
        val result = eventSink.close()
        database.close()
        return result
    }

    companion object {
        fun create(
            context: Context,
            databaseName: String = "framewright-diagnostics.db",
            onStorageError: (StorageError, Throwable?) -> Unit = { _, _ -> },
        ): FramewrightStorage {
            val database =
                Room.databaseBuilder(context.applicationContext, FramewrightDatabase::class.java, databaseName).build()
            return create(database, onStorageError)
        }

        fun createInMemory(
            context: Context,
            onStorageError: (StorageError, Throwable?) -> Unit = { _, _ -> },
        ): FramewrightStorage =
            create(
                Room.inMemoryDatabaseBuilder(context.applicationContext, FramewrightDatabase::class.java).build(),
                onStorageError,
            )

        private fun create(
            database: FramewrightDatabase,
            onStorageError: (StorageError, Throwable?) -> Unit,
        ): FramewrightStorage {
            val codec = DiagnosticEventJsonCodec()
            val sink = RoomDiagnosticEventSink(RoomDiagnosticPersistence(database, codec), onStorageError = onStorageError)
            return FramewrightStorage(database, sink, RoomDiagnosticSessionStore(database, codec))
        }
    }
}
