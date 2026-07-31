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
import androidx.media3.ui.PlayerView
import com.xheghun.framewright.ui.theme.FramewrightTheme
import com.xheghun.player_core.FramewrightExoPlayerController
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

    val controller = remember { FramewrightExoPlayerController(context) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = controller.currentExoPlayer()
            }
        },
    )

    DisposableEffect(lifecycleOwner) {
        val collectorJob =
            kotlinx.coroutines.MainScope().launch {
                controller.events.collect { event ->
                    Log.d("Framewright", event.toString())
                }
            }

        controller.prepare(TEST_STREAM_URL)
        controller.play()

        onDispose {
            collectorJob.cancel()
            controller.release()
        }
    }
}
