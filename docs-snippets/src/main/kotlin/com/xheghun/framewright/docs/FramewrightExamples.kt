package com.xheghun.framewright.docs

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.MediaDrmCallback
import com.xheghun.analytics.DrmScheme
import com.xheghun.framewright.bandwidth.FramewrightBandwidthMeter
import com.xheghun.framewright.codec.FramewrightCodecInspector
import com.xheghun.framewright.diagnostics.DiagnosticsOverlayAction
import com.xheghun.framewright.diagnostics.DiagnosticsOverlayScreen
import com.xheghun.framewright.diagnostics.DiagnosticsOverlayState
import com.xheghun.framewright.drm.FramewrightDrmInspector
import com.xheghun.framewright.media3.FramewrightMedia3
import com.xheghun.framewright.media3.Media3DiagnosticsConfiguration
import com.xheghun.framewright.media3.Media3DiagnosticsSession
import com.xheghun.framewright.media3.MediaSessionInfo
import com.xheghun.framewright.storage.FramewrightStorage
import com.xheghun.framewright.storage.StorageResult

@UnstableApi
data class InstrumentedPlayer(
    val player: ExoPlayer,
    val diagnostics: Media3DiagnosticsSession,
    val bandwidthMeter: FramewrightBandwidthMeter,
)

@UnstableApi
fun createInstrumentedPlayer(
    context: Context,
    storage: FramewrightStorage,
): InstrumentedPlayer {
    val bandwidthMeter = FramewrightBandwidthMeter(context)
    val codecInspector = FramewrightCodecInspector()
    val player =
        ExoPlayer
            .Builder(context)
            .setBandwidthMeter(bandwidthMeter)
            .build()
    val diagnostics =
        FramewrightMedia3.attach(
            context = context,
            player = player,
            contributors = listOf(bandwidthMeter),
            configuration =
                Media3DiagnosticsConfiguration(
                    eventSinks = listOf(storage.eventSink),
                    decoderCapabilityResolver = codecInspector,
                ),
        )
    return InstrumentedPlayer(player, diagnostics, bandwidthMeter)
}

@UnstableApi
fun createWidevineSessionManager(
    drmInspector: FramewrightDrmInspector,
    mediaDrmCallback: MediaDrmCallback,
): DefaultDrmSessionManager =
    DefaultDrmSessionManager
        .Builder()
        .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, drmInspector.exoMediaDrmProvider)
        .build(drmInspector.wrapMediaDrmCallback(mediaDrmCallback))

@UnstableApi
fun prepareMedia(
    instrumentedPlayer: InstrumentedPlayer,
    mediaUri: Uri,
    drmScheme: DrmScheme? = null,
) {
    instrumentedPlayer.player.setMediaItem(MediaItem.fromUri(mediaUri))
    instrumentedPlayer.diagnostics.trackPrepare(
        MediaSessionInfo(mediaUri = mediaUri.toString(), drmScheme = drmScheme),
    ) {
        instrumentedPlayer.player.prepare()
    }
}

suspend fun exportLatestSession(storage: FramewrightStorage): String? {
    if (storage.eventSink.flush() is StorageResult.Failure) return null
    val sessions = storage.sessionStore.listSessions()
    val latest = (sessions as? StorageResult.Success)?.data?.firstOrNull() ?: return null
    return (storage.sessionStore.exportSession(latest.sessionId) as? StorageResult.Success)?.data
}

@Composable
fun FramewrightOverlay(
    state: DiagnosticsOverlayState,
    onAction: (DiagnosticsOverlayAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    DiagnosticsOverlayScreen(state = state, onAction = onAction, modifier = modifier)
}
