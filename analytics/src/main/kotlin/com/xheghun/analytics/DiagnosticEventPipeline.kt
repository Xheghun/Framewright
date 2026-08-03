package com.xheghun.analytics

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong

enum class LiveDelivery { DELIVERED, NO_SUBSCRIBERS, DROPPED }

data class PublishResult(
    val recorded: Boolean,
    val liveDelivery: LiveDelivery,
)

class DiagnosticEventPipeline(
    private val aggregator: SessionAggregator = SessionAggregator(),
    extraBufferCapacity: Int = 128,
    private val onLiveEventDropped: ((DiagnosticEvent) -> Unit)? = null,
) {
    private val mutableEvents = MutableSharedFlow<DiagnosticEvent>(replay = 0, extraBufferCapacity = extraBufferCapacity)
    private val droppedCounter = AtomicLong()

    val events: SharedFlow<DiagnosticEvent> = mutableEvents.asSharedFlow()
    val droppedLiveEventCount: Long get() = droppedCounter.get()

    fun tryPublish(event: DiagnosticEvent): PublishResult {
        aggregator.record(event)
        if (mutableEvents.subscriptionCount.value == 0) {
            return PublishResult(recorded = true, liveDelivery = LiveDelivery.NO_SUBSCRIBERS)
        }
        if (mutableEvents.tryEmit(event)) {
            return PublishResult(recorded = true, liveDelivery = LiveDelivery.DELIVERED)
        }
        droppedCounter.incrementAndGet()
        onLiveEventDropped?.invoke(event)
        return PublishResult(recorded = true, liveDelivery = LiveDelivery.DROPPED)
    }

    suspend fun publish(event: DiagnosticEvent): PublishResult {
        aggregator.record(event)
        if (mutableEvents.subscriptionCount.value == 0) {
            return PublishResult(recorded = true, liveDelivery = LiveDelivery.NO_SUBSCRIBERS)
        }
        mutableEvents.emit(event)
        return PublishResult(recorded = true, liveDelivery = LiveDelivery.DELIVERED)
    }

    fun snapshot(sessionId: String): SessionSnapshot = aggregator.snapshot(sessionId)

    fun exportSessionJson(
        sessionId: String,
        codec: DiagnosticEventJsonCodec = DiagnosticEventJsonCodec(),
    ): CodecResult<String> = aggregator.exportSessionJson(sessionId, codec)

    fun clear(sessionId: String) = aggregator.clear(sessionId)
}

@Deprecated("Use DiagnosticEventPipeline", ReplaceWith("DiagnosticEventPipeline"))
typealias DiagnosticEventBus = DiagnosticEventPipeline
