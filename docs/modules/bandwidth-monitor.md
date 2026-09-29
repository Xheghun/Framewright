# Bandwidth monitor

Coordinate: `io.github.xheghun:framewright-bandwidth-monitor:$latestVersion`

`FramewrightBandwidthMeter` implements Media3's `BandwidthMeter` and `TransferListener`. Install it
before constructing the player and attach the same instance as a Framewright contributor:

```kotlin
val bandwidthMeter = FramewrightBandwidthMeter(context)
val player = ExoPlayer.Builder(context)
    .setBandwidthMeter(bandwidthMeter)
    .build()

val diagnostics = FramewrightMedia3.attach(
    context = context,
    player = player,
    contributors = listOf(bandwidthMeter),
)
```

The driving estimate combines a responsive fast exponentially weighted moving average (EWMA) and
a steadier slow EWMA. A private Media3 `DefaultBandwidthMeter` observes the same transfers for
comparison. `BANDWIDTH_SAMPLE` events contain instantaneous, fast, slow, Framewright-driving, and
Media3-default estimates plus a confidence value.

Read `currentEstimate` for a thread-safe UI snapshot. Tune `BandwidthMonitorConfiguration` only
after testing realistic transfer sizes and network transitions; the defaults require three samples
before the estimator drives away from its initial 1 Mbps value.
