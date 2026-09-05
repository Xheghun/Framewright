package com.xheghun.framewright.playback

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class DemoPlaybackSource { CLEAR_HLS, WIDEVINE_DASH }

data class DemoPlaybackState(
    val selectedSource: DemoPlaybackSource = DemoPlaybackSource.CLEAR_HLS,
)

sealed interface DemoPlaybackAction {
    data object ToggleSource : DemoPlaybackAction
}

class DemoPlaybackViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(DemoPlaybackState())
    val state = mutableState.asStateFlow()

    fun onAction(action: DemoPlaybackAction) {
        when (action) {
            DemoPlaybackAction.ToggleSource ->
                mutableState.update { state ->
                    state.copy(
                        selectedSource =
                            when (state.selectedSource) {
                                DemoPlaybackSource.CLEAR_HLS -> DemoPlaybackSource.WIDEVINE_DASH
                                DemoPlaybackSource.WIDEVINE_DASH -> DemoPlaybackSource.CLEAR_HLS
                            },
                    )
                }
        }
    }
}
