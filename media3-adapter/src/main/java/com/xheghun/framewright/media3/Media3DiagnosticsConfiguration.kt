package com.xheghun.framewright.media3

import java.net.URI

fun interface DiagnosticUriSanitizer {
    fun sanitize(uri: String): String
}

class Media3DiagnosticsConfiguration(
    val uriSanitizer: DiagnosticUriSanitizer = DiagnosticUriSanitizer(::redactSensitiveUriParts),
    val includeErrorMessages: Boolean = false,
    val onDiagnosticsError: (Throwable) -> Unit = {},
)

private fun redactSensitiveUriParts(value: String): String {
    val uri = runCatching { URI(value) }.getOrNull() ?: return "<redacted>"
    if (!uri.isAbsolute || uri.scheme.isNullOrBlank()) return "<redacted>"

    return runCatching {
        URI(uri.scheme, null, uri.host, uri.port, uri.rawPath, null, null).toASCIIString()
    }.getOrDefault("<redacted>")
}
