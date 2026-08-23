package com.xheghun.framewright.codec

import com.xheghun.analytics.DecoderCapabilitySnapshot

data class DecoderCatalogEntry(
    val decoderName: String,
    val mimeType: String,
    val capabilities: DecoderCapabilitySnapshot,
) {
    init {
        require(decoderName.isNotBlank()) { "decoderName must not be blank" }
        require(mimeType.isNotBlank()) { "mimeType must not be blank" }
    }
}

enum class CodecInspectionError {
    DECODER_NOT_FOUND,
    MIME_TYPE_NOT_SUPPORTED,
    PLATFORM_QUERY_FAILED,
}

sealed interface CodecInspectionResult<out T> {
    data class Success<T>(
        val data: T,
    ) : CodecInspectionResult<T>

    data class Failure(
        val error: CodecInspectionError,
        val cause: Throwable? = null,
    ) : CodecInspectionResult<Nothing>
}

class CodecInspectionException(
    val inspectionError: CodecInspectionError,
    cause: Throwable? = null,
) : IllegalStateException("Codec inspection failed: $inspectionError", cause)
