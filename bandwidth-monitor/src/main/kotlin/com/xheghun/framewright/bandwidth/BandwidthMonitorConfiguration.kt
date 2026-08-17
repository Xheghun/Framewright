package com.xheghun.framewright.bandwidth

/** Controls the estimator without changing Media3 or analytics lifecycle behavior. */
data class BandwidthMonitorConfiguration(
    val fastAlpha: Double = 0.5,
    val slowAlpha: Double = 0.1,
    val initialBitrateEstimateBps: Long = 1_000_000,
    val minimumSamplesForEstimate: Int = 3,
    val fullConfidenceSampleCount: Int = 5,
    val representativeTransferSizeBytes: Long = 64 * 1_024,
) {
    init {
        require(fastAlpha > 0.0 && fastAlpha <= 1.0) { "fastAlpha must be in (0, 1]" }
        require(slowAlpha > 0.0 && slowAlpha <= 1.0) { "slowAlpha must be in (0, 1]" }
        require(fastAlpha >= slowAlpha) { "fastAlpha must be greater than or equal to slowAlpha" }
        require(initialBitrateEstimateBps > 0) { "initialBitrateEstimateBps must be greater than zero" }
        require(minimumSamplesForEstimate > 0) { "minimumSamplesForEstimate must be greater than zero" }
        require(fullConfidenceSampleCount >= minimumSamplesForEstimate) {
            "fullConfidenceSampleCount must be greater than or equal to minimumSamplesForEstimate"
        }
        require(representativeTransferSizeBytes > 0) { "representativeTransferSizeBytes must be greater than zero" }
    }
}

/** A thread-safe point-in-time view for diagnostics UI and host logging. */
data class BandwidthEstimateSnapshot(
    val customEstimateBps: Long,
    val fastEstimateBps: Long,
    val slowEstimateBps: Long,
    val defaultEstimateBps: Long,
    val confidence: Double,
    val sessionSampleCount: Int,
)
