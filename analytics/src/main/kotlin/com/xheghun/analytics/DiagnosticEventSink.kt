package com.xheghun.analytics

/**
 * Receives diagnostic events synchronously from the publishing thread.
 *
 * Implementations must return quickly and move blocking work to their own worker. Exceptions are
 * isolated by [DiagnosticEventPipeline] and never propagated to playback.
 */
fun interface DiagnosticEventSink {
    fun record(event: DiagnosticEvent)
}
