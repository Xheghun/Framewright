# Full Media3 integration

This example keeps all playback ownership in the host while enabling bandwidth, codec, DRM, live
UI, and durable session diagnostics.

## Create process-scoped storage

Create one storage instance per database, normally from `Application`, and close it only when its
owning process scope ends.

```kotlin
val storage = FramewrightStorage.create(applicationContext) { error, cause ->
    Log.e("FramewrightStorage", error.name, cause)
}
```

## Construct optional contributors before the player

The bandwidth meter must be installed when the host constructs ExoPlayer. The DRM inspector must
wrap both the `ExoMediaDrm.Provider` and the host's existing callback.

```kotlin
val bandwidthMeter = FramewrightBandwidthMeter(context)
val codecInspector = FramewrightCodecInspector()
val drmInspector = FramewrightDrmInspector()

val drmSessionManager = DefaultDrmSessionManager.Builder()
    .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, drmInspector.exoMediaDrmProvider)
    .build(drmInspector.wrapMediaDrmCallback(existingMediaDrmCallback))

val mediaSourceFactory = DefaultMediaSourceFactory(context)
    .setDrmSessionManagerProvider { mediaItem ->
        if (mediaItem.localConfiguration?.drmConfiguration != null) {
            drmSessionManager
        } else {
            DrmSessionManager.DRM_UNSUPPORTED
        }
    }

val player = ExoPlayer.Builder(context)
    .setBandwidthMeter(bandwidthMeter)
    .setMediaSourceFactory(mediaSourceFactory)
    .build()
```

## Attach Framewright

```kotlin
val diagnostics = FramewrightMedia3.attach(
    context = context,
    player = player,
    contributors = listOf(bandwidthMeter, drmInspector),
    configuration = Media3DiagnosticsConfiguration(
        eventSinks = listOf(storage.eventSink),
        decoderCapabilityResolver = codecInspector,
        onDiagnosticsError = { error -> Log.w("Framewright", "Diagnostics failure", error) },
    ),
)
```

Use the same instance of each contributor in player construction and attachment. Creating a second
bandwidth meter or DRM inspector would split the observed state from the emitted events.

## Feed a diagnostics overlay

Create a `DiagnosticsOverlayViewModel`, collect its state with the lifecycle, and forward events:

```kotlin
val collectorJob = lifecycleScope.launch {
    diagnostics.events.collect(diagnosticsOverlayViewModel::onDiagnosticEvent)
}
```

```kotlin
DiagnosticsOverlayScreen(
    state = overlayState,
    onAction = diagnosticsOverlayViewModel::onAction,
)
```

The live flow is intended for observers, not persistence. It has no replay and can drop delivery to
a slow subscriber; the session aggregator and configured event sinks still record the event.

## Prepare and clean up

```kotlin
player.setMediaItem(mediaItem)
diagnostics.trackPrepare(
    MediaSessionInfo(
        mediaUri = requireNotNull(mediaItem.localConfiguration).uri.toString(),
        drmScheme = DrmScheme.WIDEVINE,
    ),
) {
    player.prepare()
}
player.play()
```

```kotlin
collectorJob.cancel()
diagnostics.close()
player.release()
drmInspector.close()
```

Flush storage before immediately querying or exporting the just-finished session. See
[session export](session-export.md).
