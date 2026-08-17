package com.xheghun.framewright.bandwidth

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xheghun.analytics.CodecResult
import com.xheghun.analytics.DiagnosticEvent
import com.xheghun.analytics.DiagnosticEventJsonCodec
import com.xheghun.analytics.DiagnosticEventMetadata
import com.xheghun.analytics.DiagnosticEventPipeline
import com.xheghun.framewright.storage.FramewrightStorage
import com.xheghun.framewright.storage.StorageResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@UnstableApi
@RunWith(AndroidJUnit4::class)
class FramewrightBandwidthMeterIntegrationTest {
    @Test
    fun completedNetworkTransferPublishesComparisonSampleAndListenerEvent() {
        val clock = MutableBandwidthClock()
        val comparisonMeter = RecordingComparisonMeter()
        val meter = meter(clock, comparisonMeter)
        val pipeline = DiagnosticEventPipeline()
        val listenerSample = mutableListOf<Triple<Int, Long, Long>>()
        val listenerCalled = CountDownLatch(1)
        meter.addEventListener(
            Handler(Looper.getMainLooper()),
            BandwidthMeter.EventListener { elapsedMs, bytes, estimate ->
                listenerSample += Triple(elapsedMs, bytes, estimate)
                listenerCalled.countDown()
            },
        )
        meter.attach(pipeline, "playback-session")

        completeTransfer(meter, newSource(), clock, bytes = 65_536, durationMs = 1_000)

        val event = pipeline.snapshot("playback-session").events.single() as DiagnosticEvent.BandwidthSample
        assertEquals(65_536, event.segmentSizeBytes)
        assertEquals(1_000, event.downloadDurationMs)
        assertEquals(524_288, event.instantaneousBps)
        assertEquals(524_288, event.fastEstimateBps)
        assertEquals(524_288, event.slowEstimateBps)
        assertEquals(2_000_000, event.defaultEstimateBps)
        assertEquals(0.2, event.confidence, 0.0)
        assertTrue(listenerCalled.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(Triple(1_000, 65_536L, 1_000_000L)), listenerSample)
        assertEquals(1, meter.currentEstimate.sessionSampleCount)
    }

    @Test
    fun removedBandwidthListenerDoesNotReceiveCompletedTransfer() {
        val clock = MutableBandwidthClock()
        val meter = meter(clock, RecordingComparisonMeter())
        val listenerCalled = CountDownLatch(1)
        val listener =
            BandwidthMeter.EventListener { _, _, _ ->
                listenerCalled.countDown()
            }
        meter.addEventListener(Handler(Looper.getMainLooper()), listener)
        meter.removeEventListener(listener)

        completeTransfer(meter, newSource(), clock, bytes = 65_536, durationMs = 1_000)

        assertTrue(!listenerCalled.await(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun concurrentTransfersAreMeasuredIndependentlyInCompletionOrder() {
        val clock = MutableBandwidthClock()
        val meter = meter(clock, RecordingComparisonMeter())
        val pipeline = DiagnosticEventPipeline()
        val firstSource = newSource()
        val secondSource = newSource()
        meter.attach(pipeline, "concurrent-session")

        clock.elapsedMs = 0
        meter.onTransferStart(firstSource, DATA_SPEC, true)
        clock.elapsedMs = 100
        meter.onTransferStart(secondSource, DATA_SPEC, true)
        meter.onBytesTransferred(firstSource, DATA_SPEC, true, 100_000)
        meter.onBytesTransferred(secondSource, DATA_SPEC, true, 50_000)
        clock.elapsedMs = 1_100
        meter.onTransferEnd(secondSource, DATA_SPEC, true)
        clock.elapsedMs = 2_000
        meter.onTransferEnd(firstSource, DATA_SPEC, true)

        val events = pipeline.snapshot("concurrent-session").events.filterIsInstance<DiagnosticEvent.BandwidthSample>()
        assertEquals(listOf(50_000L, 100_000L), events.map { it.segmentSizeBytes })
        assertEquals(listOf(1_000L, 2_000L), events.map { it.downloadDurationMs })
    }

    @Test
    fun comparisonMeterReceivesAllCallbacksWhileNonNetworkAndDetachedTransfersAreNotPublished() {
        val clock = MutableBandwidthClock()
        val comparisonMeter = RecordingComparisonMeter()
        val meter = meter(clock, comparisonMeter)
        val pipeline = DiagnosticEventPipeline()
        val source = newSource()
        meter.attach(pipeline, "lifecycle-session")

        meter.onTransferInitializing(source, DATA_SPEC, false)
        meter.onTransferStart(source, DATA_SPEC, false)
        meter.onBytesTransferred(source, DATA_SPEC, false, 100)
        meter.onTransferEnd(source, DATA_SPEC, false)
        meter.onTransferStart(source, DATA_SPEC, true)
        meter.onBytesTransferred(source, DATA_SPEC, true, 100)
        meter.detach()
        clock.elapsedMs = 1_000
        meter.onTransferEnd(source, DATA_SPEC, true)

        assertTrue(pipeline.snapshot("lifecycle-session").events.isEmpty())
        assertEquals(
            listOf("initializing", "start", "bytes", "end", "start", "bytes", "end"),
            comparisonMeter.callbacks,
        )
    }

    @Test
    fun replacingSessionPreservesEstimateButResetsSessionConfidence() {
        val clock = MutableBandwidthClock()
        val meter =
            FramewrightBandwidthMeter(
                configuration =
                    BandwidthMonitorConfiguration(
                        minimumSamplesForEstimate = 1,
                        fullConfidenceSampleCount = 1,
                    ),
                comparisonMeter = RecordingComparisonMeter(),
                clock = clock,
                eventIdGenerator = { "session-event-${clock.elapsedMs}" },
            )
        val firstPipeline = DiagnosticEventPipeline()
        meter.attach(firstPipeline, "first-session")
        completeTransfer(meter, newSource(), clock, bytes = 65_536, durationMs = 1_000)
        val establishedEstimate = meter.bitrateEstimate
        meter.detach()

        meter.attach(DiagnosticEventPipeline(), "replacement-session")

        assertEquals(524_288, establishedEstimate)
        assertEquals(establishedEstimate, meter.currentEstimate.customEstimateBps)
        assertEquals(0, meter.currentEstimate.sessionSampleCount)
        assertEquals(0.0, meter.currentEstimate.confidence, 0.0)
    }

    @Test
    fun bandwidthSamplePersistsThroughRoomAndSessionExport() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val storage = FramewrightStorage.createInMemory(context)
            val pipeline = DiagnosticEventPipeline(sinks = listOf(storage.eventSink))
            val clock = MutableBandwidthClock(wallMs = 1_000, elapsedMs = 1_000)
            val meter = meter(clock, RecordingComparisonMeter())
            pipeline.tryPublish(
                DiagnosticEvent.SessionStart(
                    DiagnosticEventMetadata("stored-session", "session-start", 1_000, 1_000),
                    mediaUri = "https://example.test/video.m3u8",
                ),
            )
            meter.attach(pipeline, "stored-session")

            completeTransfer(meter, newSource(), clock, bytes = 65_536, durationMs = 1_000)
            assertTrue(storage.eventSink.flush() is StorageResult.Success)

            val exported = storage.sessionStore.exportSession("stored-session") as StorageResult.Success
            val snapshot = DiagnosticEventJsonCodec().decodeSession(exported.data) as CodecResult.Success
            assertTrue(snapshot.data.events.any { it is DiagnosticEvent.BandwidthSample })
            storage.close()
        }
    }

    private fun meter(
        clock: MutableBandwidthClock,
        comparisonMeter: RecordingComparisonMeter,
    ) = FramewrightBandwidthMeter(
        configuration = BandwidthMonitorConfiguration(),
        comparisonMeter = comparisonMeter,
        clock = clock,
        eventIdGenerator = { "bandwidth-event-${clock.elapsedMs}" },
    )

    private fun completeTransfer(
        meter: FramewrightBandwidthMeter,
        source: DataSource,
        clock: MutableBandwidthClock,
        bytes: Int,
        durationMs: Long,
    ) {
        val startedAt = clock.elapsedMs
        meter.onTransferStart(source, DATA_SPEC, true)
        meter.onBytesTransferred(source, DATA_SPEC, true, bytes)
        clock.elapsedMs = startedAt + durationMs
        clock.wallMs += durationMs
        meter.onTransferEnd(source, DATA_SPEC, true)
    }

    private fun newSource(): DataSource = ByteArrayDataSource(byteArrayOf(1))

    private class RecordingComparisonMeter :
        BandwidthMeter,
        TransferListener {
        val callbacks = mutableListOf<String>()

        override fun getBitrateEstimate(): Long = 2_000_000

        override fun getTimeToFirstByteEstimateUs(): Long = 321

        override fun getTransferListener(): TransferListener = this

        override fun addEventListener(
            eventHandler: Handler,
            eventListener: BandwidthMeter.EventListener,
        ) = Unit

        override fun removeEventListener(eventListener: BandwidthMeter.EventListener) = Unit

        override fun onTransferInitializing(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
        ) {
            callbacks += "initializing"
        }

        override fun onTransferStart(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
        ) {
            callbacks += "start"
        }

        override fun onBytesTransferred(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
            bytesTransferred: Int,
        ) {
            callbacks += "bytes"
        }

        override fun onTransferEnd(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
        ) {
            callbacks += "end"
        }
    }

    private data class MutableBandwidthClock(
        var wallMs: Long = 0,
        var elapsedMs: Long = 0,
    ) : BandwidthClock {
        override fun wallTimeMs(): Long = wallMs

        override fun elapsedRealtimeMs(): Long = elapsedMs
    }

    companion object {
        private val DATA_SPEC = DataSpec(Uri.parse("https://example.test/segment.ts"))
    }
}
