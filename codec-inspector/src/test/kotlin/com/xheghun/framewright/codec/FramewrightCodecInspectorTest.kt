package com.xheghun.framewright.codec

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.xheghun.analytics.CodecClassificationSource
import com.xheghun.analytics.CodecFormatSupport
import com.xheghun.analytics.CodecImplementationType
import com.xheghun.analytics.DecoderCapabilitySnapshot
import com.xheghun.analytics.DecoderInspectionRequest
import com.xheghun.analytics.FormatSnapshot
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FramewrightCodecInspectorTest {
    @Test
    fun `catalog is sorted deduplicated and loaded once`() {
        val source =
            RecordingCatalogSource(
                entries =
                    listOf(
                        entry("vendor.decoder", "video/hevc"),
                        entry("android.decoder", "video/avc"),
                        entry("VENDOR.DECODER", "VIDEO/HEVC"),
                    ),
            )
        val inspector = FramewrightCodecInspector(source)

        val first = inspector.listDecoders() as CodecInspectionResult.Success
        val second = inspector.listDecoders() as CodecInspectionResult.Success

        assertThat(first.data.map { "${it.decoderName}:${it.mimeType}" })
            .containsExactly("android.decoder:video/avc", "vendor.decoder:video/hevc")
        assertThat(second.data).isEqualTo(first.data)
        assertThat(source.loadCount).isEqualTo(1)
    }

    @Test
    fun `selected video inspection uses cached platform format verdict`() {
        val source =
            RecordingCatalogSource(
                entries = listOf(entry("vendor.decoder", "video/avc")),
                selectedFormatSupport = CodecFormatSupport.SUPPORTED,
            )
        val inspector = FramewrightCodecInspector(source)

        val result =
            inspector.inspect(
                DecoderInspectionRequest(
                    decoderName = "vendor.decoder",
                    mimeType = "video/avc",
                    format = FormatSnapshot(1_920, 1_080, 5_000_000, "video/avc", "avc1"),
                ),
            ) as CodecInspectionResult.Success

        assertThat(result.data.selectedFormatSupport).isEqualTo(CodecFormatSupport.SUPPORTED)
        assertThat(source.formatQueryCount).isEqualTo(1)
    }

    @Test
    fun `incomplete video and audio formats remain unknown without platform claims`() {
        val source =
            RecordingCatalogSource(entries = listOf(entry("video.decoder", "video/avc"), entry("audio.decoder", "audio/mp4a-latm")))
        val inspector = FramewrightCodecInspector(source)

        val incompleteVideo =
            inspector.inspect(
                DecoderInspectionRequest("video.decoder", "video/avc", FormatSnapshot(bitrate = 2_000_000)),
            ) as CodecInspectionResult.Success
        val audio =
            inspector.inspect(
                DecoderInspectionRequest("audio.decoder", "audio/mp4a-latm", FormatSnapshot(bitrate = 128_000)),
            ) as CodecInspectionResult.Success

        assertThat(incompleteVideo.data.selectedFormatSupport).isEqualTo(CodecFormatSupport.UNKNOWN)
        assertThat(audio.data.selectedFormatSupport).isEqualTo(CodecFormatSupport.UNKNOWN)
        assertThat(source.formatQueryCount).isEqualTo(0)
    }

    @Test
    fun `missing decoder and unsupported mime return distinct failures`() {
        val inspector = FramewrightCodecInspector(RecordingCatalogSource(entries = listOf(entry("vendor.decoder", "video/avc"))))

        val missingDecoder = inspector.inspect(DecoderInspectionRequest("missing.decoder", "video/avc"))
        val missingMime = inspector.inspect(DecoderInspectionRequest("vendor.decoder", "video/hevc"))

        assertThat((missingDecoder as CodecInspectionResult.Failure).error).isEqualTo(CodecInspectionError.DECODER_NOT_FOUND)
        assertThat((missingMime as CodecInspectionResult.Failure).error).isEqualTo(CodecInspectionError.MIME_TYPE_NOT_SUPPORTED)
    }

    @Test
    fun `platform catalog failure is typed and resolver exposes it to host error handling`() {
        val platformFailure = IllegalStateException("Codec service unavailable")
        val inspector = FramewrightCodecInspector(CodecCatalogSource { throw platformFailure })

        val result = inspector.listDecoders() as CodecInspectionResult.Failure
        val thrown =
            assertThrows<CodecInspectionException> {
                inspector.resolve(DecoderInspectionRequest("vendor.decoder", "video/avc"))
            }

        assertThat(result.error).isEqualTo(CodecInspectionError.PLATFORM_QUERY_FAILED)
        assertThat(result.cause).isEqualTo(platformFailure)
        assertThat(thrown.inspectionError).isEqualTo(CodecInspectionError.PLATFORM_QUERY_FAILED)
        assertThat(thrown.cause).isEqualTo(platformFailure)
    }

    @Test
    fun `api 29 classification trusts platform flags`() {
        val hardware = classifyCodecImplementation(29, "unusual.name", isHardwareAccelerated = true, isSoftwareOnly = false)
        val software = classifyCodecImplementation(29, "vendor.name", isHardwareAccelerated = false, isSoftwareOnly = true)
        val unknown = classifyCodecImplementation(29, "vendor.name", isHardwareAccelerated = false, isSoftwareOnly = false)

        assertThat(hardware).isEqualTo(CodecImplementationType.HARDWARE_ACCELERATED to CodecClassificationSource.PLATFORM)
        assertThat(software).isEqualTo(CodecImplementationType.SOFTWARE_ONLY to CodecClassificationSource.PLATFORM)
        assertThat(unknown).isEqualTo(CodecImplementationType.UNKNOWN to CodecClassificationSource.PLATFORM)
    }

    @Test
    fun `pre api 29 classification uses conservative name heuristics`() {
        val platformSoftware = classifyCodecImplementation(28, "c2.android.avc.decoder")
        val vendorHardware = classifyCodecImplementation(28, "OMX.qcom.video.decoder.avc")
        val unknown = classifyCodecImplementation(28, "custom.decoder")

        assertThat(platformSoftware)
            .isEqualTo(CodecImplementationType.SOFTWARE_ONLY to CodecClassificationSource.NAME_HEURISTIC)
        assertThat(vendorHardware)
            .isEqualTo(CodecImplementationType.HARDWARE_ACCELERATED to CodecClassificationSource.NAME_HEURISTIC)
        assertThat(unknown).isEqualTo(CodecImplementationType.UNKNOWN to CodecClassificationSource.NAME_HEURISTIC)
    }

    @Test
    fun `nullable resolver contract returns capability for known decoder`() {
        val inspector = FramewrightCodecInspector(RecordingCatalogSource(entries = listOf(entry("vendor.decoder", "video/avc"))))

        val capability = inspector.resolve(DecoderInspectionRequest("vendor.decoder", "video/avc"))

        assertThat(capability?.implementationType).isEqualTo(CodecImplementationType.HARDWARE_ACCELERATED)
        assertThat(capability?.videoCapabilities).isNull()
    }

    private fun entry(
        decoderName: String,
        mimeType: String,
    ) = DecoderCatalogEntry(
        decoderName = decoderName,
        mimeType = mimeType,
        capabilities =
            DecoderCapabilitySnapshot(
                canonicalName = decoderName,
                implementationType = CodecImplementationType.HARDWARE_ACCELERATED,
                classificationSource = CodecClassificationSource.PLATFORM,
                isVendor = true,
                isAlias = false,
                supportsAdaptivePlayback = true,
                supportsSecurePlayback = false,
                supportsTunneledPlayback = false,
                maxSupportedInstances = 8,
            ),
    )

    private class RecordingCatalogSource(
        private val entries: List<DecoderCatalogEntry>,
        private val selectedFormatSupport: CodecFormatSupport = CodecFormatSupport.UNKNOWN,
    ) : CodecCatalogSource {
        var loadCount = 0
        var formatQueryCount = 0

        override fun load(): LoadedCodecCatalog {
            loadCount++
            return LoadedCodecCatalog(entries) { _, _ ->
                formatQueryCount++
                selectedFormatSupport
            }
        }
    }
}
