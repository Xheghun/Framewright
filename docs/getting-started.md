# Getting started

Framewright instruments an `ExoPlayer` that your application owns. Start with the Media3 adapter,
then opt into only the diagnostic features you need.

## Add dependencies

Use Maven Central and choose artifacts independently:

```kotlin
dependencies {
    val latestVersion = "<latest-version>"

    implementation("io.github.xheghun:framewright-media3-adapter:$latestVersion")

    // Optional:
    implementation("io.github.xheghun:framewright-bandwidth-monitor:$latestVersion")
    implementation("io.github.xheghun:framewright-codec-inspector:$latestVersion")
    implementation("io.github.xheghun:framewright-drm-inspector:$latestVersion")
    implementation("io.github.xheghun:framewright-diagnostics-overlay:$latestVersion")
    implementation("io.github.xheghun:framewright-storage:$latestVersion")
}
```

Use `framewright-analytics` directly only when building a player adapter, event processor, or
backend-independent test without the Media3 integration.

## Attach to a player

Attachment and every session method must run on the player's application thread.

```kotlin
val player = ExoPlayer.Builder(context).build()
val diagnostics = FramewrightMedia3.attach(
    context = context,
    player = player,
    configuration = Media3DiagnosticsConfiguration(
        onDiagnosticsError = { error -> Log.w("Framewright", "Diagnostics failure", error) },
    ),
)
```

Framewright deliberately does not call `setMediaItem`, `prepare`, `play`, `stop`, or `release`.

## Track preparation and session boundaries

Call `trackPrepare` around the host's `player.prepare()` call. A later `trackPrepare` ends the
previous session with `REPLACED`.

```kotlin
player.setMediaItem(MediaItem.fromUri(mediaUri))
diagnostics.trackPrepare(
    MediaSessionInfo(
        mediaUri = mediaUri.toString(),
        drmScheme = DrmScheme.WIDEVINE,
    ),
) {
    player.prepare()
}
player.play()
```

Call `endSession` when the host has a more precise reason than player callbacks can infer:

```kotlin
diagnostics.endSession(SessionEndReason.APP_BACKGROUNDED)
```

`close()` detaches listeners, ends an open session with `RELEASED`, and makes the diagnostics
handle unusable. It does not release the player.

## Choose the next guide

- [Full integration](full-integration.md) wires every optional module.
- [Session export](session-export.md) covers live collection, snapshots, persistence, and JSON.
- [Privacy](privacy.md) explains defaults and data-handling responsibilities.
- Individual module guides are in the [module index](../README.md#modules).
