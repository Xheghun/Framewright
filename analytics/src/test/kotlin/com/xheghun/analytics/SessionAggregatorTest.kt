package com.xheghun.analytics

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionAggregatorTest {
    @Test
    fun `records isolated sessions in insertion order`() {
        val aggregator = SessionAggregator()
        val primarySessionId = "primary-playback-session"
        val secondarySessionId = "secondary-playback-session"
        val primaryFirstFrame =
            DiagnosticEvent.RenderFirstFrame(
                metadata(primarySessionId, "primary-first-frame"),
                elapsedSincePrepareMs = 10,
            )
        val primaryDroppedFrames =
            DiagnosticEvent.DroppedFrames(
                metadata(primarySessionId, "primary-dropped-frames"),
                count = 2,
                elapsedMs = 100,
            )
        val secondaryFirstFrame =
            DiagnosticEvent.RenderFirstFrame(
                metadata(secondarySessionId, "secondary-first-frame"),
                elapsedSincePrepareMs = 20,
            )

        aggregator.record(primaryFirstFrame)
        aggregator.record(secondaryFirstFrame)
        aggregator.record(primaryDroppedFrames)

        assertThat(aggregator.eventsFor(primarySessionId)).containsExactly(primaryFirstFrame, primaryDroppedFrames)
        assertThat(aggregator.eventsFor(secondarySessionId)).containsExactly(secondaryFirstFrame)
    }

    @Test
    fun `truncates oldest events and marks snapshot`() {
        val aggregator = SessionAggregator(maxEventsPerSession = 2)
        repeat(3) { frameIndex ->
            aggregator.record(
                DiagnosticEvent.RenderFirstFrame(
                    metadata(eventId = "rendered-frame-$frameIndex"),
                    elapsedSincePrepareMs = frameIndex.toLong(),
                ),
            )
        }

        val truncatedSessionSnapshot = aggregator.snapshot("session-1")

        assertThat(truncatedSessionSnapshot.events.map { it.eventId })
            .containsExactly("rendered-frame-1", "rendered-frame-2")
        assertThat(truncatedSessionSnapshot.truncated).isTrue()
    }

    @Test
    fun `snapshot is immutable copy and clear is session scoped`() {
        val aggregator = SessionAggregator()
        val sessionToClear = "completed-playback-session"
        val sessionToRetain = "active-playback-session"
        aggregator.record(
            DiagnosticEvent.RenderFirstFrame(
                metadata(sessionToClear, "completed-session-first-frame"),
                elapsedSincePrepareMs = 1,
            ),
        )
        aggregator.record(
            DiagnosticEvent.RenderFirstFrame(
                metadata(sessionToRetain, "active-session-first-frame"),
                elapsedSincePrepareMs = 2,
            ),
        )
        val snapshotCapturedBeforeClear = aggregator.snapshot(sessionToClear)

        aggregator.clear(sessionToClear)

        assertThat(snapshotCapturedBeforeClear.events.size).isEqualTo(1)
        assertThat(aggregator.eventsFor(sessionToClear)).isEmpty()
        assertThat(aggregator.eventsFor(sessionToRetain).size).isEqualTo(1)
        assertThat(aggregator.isTruncated("unknown-playback-session")).isFalse()
    }

    @Test
    fun `rejects invalid retention limit`() {
        assertThrows<IllegalArgumentException> { SessionAggregator(0) }
    }

    @Test
    fun `records concurrently on real worker threads without loss`() {
        val workerCount = 8
        val eventsPerWorker = 500
        val expectedEventCount = workerCount * eventsPerWorker
        val concurrentSessionId = "concurrent-playback-session"
        val aggregator = SessionAggregator(maxEventsPerSession = expectedEventCount)
        val workerPool = Executors.newFixedThreadPool(workerCount)
        val startAllWorkers = CountDownLatch(1)
        val allWorkersFinished = CountDownLatch(workerCount)
        repeat(workerCount) { workerIndex ->
            workerPool.execute {
                startAllWorkers.await()
                repeat(eventsPerWorker) { eventIndex ->
                    aggregator.record(
                        DiagnosticEvent.RenderFirstFrame(
                            metadata(
                                sessionId = concurrentSessionId,
                                eventId = "worker-$workerIndex-first-frame-$eventIndex",
                            ),
                            elapsedSincePrepareMs = eventIndex.toLong(),
                        ),
                    )
                }
                allWorkersFinished.countDown()
            }
        }

        startAllWorkers.countDown()
        assertThat(allWorkersFinished.await(10, TimeUnit.SECONDS)).isTrue()
        workerPool.shutdownNow()
        assertThat(aggregator.eventsFor(concurrentSessionId).size).isEqualTo(expectedEventCount)
    }
}
