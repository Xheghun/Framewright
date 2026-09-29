# Media3 adapter

Coordinate: `io.github.xheghun:framewright-media3-adapter:$latestVersion`

The adapter observes a host-owned `ExoPlayer` and maps Media3 callbacks into Framewright events. It
does not create, prepare, control, or release the player.

```kotlin
val diagnostics = FramewrightMedia3.attach(
    context = context,
    player = player,
    contributors = optionalEventSources,
    configuration = Media3DiagnosticsConfiguration(
        eventSinks = persistentSinks,
        decoderCapabilityResolver = optionalCodecInspector,
        includeErrorMessages = false,
        onDiagnosticsError = errorReporter,
    ),
)
```

`trackPrepare` establishes a session and measures preparation-to-first-frame timing. Player state,
rebuffering, formats, track switches, decoder initialization, load failures, fatal playback errors,
dropped frames, and session termination are then mapped from Media3 callbacks.

URIs are sanitized by default, and error messages are omitted by default. See [privacy](../privacy.md)
before changing either behavior.

All entry points must run on `player.applicationLooper`. See [getting started](../getting-started.md)
for lifecycle rules.
