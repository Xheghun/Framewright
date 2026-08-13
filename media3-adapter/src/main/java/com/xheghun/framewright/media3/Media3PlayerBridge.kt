package com.xheghun.framewright.media3

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener

internal interface Media3PlayerBridge {
    val playerState: Int
    val currentPosition: Long
    val bufferedPosition: Long

    fun addAnalyticsListener(listener: AnalyticsListener)

    fun removeAnalyticsListener(listener: AnalyticsListener)
}

internal class ExoPlayerBridge(
    private val player: ExoPlayer,
) : Media3PlayerBridge {
    override val playerState: Int get() = player.playbackState
    override val currentPosition: Long get() = player.currentPosition
    override val bufferedPosition: Long get() = player.bufferedPosition

    override fun addAnalyticsListener(listener: AnalyticsListener) = player.addAnalyticsListener(listener)

    override fun removeAnalyticsListener(listener: AnalyticsListener) = player.removeAnalyticsListener(listener)
}
