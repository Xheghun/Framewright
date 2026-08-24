package com.xheghun.framewright.codec

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Range
import com.xheghun.analytics.CodecClassificationSource
import com.xheghun.analytics.CodecFormatSupport
import com.xheghun.analytics.CodecImplementationType
import com.xheghun.analytics.CodecProfileLevelSnapshot
import com.xheghun.analytics.DecoderCapabilitySnapshot
import com.xheghun.analytics.DoubleRangeSnapshot
import com.xheghun.analytics.FormatSnapshot
import com.xheghun.analytics.IntRangeSnapshot
import com.xheghun.analytics.VideoCodecCapabilitiesSnapshot
import java.util.Locale

internal class AndroidCodecCatalogSource : CodecCatalogSource {
    override fun load(): LoadedCodecCatalog {
        val capabilitiesByEntry = mutableMapOf<EntryKey, MediaCodecInfo.CodecCapabilities>()
        val entries =
            MediaCodecList(MediaCodecList.ALL_CODECS)
                .codecInfos
                .asSequence()
                .filterNot(MediaCodecInfo::isEncoder)
                .flatMap { codecInfo ->
                    codecInfo.supportedTypes.asSequence().mapNotNull { mimeType ->
                        val capabilities = runCatching { codecInfo.getCapabilitiesForType(mimeType) }.getOrNull() ?: return@mapNotNull null
                        val key = EntryKey(codecInfo.name, mimeType)
                        capabilitiesByEntry[key] = capabilities
                        DecoderCatalogEntry(
                            decoderName = codecInfo.name,
                            mimeType = mimeType,
                            capabilities = codecInfo.toSnapshot(capabilities),
                        )
                    }
                }.toList()
        return LoadedCodecCatalog(entries) { entry, format ->
            val capabilities = capabilitiesByEntry[EntryKey(entry.decoderName, entry.mimeType)]
            capabilities?.selectedFormatSupport(entry.mimeType, format) ?: CodecFormatSupport.UNKNOWN
        }
    }
}

private data class EntryKey(
    val decoderName: String,
    val mimeType: String,
)

private fun MediaCodecInfo.toSnapshot(capabilities: MediaCodecInfo.CodecCapabilities): DecoderCapabilitySnapshot {
    val classification = implementationClassification()
    return DecoderCapabilitySnapshot(
        canonicalName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) canonicalName else null,
        implementationType = classification.first,
        classificationSource = classification.second,
        isVendor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isVendor else null,
        isAlias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isAlias else null,
        profileLevels =
            capabilities.profileLevels
                .map { CodecProfileLevelSnapshot(it.profile, it.level) }
                .distinct()
                .sortedWith(compareBy(CodecProfileLevelSnapshot::profile, CodecProfileLevelSnapshot::level)),
        supportsAdaptivePlayback = capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback),
        supportsSecurePlayback = capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback),
        supportsTunneledPlayback = capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback),
        maxSupportedInstances = capabilities.maxSupportedInstances.takeIf { it > 0 },
        selectedFormatSupport = CodecFormatSupport.UNKNOWN,
        videoCapabilities = capabilities.videoCapabilities?.toSnapshot(),
    )
}

private fun MediaCodecInfo.implementationClassification(): Pair<CodecImplementationType, CodecClassificationSource> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        return classifyCodecImplementation(
            sdkInt = Build.VERSION.SDK_INT,
            codecName = name,
            isHardwareAccelerated = isHardwareAccelerated,
            isSoftwareOnly = isSoftwareOnly,
        )
    }
    return classifyCodecImplementation(sdkInt = Build.VERSION.SDK_INT, codecName = name)
}

internal fun classifyCodecImplementation(
    sdkInt: Int,
    codecName: String,
    isHardwareAccelerated: Boolean? = null,
    isSoftwareOnly: Boolean? = null,
): Pair<CodecImplementationType, CodecClassificationSource> {
    if (sdkInt >= Build.VERSION_CODES.Q) {
        val type =
            when {
                isHardwareAccelerated == true -> CodecImplementationType.HARDWARE_ACCELERATED
                isSoftwareOnly == true -> CodecImplementationType.SOFTWARE_ONLY
                else -> CodecImplementationType.UNKNOWN
            }
        return type to CodecClassificationSource.PLATFORM
    }
    val normalizedName = codecName.lowercase(Locale.ROOT)
    val isKnownSoftware =
        normalizedName.startsWith("omx.google.") ||
            normalizedName.startsWith("omx.ffmpeg.") ||
            normalizedName.startsWith("c2.android.") ||
            normalizedName.startsWith("c2.google.")
    val looksHardwareBacked = normalizedName.startsWith("omx.") || normalizedName.startsWith("c2.")
    val type =
        when {
            isKnownSoftware -> CodecImplementationType.SOFTWARE_ONLY
            looksHardwareBacked -> CodecImplementationType.HARDWARE_ACCELERATED
            else -> CodecImplementationType.UNKNOWN
        }
    return type to CodecClassificationSource.NAME_HEURISTIC
}

private fun MediaCodecInfo.VideoCapabilities.toSnapshot() =
    VideoCodecCapabilitiesSnapshot(
        supportedWidths = supportedWidths.toIntSnapshot(),
        supportedHeights = supportedHeights.toIntSnapshot(),
        supportedBitratesBps = bitrateRange.toIntSnapshot(),
        supportedFrameRates = supportedFrameRates.toDoubleSnapshot(),
        widthAlignment = widthAlignment,
        heightAlignment = heightAlignment,
    )

private fun MediaCodecInfo.CodecCapabilities.selectedFormatSupport(
    mimeType: String,
    format: FormatSnapshot,
): CodecFormatSupport =
    runCatching {
        val mediaFormat = MediaFormat.createVideoFormat(mimeType, requireNotNull(format.width), requireNotNull(format.height))
        if (format.bitrate > 0) mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, format.bitrate)
        if (isFormatSupported(mediaFormat)) CodecFormatSupport.SUPPORTED else CodecFormatSupport.UNSUPPORTED
    }.getOrDefault(CodecFormatSupport.UNKNOWN)

private fun Range<Int>.toIntSnapshot() = IntRangeSnapshot(lower, upper)

private fun Range<Int>.toDoubleSnapshot() = DoubleRangeSnapshot(lower.toDouble(), upper.toDouble())
