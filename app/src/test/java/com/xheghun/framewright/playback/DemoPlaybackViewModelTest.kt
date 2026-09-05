package com.xheghun.framewright.playback

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class DemoPlaybackViewModelTest {
    @Test
    fun `toggle source alternates between clear HLS and Widevine DASH`() {
        val viewModel = DemoPlaybackViewModel()

        viewModel.onAction(DemoPlaybackAction.ToggleSource)
        assertThat(viewModel.state.value.selectedSource).isEqualTo(DemoPlaybackSource.WIDEVINE_DASH)

        viewModel.onAction(DemoPlaybackAction.ToggleSource)
        assertThat(viewModel.state.value.selectedSource).isEqualTo(DemoPlaybackSource.CLEAR_HLS)
    }
}
