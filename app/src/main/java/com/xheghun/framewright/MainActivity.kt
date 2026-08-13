package com.xheghun.framewright

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.xheghun.framewright.media3.FramewrightMedia3
import com.xheghun.framewright.media3.MediaSessionInfo
import com.xheghun.framewright.ui.theme.FramewrightTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
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

@Composable
fun PlayerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val player = remember { ExoPlayer.Builder(context).build() }
    val diagnostics = remember { FramewrightMedia3.attach(context, player) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
            }
        },
    )

    DisposableEffect(lifecycleOwner) {
        val collectorJob =
            kotlinx.coroutines.MainScope().launch {
                diagnostics.events.collect { event ->
                    Log.d("Framewright", event.toString())
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
