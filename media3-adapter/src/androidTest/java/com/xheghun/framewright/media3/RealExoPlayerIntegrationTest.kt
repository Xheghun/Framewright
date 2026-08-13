package com.xheghun.framewright.media3

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xheghun.analytics.CodecResult
import com.xheghun.analytics.DiagnosticEvent
import com.xheghun.analytics.DiagnosticEventPipeline
import com.xheghun.analytics.LoadErrorClass
import com.xheghun.framewright.storage.FramewrightStorage
import com.xheghun.framewright.storage.StorageResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@UnstableApi
@RunWith(AndroidJUnit4::class)
class RealExoPlayerIntegrationTest {
    @Test
    fun realExoPlayerTerminalCallbackReachesSessionExport() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val playbackEnded = CountDownLatch(1)
        val storage = FramewrightStorage.createInMemory(context)
        lateinit var player: ExoPlayer
        lateinit var diagnostics: Media3DiagnosticsSession

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player = ExoPlayer.Builder(context).build()
            player.addListener(
                object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) playbackEnded.countDown()
                    }
                },
            )
            diagnostics =
                FramewrightMedia3.attach(
                    context,
                    player,
                    configuration = Media3DiagnosticsConfiguration(eventSinks = listOf(storage.eventSink)),
                )
            diagnostics.trackPrepare(MediaSessionInfo("https://example.test/empty-playlist")) {
                player.prepare()
            }
        }

        assertTrue("ExoPlayer did not report STATE_ENDED", playbackEnded.await(5, TimeUnit.SECONDS))
        runBlocking {
            assertTrue(storage.eventSink.flush() is StorageResult.Success)
            val storedSessions = storage.sessionStore.listSessions() as StorageResult.Success
            val storedExport = storage.sessionStore.exportSession(storedSessions.data.single().sessionId)
            assertTrue(storedExport is StorageResult.Success)
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val events = requireNotNull(diagnostics.currentSnapshot()).session.events
            val export = diagnostics.exportCurrentSession()
            assertTrue(events.any { it is DiagnosticEvent.SessionEnd })
            assertTrue(export is CodecResult.Success)
            diagnostics.close()
            player.release()
        }
        runBlocking { storage.close() }
    }

    @Test
    fun media3HttpFailuresRetainStatusAndClassification() {
        val pipeline = DiagnosticEventPipeline()
        val adapter =
            FramewrightMedia3EventAdapter(
                player = InstrumentedPlayerBridge(),
                clock = InstrumentedClock,
                eventIdGenerator = {
                    java.util.UUID
                        .randomUUID()
                        .toString()
                },
                onTerminalState = {},
            )
        adapter.attach(pipeline, "http-error-session")
        adapter.markPrepareStart()
        val dataSpec = DataSpec(android.net.Uri.parse("https://example.test/segment.ts"))

        adapter.handleLoadError(1, dataSpec.uri.toString(), httpFailure(404, dataSpec), false)
        adapter.handleLoadError(2, dataSpec.uri.toString(), httpFailure(503, dataSpec), false)

        val errors = pipeline.snapshot("http-error-session").events.filterIsInstance<DiagnosticEvent.LoadError>()
        assertEquals(listOf(LoadErrorClass.HTTP_4XX, LoadErrorClass.HTTP_5XX), errors.map { it.errorClass })
        assertEquals(listOf(404, 503), errors.map { it.httpStatus })
        adapter.detach()
    }

    private fun httpFailure(
        status: Int,
        dataSpec: DataSpec,
    ) = HttpDataSource.InvalidResponseCodeException(
        status,
        null,
        null,
        Collections.emptyMap(),
        dataSpec,
        byteArrayOf(),
    )

    private class InstrumentedPlayerBridge : Media3PlayerBridge {
        override val playerState: Int = Player.STATE_IDLE
        override val currentPosition: Long = 0
        override val bufferedPosition: Long = 0

        override fun addAnalyticsListener(listener: AnalyticsListener) = Unit

        override fun removeAnalyticsListener(listener: AnalyticsListener) = Unit
    }

    private object InstrumentedClock : FramewrightClock {
        override fun wallTimeMs(): Long = 1_000

        override fun elapsedRealtimeMs(): Long = 1_000
    }
}
