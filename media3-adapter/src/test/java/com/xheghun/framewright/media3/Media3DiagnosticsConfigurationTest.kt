package com.xheghun.framewright.media3

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.xheghun.analytics.DiagnosticEvent
import com.xheghun.analytics.DiagnosticEventSink
import org.junit.jupiter.api.Test

class Media3DiagnosticsConfigurationTest {
    @Test
    fun `existing trailing lambda configuration construction remains valid`() {
        var reportedMessage: String? = null
        val configuration = Media3DiagnosticsConfiguration { error -> reportedMessage = error.message }

        configuration.onDiagnosticsError(IllegalStateException("diagnostic failure"))

        assertThat(reportedMessage).isEqualTo("diagnostic failure")
    }

    @Test
    fun `configuration retains event sinks`() {
        val sink = DiagnosticEventSink { _: DiagnosticEvent -> }

        val configuration = Media3DiagnosticsConfiguration(eventSinks = listOf(sink))

        assertThat(configuration.eventSinks).isEqualTo(listOf(sink))
    }

    @Test
    fun `default sanitizer removes credentials query and fragment`() {
        val sanitizer = Media3DiagnosticsConfiguration().uriSanitizer

        val sanitized = sanitizer.sanitize("https://user:password@example.test:8443/video/master.m3u8?token=secret#chapter")

        assertThat(sanitized).isEqualTo("https://example.test:8443/video/master.m3u8")
    }

    @Test
    fun `default sanitizer rejects values without a URI scheme`() {
        val sanitizer = Media3DiagnosticsConfiguration().uriSanitizer

        assertThat(sanitizer.sanitize("not-a-uri")).isEqualTo("<redacted>")
    }
}
