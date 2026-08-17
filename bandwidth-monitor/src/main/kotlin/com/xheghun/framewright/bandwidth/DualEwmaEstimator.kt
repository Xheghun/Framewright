package com.xheghun.framewright.bandwidth

internal data class EwmaEstimate(
    val instantaneousBps: Long,
    val fastEstimateBps: Long,
    val slowEstimateBps: Long,
    val drivingEstimateBps: Long,
    val totalSampleCount: Long,
)

internal class DualEwmaEstimator(
    private val configuration: BandwidthMonitorConfiguration,
) {
    private var fastEstimate = 0.0
    private var slowEstimate = 0.0
    private var sampleCount = 0L

    val drivingEstimateBps: Long
        get() =
            if (sampleCount < configuration.minimumSamplesForEstimate) {
                configuration.initialBitrateEstimateBps
            } else {
                minimumEstimate()
            }

    val fastEstimateBps: Long get() = fastEstimate.asBitrate(configuration.initialBitrateEstimateBps)
    val slowEstimateBps: Long get() = slowEstimate.asBitrate(configuration.initialBitrateEstimateBps)

    fun addSample(
        transferredBytes: Long,
        durationMs: Long,
    ): EwmaEstimate? {
        if (transferredBytes <= 0 || durationMs <= 0) return null
        val instantaneous = ((transferredBytes.toDouble() * BITS_PER_BYTE * MILLIS_PER_SECOND) / durationMs).asBitrate()
        fastEstimate = updateEwma(fastEstimate, instantaneous.toDouble(), configuration.fastAlpha)
        slowEstimate = updateEwma(slowEstimate, instantaneous.toDouble(), configuration.slowAlpha)
        sampleCount++
        return EwmaEstimate(
            instantaneousBps = instantaneous,
            fastEstimateBps = fastEstimate.asBitrate(),
            slowEstimateBps = slowEstimate.asBitrate(),
            drivingEstimateBps = drivingEstimateBps,
            totalSampleCount = sampleCount,
        )
    }

    private fun minimumEstimate(): Long = minOf(fastEstimate, slowEstimate).asBitrate(configuration.initialBitrateEstimateBps)

    private fun updateEwma(
        current: Double,
        sample: Double,
        alpha: Double,
    ): Double = if (current == 0.0) sample else alpha * sample + (1.0 - alpha) * current
}

private const val BITS_PER_BYTE = 8.0
private const val MILLIS_PER_SECOND = 1_000.0

private fun Double.asBitrate(fallback: Long = Long.MAX_VALUE): Long =
    when {
        isNaN() || this <= 0.0 -> fallback
        this >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
        else -> toLong().coerceAtLeast(1)
    }
