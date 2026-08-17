package com.xheghun.framewright.bandwidth

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class DualEwmaEstimatorTest {
    @Test
    fun `uses initial estimate until minimum sample count is reached`() {
        val estimator = DualEwmaEstimator(BandwidthMonitorConfiguration(minimumSamplesForEstimate = 3))

        val first = requireNotNull(estimator.addSample(100_000, 1_000))
        val second = requireNotNull(estimator.addSample(200_000, 1_000))
        val third = requireNotNull(estimator.addSample(200_000, 1_000))

        assertThat(first.instantaneousBps).isEqualTo(800_000)
        assertThat(first.drivingEstimateBps).isEqualTo(1_000_000)
        assertThat(second.fastEstimateBps).isEqualTo(1_200_000)
        assertThat(second.slowEstimateBps).isEqualTo(880_000)
        assertThat(second.drivingEstimateBps).isEqualTo(1_000_000)
        assertThat(third.fastEstimateBps).isEqualTo(1_400_000)
        assertThat(third.slowEstimateBps).isEqualTo(952_000)
        assertThat(third.drivingEstimateBps).isEqualTo(952_000)
    }

    @Test
    fun `rejects empty and zero-duration samples without changing state`() {
        val estimator = DualEwmaEstimator(BandwidthMonitorConfiguration())

        assertThat(estimator.addSample(0, 1_000)).isEqualTo(null)
        assertThat(estimator.addSample(10_000, 0)).isEqualTo(null)
        assertThat(estimator.drivingEstimateBps).isEqualTo(1_000_000)
    }

    @Test
    fun `saturates estimates that exceed long range`() {
        val estimator =
            DualEwmaEstimator(
                BandwidthMonitorConfiguration(minimumSamplesForEstimate = 1, fullConfidenceSampleCount = 1),
            )

        val estimate = requireNotNull(estimator.addSample(Long.MAX_VALUE, 1))

        assertThat(estimate.instantaneousBps).isEqualTo(Long.MAX_VALUE)
        assertThat(estimate.drivingEstimateBps).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `configuration rejects unsafe estimator values`() {
        assertFailure { BandwidthMonitorConfiguration(fastAlpha = 0.0) }
        assertFailure { BandwidthMonitorConfiguration(fastAlpha = 0.1, slowAlpha = 0.5) }
        assertFailure { BandwidthMonitorConfiguration(initialBitrateEstimateBps = 0) }
        assertFailure { BandwidthMonitorConfiguration(minimumSamplesForEstimate = 4, fullConfidenceSampleCount = 3) }
        assertFailure { BandwidthMonitorConfiguration(representativeTransferSizeBytes = 0) }
    }
}
