package com.xheghun.framewright.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi

internal val DemoPlaybackSource.mediaUri: String
    get() =
        when (this) {
            DemoPlaybackSource.CLEAR_HLS ->
                "https://devstreaming-cdn.apple.com/videos/streaming/examples/" +
                    "img_bipbop_adv_example_ts/master.m3u8"
            DemoPlaybackSource.WIDEVINE_DASH ->
                "https://storage.googleapis.com/wvmedia/cenc/h264/tears/tears.mpd"
        }

internal val DemoPlaybackSource.licenseUri: String
    get() =
        when (this) {
            DemoPlaybackSource.CLEAR_HLS -> error("Clear playback does not use a DRM license")
            DemoPlaybackSource.WIDEVINE_DASH ->
                "https://proxy.uat.widevine.com/proxy?video_id=2015_tears&provider=widevine_test"
        }

@UnstableApi
internal fun DemoPlaybackSource.toMediaItem(): MediaItem =
    when (this) {
        DemoPlaybackSource.CLEAR_HLS -> MediaItem.fromUri(mediaUri)
        DemoPlaybackSource.WIDEVINE_DASH ->
            MediaItem
                .Builder()
                .setUri(mediaUri)
                .setDrmConfiguration(
                    MediaItem.DrmConfiguration
                        .Builder(C.WIDEVINE_UUID)
                        .setLicenseUri(licenseUri)
                        .build(),
                ).build()
    }
