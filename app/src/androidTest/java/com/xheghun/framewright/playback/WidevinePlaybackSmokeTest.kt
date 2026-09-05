package com.xheghun.framewright.playback

import android.media.MediaDrm
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.KeyRequestInfo
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xheghun.analytics.CodecResult
import com.xheghun.analytics.DiagnosticEvent
import com.xheghun.analytics.DiagnosticEventJsonCodec
import com.xheghun.analytics.DrmRequestKind
import com.xheghun.analytics.DrmScheme
import com.xheghun.analytics.DrmSessionEventType
import com.xheghun.analytics.SessionEndReason
import com.xheghun.framewright.drm.FramewrightDrmInspector
import com.xheghun.framewright.media3.FramewrightMedia3
import com.xheghun.framewright.media3.Media3DiagnosticsSession
import com.xheghun.framewright.media3.MediaSessionInfo
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Network-backed smoke test for explicit local verification. It is intentionally excluded from
 * normal connected-test runs because the public Widevine fixture is an external dependency.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class WidevinePlaybackSmokeTest {
    @Test
    fun widevineLicenseCallbacksReachTheExportedDiagnosticSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "Pass runWidevineSmoke=true to run the external Widevine smoke test",
            InstrumentationRegistry.getArguments().getString(RUN_WIDEVINE_SMOKE_ARGUMENT).toBoolean(),
        )
        assumeTrue(
            "Widevine is unavailable on this device",
            MediaDrm.isCryptoSchemeSupported(C.WIDEVINE_UUID),
        )

        val drmKeysLoaded = CountDownLatch(1)
        val playbackFailure = AtomicReference<PlaybackException?>()
        val fixture = AtomicReference<PlaybackFixture>()
        instrumentation.runOnMainSync {
            fixture.set(
                createPlaybackFixture(
                    onDrmKeysLoaded = drmKeysLoaded::countDown,
                    onPlaybackFailure = { error ->
                        playbackFailure.set(error)
                        drmKeysLoaded.countDown()
                    },
                ),
            )
        }

        try {
            assertTrue(
                "Timed out waiting for the Widevine key response",
                drmKeysLoaded.await(WIDEVINE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            assertNull("Widevine playback failed", playbackFailure.get())

            val exportedJson = AtomicReference<String>()
            instrumentation.runOnMainSync {
                val activeFixture = fixture.get()
                activeFixture.diagnostics.endSession(SessionEndReason.USER_STOPPED)
                val export = activeFixture.diagnostics.exportCurrentSession()
                check(export is CodecResult.Success) { "Session export failed: $export" }
                exportedJson.set(export.data)
            }

            val decoded = DiagnosticEventJsonCodec().decodeSession(exportedJson.get())
            check(decoded is CodecResult.Success) { "Exported session could not be decoded: $decoded" }
            val events = decoded.data.events
            assertTrue(
                "Export did not contain the Media3 keys-loaded callback",
                events
                    .filterIsInstance<DiagnosticEvent.DrmSessionEvent>()
                    .any { it.eventType == DrmSessionEventType.KEYS_LOADED },
            )
            assertTrue(
                "Export did not contain a successful license request",
                events
                    .filterIsInstance<DiagnosticEvent.DrmRequest>()
                    .any { it.requestKind == DrmRequestKind.LICENSE && it.successful },
            )
        } finally {
            instrumentation.runOnMainSync {
                fixture.get()?.let { activeFixture ->
                    activeFixture.diagnostics.close()
                    activeFixture.player.release()
                    activeFixture.drmInspector.close()
                }
            }
        }
    }

    private fun createPlaybackFixture(
        onDrmKeysLoaded: () -> Unit,
        onPlaybackFailure: (PlaybackException) -> Unit,
    ): PlaybackFixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val protectedSource = DemoPlaybackSource.WIDEVINE_DASH
        val drmInspector = FramewrightDrmInspector()
        val drmSessionManager =
            DefaultDrmSessionManager
                .Builder()
                .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, drmInspector.exoMediaDrmProvider)
                .build(
                    drmInspector.wrapMediaDrmCallback(
                        HttpMediaDrmCallback(
                            protectedSource.licenseUri,
                            DefaultHttpDataSource.Factory(),
                        ),
                    ),
                )
        val player =
            ExoPlayer
                .Builder(context)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(context).setDrmSessionManagerProvider { drmSessionManager },
                ).build()
        val diagnostics =
            FramewrightMedia3.attach(
                context = context,
                player = player,
                contributors = listOf(drmInspector),
            )
        player.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onDrmKeysLoaded(
                    eventTime: AnalyticsListener.EventTime,
                    keyRequestInfo: KeyRequestInfo,
                ) = onDrmKeysLoaded()

                override fun onPlayerError(
                    eventTime: AnalyticsListener.EventTime,
                    error: PlaybackException,
                ) = onPlaybackFailure(error)
            },
        )
        val mediaItem = protectedSource.toMediaItem()
        player.setMediaItem(mediaItem)
        diagnostics.trackPrepare(
            MediaSessionInfo(
                mediaUri = protectedSource.mediaUri,
                drmScheme = DrmScheme.WIDEVINE,
            ),
        ) {
            player.prepare()
        }
        player.play()
        return PlaybackFixture(player, diagnostics, drmInspector)
    }

    private data class PlaybackFixture(
        val player: ExoPlayer,
        val diagnostics: Media3DiagnosticsSession,
        val drmInspector: FramewrightDrmInspector,
    )

    private companion object {
        const val RUN_WIDEVINE_SMOKE_ARGUMENT = "runWidevineSmoke"
        const val WIDEVINE_TIMEOUT_SECONDS = 45L
    }
}
