package com.xheghun.analytics

interface PlayerEventSource {
    fun attach(bus: DiagnosticEventBus, sessionId: String)
    fun detach()
}