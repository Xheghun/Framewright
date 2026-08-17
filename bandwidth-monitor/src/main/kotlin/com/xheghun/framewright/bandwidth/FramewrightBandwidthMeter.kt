package com.xheghun.framewright.bandwidth

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.xheghun.analytics.AbstractPlayerEventSource
import com.xheghun.analytics.DiagnosticEvent
import com.xheghun.analytics.DiagnosticEventMetadata
import com.xheghun.analytics.DiagnosticEventPipeline
import java.util.IdentityHashMap
import java.util.UUID

/**
 * Drives Media3 selection with a dual-EWMA estimate and publishes comparison samples when attached
 * as a Framewright analytics contributor.
 */
@UnstableApi
class FramewrightBandwidthMeter internal constructor(
    private val configuration: BandwidthMonitorConfiguration = BandwidthMonitorConfiguration(),
    private val comparisonMeter: BandwidthMeter,
    private val clock: BandwidthClock,
    private val eventIdGenerator: () -> String,
    private val onDiagnosticsError: (Throwable) -> Unit = {},
) : AbstractPlayerEventSource(),
    BandwidthMeter,
    TransferListener {
    constructor(
        context: Context,
        configuration: BandwidthMonitorConfiguration = BandwidthMonitorConfiguration(),
        onDiagnosticsError: (Throwable) -> Unit = {},
    ) : this(
        configuration = configuration,
        comparisonMeter = DefaultBandwidthMeter.Builder(context.applicationContext).build(),
        clock = SystemBandwidthClock,
        eventIdGenerator = { UUID.randomUUID().toString() },
        onDiagnosticsError = onDiagnosticsError,
    )

    private val comparisonTransferListener: TransferListener by lazy {
        requireNotNull(comparisonMeter.transferListener) { "Comparison bandwidth meter must provide a TransferListener" }
    }
    private val eventDispatcher = BandwidthMeter.EventListener.EventDispatcher()
    private val lock = Any()
    private val estimator = DualEwmaEstimator(configuration)
    private val activeTransfers = IdentityHashMap<DataSource, ActiveTransfer>()
    private var pipeline: DiagnosticEventPipeline? = null
    private var sessionId: String? = null
    private var sessionSampleCount = 0
    private var lastConfidence = 0.0

    // Returns the latest estimates without exposing mutable estimator state.
    val currentEstimate: BandwidthEstimateSnapshot
        get() =
            synchronized(lock) {
                BandwidthEstimateSnapshot(
                    customEstimateBps = estimator.drivingEstimateBps,
                    fastEstimateBps = estimator.fastEstimateBps,
                    slowEstimateBps = estimator.slowEstimateBps,
                    defaultEstimateBps = comparisonMeter.bitrateEstimate.coerceAtLeast(1),
                    confidence = lastConfidence,
                    sessionSampleCount = sessionSampleCount,
                )
            }

    override fun getBitrateEstimate(): Long = synchronized(lock) { estimator.drivingEstimateBps }

    override fun getTimeToFirstByteEstimateUs(): Long = comparisonMeter.timeToFirstByteEstimateUs

    override fun getTransferListener(): TransferListener = this

    override fun addEventListener(
        eventHandler: Handler,
        eventListener: BandwidthMeter.EventListener,
    ) = eventDispatcher.addListener(eventHandler, eventListener)

    override fun removeEventListener(eventListener: BandwidthMeter.EventListener) = eventDispatcher.removeListener(eventListener)

    override fun onAttach(
        pipeline: DiagnosticEventPipeline,
        sessionId: String,
    ) {
        synchronized(lock) {
            this.pipeline = pipeline
            this.sessionId = sessionId
            sessionSampleCount = 0
            lastConfidence = 0.0
            activeTransfers.clear()
        }
    }

    override fun onDetach() {
        synchronized(lock) {
            pipeline = null
            sessionId = null
            activeTransfers.clear()
        }
    }

    override fun onTransferInitializing(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) = comparisonTransferListener.onTransferInitializing(source, dataSpec, isNetwork)

    override fun onTransferStart(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) {
        comparisonTransferListener.onTransferStart(source, dataSpec, isNetwork)
        if (!isNetwork) return
        synchronized(lock) {
            activeTransfers[source] = ActiveTransfer(clock.elapsedRealtimeMs())
        }
    }

    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int,
    ) {
        comparisonTransferListener.onBytesTransferred(source, dataSpec, isNetwork, bytesTransferred)
        if (!isNetwork || bytesTransferred <= 0) return
        synchronized(lock) {
            activeTransfers[source]?.addBytes(bytesTransferred)
        }
    }

    override fun onTransferEnd(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) {
        comparisonTransferListener.onTransferEnd(source, dataSpec, isNetwork)
        if (!isNetwork) return
        runCatching { finishTransfer(source) }.onFailure(::reportDiagnosticsError)
    }

    private fun finishTransfer(source: DataSource) {
        val delivery =
            synchronized(lock) {
                val transfer = activeTransfers.remove(source) ?: return
                val durationMs = (clock.elapsedRealtimeMs() - transfer.startedAtMs).coerceAtLeast(0)
                val estimate = estimator.addSample(transfer.transferredBytes, durationMs) ?: return
                val attachedPipeline = pipeline
                val attachedSessionId = sessionId
                val sessionCount =
                    if (attachedPipeline != null && attachedSessionId != null) {
                        if (sessionSampleCount < Int.MAX_VALUE) sessionSampleCount++
                        sessionSampleCount
                    } else {
                        sessionSampleCount
                    }
                val confidence = confidence(sessionCount, transfer.transferredBytes)
                lastConfidence = confidence
                Delivery(
                    elapsedMs = durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    transferredBytes = transfer.transferredBytes,
                    estimate = estimate,
                    defaultEstimateBps = comparisonMeter.bitrateEstimate.coerceAtLeast(1),
                    confidence = confidence,
                    pipeline = attachedPipeline,
                    sessionId = attachedSessionId,
                    timestampMs = clock.wallTimeMs(),
                    elapsedRealtimeMs = clock.elapsedRealtimeMs(),
                )
            }
        eventDispatcher.bandwidthSample(delivery.elapsedMs, delivery.transferredBytes, delivery.estimate.drivingEstimateBps)
        val attachedPipeline = delivery.pipeline ?: return
        val attachedSessionId = delivery.sessionId ?: return
        attachedPipeline.tryPublish(
            DiagnosticEvent.BandwidthSample(
                metadata =
                    DiagnosticEventMetadata(
                        sessionId = attachedSessionId,
                        eventId = eventIdGenerator(),
                        timestampMs = delivery.timestampMs,
                        elapsedRealtimeMs = delivery.elapsedRealtimeMs,
                    ),
                segmentSizeBytes = delivery.transferredBytes,
                downloadDurationMs = delivery.elapsedMs.toLong(),
                instantaneousBps = delivery.estimate.instantaneousBps,
                fastEstimateBps = delivery.estimate.fastEstimateBps,
                slowEstimateBps = delivery.estimate.slowEstimateBps,
                defaultEstimateBps = delivery.defaultEstimateBps,
                confidence = delivery.confidence,
            ),
        )
    }

    private fun confidence(
        sampleCount: Int,
        transferredBytes: Long,
    ): Double =
        minOf(
            sampleCount.toDouble() / configuration.fullConfidenceSampleCount,
            transferredBytes.toDouble() / configuration.representativeTransferSizeBytes,
        ).coerceIn(0.0, 1.0)

    private fun reportDiagnosticsError(error: Throwable) {
        runCatching { onDiagnosticsError(error) }
    }

    private data class ActiveTransfer(
        val startedAtMs: Long,
        var transferredBytes: Long = 0,
    ) {
        fun addBytes(bytes: Int) {
            transferredBytes =
                if (Long.MAX_VALUE - transferredBytes < bytes) Long.MAX_VALUE else transferredBytes + bytes
        }
    }

    private data class Delivery(
        val elapsedMs: Int,
        val transferredBytes: Long,
        val estimate: EwmaEstimate,
        val defaultEstimateBps: Long,
        val confidence: Double,
        val pipeline: DiagnosticEventPipeline?,
        val sessionId: String?,
        val timestampMs: Long,
        val elapsedRealtimeMs: Long,
    )
}

internal interface BandwidthClock {
    fun wallTimeMs(): Long

    fun elapsedRealtimeMs(): Long
}

private object SystemBandwidthClock : BandwidthClock {
    override fun wallTimeMs(): Long = System.currentTimeMillis()

    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}
