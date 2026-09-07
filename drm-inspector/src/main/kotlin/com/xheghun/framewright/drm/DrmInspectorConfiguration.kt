package com.xheghun.framewright.drm

data class DrmInspectorConfiguration(
    val includeErrorMessages: Boolean = false,
    val onDiagnosticsError: (Throwable) -> Unit = {},
)
