package com.xheghun.analytics

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class DiagnosticEventBus {
    private val _events = MutableSharedFlow<DiagnosticEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<DiagnosticEvent> = _events.asSharedFlow()

    suspend fun publish(event: DiagnosticEvent) = _events.emit(event)

    fun tryPublish(event: DiagnosticEvent): Boolean = _events.tryEmit(event)
}
