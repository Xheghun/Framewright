package com.xheghun.analytics

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong

enum class LiveDelivery { DELIVERED, NO_SUBSCRIBERS, DROPPED }

data class PublishResult(
    val recorded: Boolean,
    val liveDelivery: LiveDelivery,
    val sinkFailureCount: Int = 0,
)

class DiagnosticEventPipeline(
    private val aggregator: SessionAggregator = SessionAggregator(),
    extraBufferCapacity: Int = 128,
    private val sinks: List<DiagnosticEventSink> = emptyList(),
    private val onSinkError: (DiagnosticEventSink, DiagnosticEvent, Throwable) -> Unit = { _, _, _ -> },
    private val onLiveEventDropped: ((DiagnosticEvent) -> Unit)? = null,
) {
    constructor(
        aggregator: SessionAggregator,
        extraBufferCapacity: Int,
        onLiveEventDropped: ((DiagnosticEvent) -> Unit)?,
    ) : this(
        aggregator = aggregator,
        extraBufferCapacity = extraBufferCapacity,
        sinks = emptyList(),
        onSinkError = { _, _, _ -> },
        onLiveEventDropped = onLiveEventDropped,
    )

    private val mutableEvents = MutableSharedFlow<DiagnosticEvent>(replay = 0, extraBufferCapacity = extraBufferCapacity)
    private val droppedCounter = AtomicLong()

    val events: SharedFlow<DiagnosticEvent> = mutableEvents.asSharedFlow()
    val droppedLiveEventCount: Long get() = droppedCounter.get()

    fun tryPublish(event: DiagnosticEvent): PublishResult {
        aggregator.record(event)
        val sinkFailureCount = deliverToSinks(event)
        if (mutableEvents.subscriptionCount.value == 0) {
            return PublishResult(true, LiveDelivery.NO_SUBSCRIBERS, sinkFailureCount)
        }
        if (mutableEvents.tryEmit(event)) {
            return PublishResult(true, LiveDelivery.DELIVERED, sinkFailureCount)
        }
        droppedCounter.incrementAndGet()
        onLiveEventDropped?.invoke(event)
        return PublishResult(true, LiveDelivery.DROPPED, sinkFailureCount)
    }

    suspend fun publish(event: DiagnosticEvent): PublishResult {
        aggregator.record(event)
        val sinkFailureCount = deliverToSinks(event)
        if (mutableEvents.subscriptionCount.value == 0) {
            return PublishResult(true, LiveDelivery.NO_SUBSCRIBERS, sinkFailureCount)
        }
        mutableEvents.emit(event)
        return PublishResult(true, LiveDelivery.DELIVERED, sinkFailureCount)
    }

    fun snapshot(sessionId: String): SessionSnapshot = aggregator.snapshot(sessionId)

    fun exportSessionJson(
        sessionId: String,
        codec: DiagnosticEventJsonCodec = DiagnosticEventJsonCodec(),
    ): CodecResult<String> = aggregator.exportSessionJson(sessionId, codec)

    fun clear(sessionId: String) = aggregator.clear(sessionId)

    private fun deliverToSinks(event: DiagnosticEvent): Int {
        var failureCount = 0
        sinks.forEach { sink ->
            try {
                sink.record(event)
            } catch (error: Throwable) {
                failureCount++
                runCatching { onSinkError(sink, event, error) }
            }
        }
        return failureCount
    }
}

@Deprecated("Use DiagnosticEventPipeline", ReplaceWith("DiagnosticEventPipeline"))
typealias DiagnosticEventBus = DiagnosticEventPipeline
