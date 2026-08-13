package com.xheghun.framewright.media3

import com.xheghun.analytics.DiagnosticEventSink
import java.net.URI

fun interface DiagnosticUriSanitizer {
    fun sanitize(uri: String): String
}

class Media3DiagnosticsConfiguration(
    val uriSanitizer: DiagnosticUriSanitizer = DiagnosticUriSanitizer(::redactSensitiveUriParts),
    val includeErrorMessages: Boolean = false,
    val eventSinks: List<DiagnosticEventSink> = emptyList(),
    val onDiagnosticsError: (Throwable) -> Unit = {},
) {
    constructor(
        uriSanitizer: DiagnosticUriSanitizer,
        includeErrorMessages: Boolean,
        onDiagnosticsError: (Throwable) -> Unit,
    ) : this(uriSanitizer, includeErrorMessages, emptyList(), onDiagnosticsError)
}

private fun redactSensitiveUriParts(value: String): String {
    val uri = runCatching { URI(value) }.getOrNull() ?: return "<redacted>"
    if (!uri.isAbsolute || uri.scheme.isNullOrBlank()) return "<redacted>"

    return runCatching {
        URI(uri.scheme, null, uri.host, uri.port, uri.rawPath, null, null).toASCIIString()
    }.getOrDefault("<redacted>")
}
