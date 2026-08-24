package com.xheghun.framewright.codec

import com.xheghun.analytics.CodecFormatSupport
import com.xheghun.analytics.DecoderCapabilityResolver
import com.xheghun.analytics.DecoderCapabilitySnapshot
import com.xheghun.analytics.DecoderInspectionRequest
import java.util.Locale

class FramewrightCodecInspector internal constructor(
    private val catalogSource: CodecCatalogSource,
) : DecoderCapabilityResolver {
    constructor() : this(AndroidCodecCatalogSource())

    @Volatile
    private var cachedCatalog: LoadedCodecCatalog? = null
    private val catalogLock = Any()

    fun listDecoders(): CodecInspectionResult<List<DecoderCatalogEntry>> =
        when (val loaded = loadCatalog()) {
            is CodecInspectionResult.Failure -> loaded
            is CodecInspectionResult.Success -> CodecInspectionResult.Success(loaded.data.entries)
        }

    fun inspect(request: DecoderInspectionRequest): CodecInspectionResult<DecoderCapabilitySnapshot> {
        val catalog =
            when (val loaded = loadCatalog()) {
                is CodecInspectionResult.Failure -> return loaded
                is CodecInspectionResult.Success -> loaded.data
            }
        val matchingDecoderEntries =
            catalog.entries.filter { it.decoderName.equals(request.decoderName, ignoreCase = true) }
        if (matchingDecoderEntries.isEmpty()) {
            return CodecInspectionResult.Failure(CodecInspectionError.DECODER_NOT_FOUND)
        }
        val matchingEntry =
            matchingDecoderEntries.firstOrNull { it.mimeType.equals(request.mimeType, ignoreCase = true) }
                ?: return CodecInspectionResult.Failure(CodecInspectionError.MIME_TYPE_NOT_SUPPORTED)
        val selectedFormat = request.format
        val selectedFormatSupport =
            if (request.hasInspectableVideoFormat() && selectedFormat != null) {
                catalog.selectedFormatSupport.resolve(matchingEntry, selectedFormat)
            } else {
                CodecFormatSupport.UNKNOWN
            }
        return CodecInspectionResult.Success(
            matchingEntry.capabilities.copy(selectedFormatSupport = selectedFormatSupport),
        )
    }

    override fun resolve(request: DecoderInspectionRequest): DecoderCapabilitySnapshot? =
        when (val result = inspect(request)) {
            is CodecInspectionResult.Success -> result.data
            is CodecInspectionResult.Failure -> throw CodecInspectionException(result.error, result.cause)
        }

    private fun loadCatalog(): CodecInspectionResult<LoadedCodecCatalog> {
        cachedCatalog?.let { return CodecInspectionResult.Success(it) }
        return synchronized(catalogLock) {
            cachedCatalog?.let { return@synchronized CodecInspectionResult.Success(it) }
            try {
                val loaded = catalogSource.load().normalized()
                cachedCatalog = loaded
                CodecInspectionResult.Success(loaded)
            } catch (error: Exception) {
                CodecInspectionResult.Failure(CodecInspectionError.PLATFORM_QUERY_FAILED, error)
            }
        }
    }

    private fun LoadedCodecCatalog.normalized(): LoadedCodecCatalog =
        copy(
            entries =
                entries
                    .distinctBy { "${it.decoderName.lowercase(Locale.ROOT)}:${it.mimeType.lowercase(Locale.ROOT)}" }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, DecoderCatalogEntry::decoderName).thenBy { it.mimeType }),
        )

    private fun DecoderInspectionRequest.hasInspectableVideoFormat(): Boolean =
        mimeType.startsWith("video/", ignoreCase = true) && format?.let { it.width != null && it.height != null } == true
}

internal fun interface SelectedFormatSupportResolver {
    fun resolve(
        entry: DecoderCatalogEntry,
        format: com.xheghun.analytics.FormatSnapshot,
    ): CodecFormatSupport
}

internal data class LoadedCodecCatalog(
    val entries: List<DecoderCatalogEntry>,
    val selectedFormatSupport: SelectedFormatSupportResolver,
)

internal fun interface CodecCatalogSource {
    fun load(): LoadedCodecCatalog
}
