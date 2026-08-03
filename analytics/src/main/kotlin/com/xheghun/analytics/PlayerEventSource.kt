package com.xheghun.analytics

import java.util.concurrent.atomic.AtomicBoolean

interface PlayerEventSource {
    fun attach(
        pipeline: DiagnosticEventPipeline,
        sessionId: String,
    )

    fun detach()
}

abstract class AbstractPlayerEventSource : PlayerEventSource {
    private val attached = AtomicBoolean(false)

    protected abstract fun onAttach(
        pipeline: DiagnosticEventPipeline,
        sessionId: String,
    )

    protected abstract fun onDetach()

    final override fun attach(
        pipeline: DiagnosticEventPipeline,
        sessionId: String,
    ) {
        check(attached.compareAndSet(false, true)) {
            "attach() called while already attached — call detach() first"
        }
        try {
            onAttach(pipeline, sessionId)
        } catch (error: Throwable) {
            attached.set(false)
            throw error
        }
    }

    final override fun detach() {
        if (attached.compareAndSet(true, false)) {
            onDetach()
        }
    }
}
