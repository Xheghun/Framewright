package com.xheghun.analytics

import java.util.concurrent.atomic.AtomicBoolean

interface PlayerEventSource {
    fun attach(
        bus: DiagnosticEventBus,
        sessionId: String,
    )

    fun detach()
}

abstract class AbstractPlayerEventSource : PlayerEventSource {
    private val attached = AtomicBoolean(false)

    protected abstract fun onAttach(
        bus: DiagnosticEventBus,
        sessionId: String,
    )

    protected abstract fun onDetach()

    final override fun attach(
        bus: DiagnosticEventBus,
        sessionId: String,
    ) {
        check(attached.compareAndSet(false, true)) {
            "attach() called while already attached — call detach() first"
        }
        onAttach(bus, sessionId)
    }

    final override fun detach() {
        if (attached.compareAndSet(true, false)) {
            onDetach()
        }
    }
}
