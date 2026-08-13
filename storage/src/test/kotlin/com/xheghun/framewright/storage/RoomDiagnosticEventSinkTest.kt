package com.xheghun.framewright.storage

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.xheghun.analytics.DiagnosticEvent
import com.xheghun.analytics.DiagnosticEventMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class RoomDiagnosticEventSinkTest {
    @Test
    fun `queue overload is reported without blocking publisher`() =
        runTest {
            val reportedErrors = mutableListOf<StorageError>()
            val pausedScope = CoroutineScope(StandardTestDispatcher(testScheduler))
            val sink =
                RoomDiagnosticEventSink(
                    persistence = DiagnosticBatchPersistence { StorageResult.Success(Unit) },
                    queueCapacity = 1,
                    onStorageError = { error, _ -> reportedErrors += error },
                    scope = pausedScope,
                )

            sink.record(event(1))
            sink.record(event(2))

            assertThat(reportedErrors).containsExactly(StorageError.OVERLOADED)
            testScheduler.runCurrent()
            sink.close()
        }

    @Test
    fun `flush persists queued events in order and batches writes`() =
        runTest {
            val writtenBatches = mutableListOf<List<DiagnosticEvent>>()
            val persistence =
                DiagnosticBatchPersistence { events ->
                    writtenBatches += events
                    StorageResult.Success(Unit)
                }
            val sink = RoomDiagnosticEventSink(persistence, batchSize = 2, scope = backgroundScope)
            val events = (1..3).map(::event)

            events.forEach(sink::record)
            val flushResult = sink.flush()

            assertThat(flushResult).isEqualTo(StorageResult.Success(Unit))
            assertThat(writtenBatches.flatten()).containsExactly(*events.toTypedArray())
            assertThat(writtenBatches.map(List<DiagnosticEvent>::size)).containsExactly(2, 1)
            sink.close()
        }

    @Test
    fun `persistence failure is observable without throwing from record`() =
        runTest {
            val reportedErrors = mutableListOf<StorageError>()
            val sink =
                RoomDiagnosticEventSink(
                    persistence = DiagnosticBatchPersistence { StorageResult.Failure(StorageError.DATABASE) },
                    onStorageError = { error, _ -> reportedErrors += error },
                    scope = backgroundScope,
                )

            sink.record(event(1))
            val result = sink.flush()

            assertThat(result).isEqualTo(StorageResult.Failure(StorageError.DATABASE))
            assertThat(reportedErrors).containsExactly(StorageError.DATABASE)
            sink.close()
        }

    private fun event(index: Int) =
        DiagnosticEvent.RenderFirstFrame(
            DiagnosticEventMetadata("session", "event-$index", index.toLong(), index.toLong()),
            elapsedSincePrepareMs = index.toLong(),
        )
}
