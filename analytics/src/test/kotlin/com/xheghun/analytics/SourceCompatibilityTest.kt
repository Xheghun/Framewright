package com.xheghun.analytics

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class SourceCompatibilityTest {
    @Test
    fun `existing trailing lambda pipeline construction remains valid`() {
        var droppedEvent: DiagnosticEvent? = null
        val pipeline = DiagnosticEventPipeline(SessionAggregator(), 1) { droppedEvent = it }

        assertThat(pipeline.droppedLiveEventCount).isEqualTo(0)
        assertThat(droppedEvent).isEqualTo(null)
    }
}
