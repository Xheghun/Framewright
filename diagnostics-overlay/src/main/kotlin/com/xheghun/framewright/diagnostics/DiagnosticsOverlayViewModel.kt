package com.xheghun.framewright.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xheghun.analytics.DiagnosticEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DiagnosticsOverlayViewModel internal constructor(
    private val updateIntervalMs: Long,
) : ViewModel() {
    constructor() : this(DEFAULT_UPDATE_INTERVAL_MS)

    private val mutableState = MutableStateFlow(DiagnosticsOverlayState())
    val state = mutableState.asStateFlow()

    private var latestReducedState = mutableState.value
    private var scheduledPublish: Job? = null

    init {
        require(updateIntervalMs > 0) { "updateIntervalMs must be greater than zero" }
    }

    fun onAction(action: DiagnosticsOverlayAction) {
        latestReducedState =
            when (action) {
                DiagnosticsOverlayAction.ToggleVisibility ->
                    latestReducedState.copy(isVisible = !latestReducedState.isVisible)
                DiagnosticsOverlayAction.Close -> latestReducedState.copy(isVisible = false)
            }
        publishImmediately()
    }

    fun onDiagnosticEvent(event: DiagnosticEvent) {
        val reducedState = DiagnosticsOverlayReducer.reduce(latestReducedState, event)
        if (reducedState == latestReducedState) return
        latestReducedState = reducedState
        if (event.requiresImmediatePublish()) {
            publishImmediately()
        } else if (latestReducedState != mutableState.value && scheduledPublish == null) {
            scheduledPublish =
                viewModelScope.launch {
                    delay(updateIntervalMs)
                    mutableState.update { latestReducedState }
                    scheduledPublish = null
                }
        }
    }

    override fun onCleared() {
        scheduledPublish?.cancel()
        super.onCleared()
    }

    private fun publishImmediately() {
        scheduledPublish?.cancel()
        scheduledPublish = null
        mutableState.update { latestReducedState }
    }

    private fun DiagnosticEvent.requiresImmediatePublish(): Boolean =
        this is DiagnosticEvent.SessionStart ||
            this is DiagnosticEvent.SessionEnd ||
            (this is DiagnosticEvent.PlaybackError && isFatal)

    private companion object {
        const val DEFAULT_UPDATE_INTERVAL_MS = 200L
    }
}
