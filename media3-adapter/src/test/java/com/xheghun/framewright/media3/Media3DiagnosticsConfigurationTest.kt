package com.xheghun.framewright.media3

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class Media3DiagnosticsConfigurationTest {
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
