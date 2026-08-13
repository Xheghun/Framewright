package com.xheghun.framewright.storage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xheghun.analytics.CodecResult
import com.xheghun.analytics.DiagnosticEvent
import com.xheghun.analytics.DiagnosticEventJsonCodec
import com.xheghun.analytics.DiagnosticEventMetadata
import com.xheghun.analytics.SessionEndReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomStorageIntegrationTest {
    @Test
    fun sessionSurvivesFlushAndReconstructsLosslessly() {
        runBlocking {
            val storage = FramewrightStorage.createInMemory(ApplicationProvider.getApplicationContext())
            val events = completeSession()

            events.forEach(storage.eventSink::record)
            storage.eventSink.record(events.first())
            assertTrue(storage.eventSink.flush() is StorageResult.Success)

            val sessions = (storage.sessionStore.listSessions() as StorageResult.Success).data
            val snapshot = (storage.sessionStore.loadSession("session-1") as StorageResult.Success).data
            val exported = (storage.sessionStore.exportSession("session-1") as StorageResult.Success).data
            val decoded = (DiagnosticEventJsonCodec().decodeSession(exported) as CodecResult.Success).data

            assertEquals(1, sessions.size)
            assertTrue(sessions.single().isComplete)
            assertEquals(events, snapshot.events)
            assertEquals(snapshot, decoded)
            assertTrue(storage.sessionStore.deleteSession("session-1") is StorageResult.Success)
            assertTrue(storage.sessionStore.loadSession("session-1") is StorageResult.Failure)
            storage.close()
        }
    }

    @Test
    fun sessionWithoutEndEventRemainsAvailableAsIncomplete() {
        runBlocking {
            val storage = FramewrightStorage.createInMemory(ApplicationProvider.getApplicationContext())
            storage.eventSink.record(completeSession().first())
            storage.eventSink.flush()

            val session = (storage.sessionStore.listSessions() as StorageResult.Success).data.single()

            assertFalse(session.isComplete)
            assertEquals(null, session.endReason)
            storage.close()
        }
    }

    @Test
    fun sessionRemainsAvailableAfterDatabaseIsClosedAndReopened() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val databaseName = "framewright-restart-${UUID.randomUUID()}.db"
            try {
                val firstStorage = FramewrightStorage.create(context, databaseName)
                completeSession().forEach(firstStorage.eventSink::record)
                assertTrue(firstStorage.close() is StorageResult.Success)

                val reopenedStorage = FramewrightStorage.create(context, databaseName)
                val restored = reopenedStorage.sessionStore.loadSession("session-1") as StorageResult.Success

                assertEquals(completeSession(), restored.data.events)
                reopenedStorage.close()
            } finally {
                context.deleteDatabase(databaseName)
            }
        }
    }

    @Test
    fun incompleteSessionRemainsAvailableAfterDatabaseIsClosedAndReopened() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val databaseName = "framewright-incomplete-${UUID.randomUUID()}.db"
            try {
                val firstStorage = FramewrightStorage.create(context, databaseName)
                firstStorage.eventSink.record(completeSession().first())
                assertTrue(firstStorage.close() is StorageResult.Success)

                val reopenedStorage = FramewrightStorage.create(context, databaseName)
                val restoredSession =
                    (reopenedStorage.sessionStore.listSessions() as StorageResult.Success).data.single()

                assertFalse(restoredSession.isComplete)
                assertEquals(null, restoredSession.endReason)
                reopenedStorage.close()
            } finally {
                context.deleteDatabase(databaseName)
            }
        }
    }

    private fun completeSession(): List<DiagnosticEvent> =
        listOf(
            DiagnosticEvent.SessionStart(
                metadata("start", 1_000),
                mediaUri = "https://example.test/video.m3u8",
                deviceModel = "device",
                osVersion = "16",
                appVersion = "1.0",
            ),
            DiagnosticEvent.RenderFirstFrame(metadata("first-frame", 1_400), 400),
            DiagnosticEvent.SessionEnd(metadata("end", 2_000), 1_000, SessionEndReason.PLAYBACK_ENDED),
        )

    private fun metadata(
        eventId: String,
        timestampMs: Long,
    ) = DiagnosticEventMetadata("session-1", eventId, timestampMs, timestampMs)
}
