package com.xheghun.framewright

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.xheghun.framewright.abr.AbrExplorerAction
import com.xheghun.framewright.abr.AbrExplorerScreen
import com.xheghun.framewright.abr.AbrExplorerViewModel
import com.xheghun.framewright.bandwidth.FramewrightBandwidthMeter
import com.xheghun.framewright.media3.FramewrightMedia3
import com.xheghun.framewright.media3.Media3DiagnosticsConfiguration
import com.xheghun.framewright.media3.MediaSessionInfo
import com.xheghun.framewright.storage.FramewrightStorage
import com.xheghun.framewright.storage.StorageResult
import com.xheghun.framewright.ui.theme.FramewrightTheme
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FramewrightTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PlayerScreen(
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

// Public HLS test stream — replace with your own once you're past the smoke test.
private const val TEST_STREAM_URL =
    "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8"

@UnstableApi
@Composable
fun PlayerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val storage = (context.applicationContext as FramewrightApplication).diagnosticsStorage
    val coroutineScope = rememberCoroutineScope()
    val abrExplorerViewModel: AbrExplorerViewModel = viewModel()
    val abrExplorerState by abrExplorerViewModel.state.collectAsStateWithLifecycle()

    val bandwidthMeter = remember { FramewrightBandwidthMeter(context.applicationContext) }
    val player = remember { ExoPlayer.Builder(context).setBandwidthMeter(bandwidthMeter).build() }
    val diagnostics =
        remember {
            FramewrightMedia3.attach(
                context,
                player,
                contributors = listOf(bandwidthMeter),
                configuration = Media3DiagnosticsConfiguration(eventSinks = listOf(storage.eventSink)),
            )
        }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                }
            },
        )
        Button(
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            onClick = {
                coroutineScope.launch {
                    exportLatestSession(context, storage)
                }
            },
        ) {
            Text(stringResource(R.string.export_latest_session))
        }
        if (!abrExplorerState.isVisible) {
            Button(
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                onClick = { abrExplorerViewModel.onAction(AbrExplorerAction.ToggleVisibility) },
            ) {
                Text(stringResource(R.string.open_abr_explorer))
            }
        }
        if (abrExplorerState.isVisible) {
            AbrExplorerScreen(
                state = abrExplorerState,
                onAction = abrExplorerViewModel::onAction,
            )
        }
    }

    DisposableEffect(lifecycleOwner) {
        val collectorJob =
            coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                diagnostics.events.collect { event ->
                    Log.d("Framewright", event.toString())
                    abrExplorerViewModel.onDiagnosticEvent(event)
                }
            }

        player.setMediaItem(MediaItem.fromUri(TEST_STREAM_URL))
        diagnostics.trackPrepare(MediaSessionInfo(mediaUri = TEST_STREAM_URL)) {
            player.prepare()
        }
        player.play()

        onDispose {
            collectorJob.cancel()
            diagnostics.close()
            player.release()
        }
    }
}

private suspend fun exportLatestSession(
    context: android.content.Context,
    storage: FramewrightStorage,
) {
    if (storage.eventSink.flush() is StorageResult.Failure) {
        Toast.makeText(context, R.string.export_flush_failed, Toast.LENGTH_SHORT).show()
        return
    }
    val sessions = storage.sessionStore.listSessions()
    val latest = (sessions as? StorageResult.Success)?.data?.firstOrNull()
    if (latest == null) {
        Toast.makeText(context, R.string.export_no_session, Toast.LENGTH_SHORT).show()
        return
    }
    val export = storage.sessionStore.exportSession(latest.sessionId)
    val json = (export as? StorageResult.Success)?.data
    if (json == null) {
        Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
        return
    }
    val exportFile =
        withContext(Dispatchers.IO) {
            runCatching {
                val directory = File(context.cacheDir, "diagnostic-exports").apply { check(mkdirs() || isDirectory) }
                File(directory, "framewright-${latest.startedAtMs}.json").apply { writeText(json) }
            }.getOrNull()
        }
    if (exportFile == null) {
        Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
        return
    }
    val exportUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", exportFile)
    runCatching {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, exportUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                context.getString(R.string.share_diagnostic_session),
            ),
        )
    }.onFailure {
        Toast.makeText(context, R.string.export_share_failed, Toast.LENGTH_SHORT).show()
    }
}
