package com.xheghun.analytics

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DiagnosticEventPipelineTest {
    @Test
    fun `delivers events to sinks in configured order before live delivery`() =
        runTest {
            val deliveryOrder = mutableListOf<String>()
            val event = DiagnosticEvent.RenderFirstFrame(metadata(), 450)
            val pipeline =
                DiagnosticEventPipeline(
                    sinks =
                        listOf(
                            DiagnosticEventSink { deliveryOrder += "first-sink" },
                            DiagnosticEventSink { deliveryOrder += "second-sink" },
                        ),
                )

            pipeline.events.test {
                pipeline.tryPublish(event)
                deliveryOrder += "live-${awaitItem().eventId}"
                cancelAndIgnoreRemainingEvents()
            }

            assertThat(deliveryOrder).containsExactly("first-sink", "second-sink", "live-event-1")
        }

    @Test
    fun `sink failure is reported and does not prevent other delivery`() {
        val deliveredEvents = mutableListOf<DiagnosticEvent>()
        val sinkErrors = mutableListOf<Throwable>()
        val event = DiagnosticEvent.RenderFirstFrame(metadata(), 450)
        val pipeline =
            DiagnosticEventPipeline(
                sinks =
                    listOf(
                        DiagnosticEventSink { error("disk unavailable") },
                        DiagnosticEventSink(deliveredEvents::add),
                    ),
                onSinkError = { _, _, error -> sinkErrors += error },
            )

        val result = pipeline.tryPublish(event)

        assertThat(result.sinkFailureCount).isEqualTo(1)
        assertThat(deliveredEvents).containsExactly(event)
        assertThat(sinkErrors.single().message).isEqualTo("disk unavailable")
        assertThat(pipeline.snapshot("session-1").events).containsExactly(event)
    }

    @Test
    fun `records event when there are no live subscribers`() {
        val pipeline = DiagnosticEventPipeline()
        val event = DiagnosticEvent.RenderFirstFrame(metadata(), 450)

        val result = pipeline.tryPublish(event)

        assertThat(result).isEqualTo(PublishResult(true, LiveDelivery.NO_SUBSCRIBERS))
        assertThat(pipeline.snapshot("session-1").events).containsExactly(event)
    }

    @Test
    fun `tryPublish delivers ordered live events to subscriber`() =
        runTest {
            val pipeline = DiagnosticEventPipeline()
            val first = DiagnosticEvent.RenderFirstFrame(metadata(eventId = "1"), 450)
            val second = DiagnosticEvent.DroppedFrames(metadata(eventId = "2"), 2, 100)

            pipeline.events.test {
                assertThat(pipeline.tryPublish(first).liveDelivery).isEqualTo(LiveDelivery.DELIVERED)
                assertThat(pipeline.tryPublish(second).liveDelivery).isEqualTo(LiveDelivery.DELIVERED)
                assertThat(awaitItem()).isEqualTo(first)
                assertThat(awaitItem()).isEqualTo(second)
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(pipeline.snapshot("session-1").events).containsExactly(first, second)
        }

    @Test
    fun `suspending publish records before live delivery`() =
        runTest {
            val pipeline = DiagnosticEventPipeline()
            val event = DiagnosticEvent.RenderFirstFrame(metadata(), 450)

            pipeline.events.test {
                assertThat(pipeline.publish(event).recorded).isEqualTo(true)
                assertThat(awaitItem()).isEqualTo(event)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `slow subscriber overflow is counted without losing recording`() =
        runTest {
            val dropped = mutableListOf<DiagnosticEvent>()
            val pipeline = DiagnosticEventPipeline(extraBufferCapacity = 1, onLiveEventDropped = dropped::add)
            val blockSubscriber = CompletableDeferred<Unit>()
            val collector =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    pipeline.events.collect { blockSubscriber.await() }
                }

            val results =
                (1..10).map { index ->
                    pipeline.tryPublish(
                        DiagnosticEvent.RenderFirstFrame(metadata(eventId = "$index"), index.toLong()),
                    )
                }

            assertThat(results.any { it.liveDelivery == LiveDelivery.DROPPED }).isEqualTo(true)
            assertThat(pipeline.droppedLiveEventCount).isEqualTo(dropped.size.toLong())
            assertThat(pipeline.snapshot("session-1").events.size).isEqualTo(10)
            collector.cancel()
        }
}
