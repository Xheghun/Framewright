package com.xheghun.framewright.media3

import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.AnalyticsListener

internal class FakeClock(
    var wallTime: Long = 1_000,
    var elapsedTime: Long = 100,
) : FramewrightClock {
    override fun wallTimeMs(): Long = wallTime

    override fun elapsedRealtimeMs(): Long = elapsedTime
}

internal class FakePlayerBridge : Media3PlayerBridge {
    override var playerState: Int = Player.STATE_IDLE
    override var currentPosition: Long = 0
    override var bufferedPosition: Long = 0
    var listener: AnalyticsListener? = null

    override fun addAnalyticsListener(listener: AnalyticsListener) {
        check(this.listener == null)
        this.listener = listener
    }

    override fun removeAnalyticsListener(listener: AnalyticsListener) {
        if (this.listener === listener) this.listener = null
    }
}

internal fun sequentialIds(): () -> String {
    var nextId = 0
    return { "generated-id-${nextId++}" }
}
