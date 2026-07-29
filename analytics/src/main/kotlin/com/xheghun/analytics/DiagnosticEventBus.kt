package com.xheghun.analytics

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong

class DiagnosticEventBus(
    extraBufferCapacity: Int = 128,
    private val onEventDropped: ((DiagnosticEvent) -> Unit)? = null,
) {
    private val _events =
        MutableSharedFlow<DiagnosticEvent>(replay = 0, extraBufferCapacity = extraBufferCapacity)

    val events: SharedFlow<DiagnosticEvent> = _events.asSharedFlow()

    private val _droppedEventCount = AtomicLong(0)
    val droppedEventCount: Long get() = _droppedEventCount.get()

    suspend fun publish(event: DiagnosticEvent) = _events.emit(event)

    /**
     * Non-suspending publish for use inside synchronous player listener callbacks, where
     * suspending is not an option. Returns false if the event was dropped due to buffer
     * overflow — callers may check this for critical events (e.g. PLAYBACK_ERROR) but
     * should not treat a false return as fatal; it is expected under sustained bursts.
     */
    fun tryPublish(event: DiagnosticEvent): Boolean {
        val didPublish = _events.tryEmit(event)

        if (!didPublish) {
            _droppedEventCount.incrementAndGet()
            onEventDropped?.invoke(event)
        }

        return didPublish
    }
}
