# Framewright

Framewright is an attach-first diagnostics toolkit for Android video playback. It observes a
host-owned Media3 `ExoPlayer`, produces structured playback events, and lets an application add
bandwidth, codec, DRM, UI, and persistence diagnostics independently.

Framewright is not a player framework. Your application continues to create, configure, control,
and release its player.

> Framewright is preparing its first `0.1.0` Maven Central release. The coordinates below are the
> stable release contract but will not resolve until that release is published.

## Modules

| Artifact | Purpose |
| --- | --- |
| [`framewright-analytics`](docs/modules/analytics.md) | Player-independent events, aggregation, summaries, and JSON serialization |
| [`framewright-media3-adapter`](docs/modules/media3-adapter.md) | Maps callbacks from a host-owned Media3 player into analytics sessions |
| [`framewright-bandwidth-monitor`](docs/modules/bandwidth-monitor.md) | Media3 bandwidth meter with dual-EWMA estimates and comparison telemetry |
| [`framewright-codec-inspector`](docs/modules/codec-inspector.md) | Decoder catalog and selected-format capability inspection |
| [`framewright-drm-inspector`](docs/modules/drm-inspector.md) | Widevine request, key-status, expiration, and output-protection diagnostics |
| [`framewright-diagnostics-overlay`](docs/modules/diagnostics-overlay.md) | Compose UI driven by the diagnostic event stream |
| [`framewright-storage`](docs/modules/storage.md) | Room-backed session persistence and JSON export |

Only use the artifacts your application needs. Feature artifacts bring in
`framewright-analytics` transitively.

## Install

Maven Central is available in new Android projects by default:

```kotlin
repositories {
    google()
    mavenCentral()
}
```

Add the Media3 adapter and any optional features:

```kotlin
dependencies {
    implementation("io.github.xheghun:framewright-media3-adapter:0.1.0")
    implementation("io.github.xheghun:framewright-bandwidth-monitor:0.1.0")
    implementation("io.github.xheghun:framewright-codec-inspector:0.1.0")
    implementation("io.github.xheghun:framewright-drm-inspector:0.1.0")
    implementation("io.github.xheghun:framewright-diagnostics-overlay:0.1.0")
    implementation("io.github.xheghun:framewright-storage:0.1.0")
}
```

Framewright v0.1 requires Android API 24 or newer, Java 11 bytecode support, Kotlin 2.0 or newer,
and Media3 1.10.1. See the [compatibility guide](docs/compatibility.md), including why consuming
applications do not need to move to the repository's Kotlin compiler version.

## Minimal Media3 integration

Create the player yourself, then attach Framewright on the player's application thread:

```kotlin
val player = ExoPlayer.Builder(context).build()
val diagnostics = FramewrightMedia3.attach(context, player)

player.setMediaItem(MediaItem.fromUri(mediaUri))
diagnostics.trackPrepare(MediaSessionInfo(mediaUri.toString())) {
    player.prepare()
}
player.play()
```

Collect live events if a screen or logger needs them:

```kotlin
val eventJob = lifecycleScope.launch {
    diagnostics.events.collect { event ->
        Log.d("Framewright", event.toString())
    }
}
```

Release resources in the same lifecycle that owns the player:

```kotlin
diagnostics.close()
player.release()
eventJob.cancel()
```

For bandwidth, codec, DRM, storage, and overlay wiring together, use the
[full integration guide](docs/full-integration.md).

## Documentation

- [Getting started](docs/getting-started.md)
- [Compatibility](docs/compatibility.md)
- [Full Media3 integration](docs/full-integration.md)
- [Session lifecycle and export](docs/session-export.md)
- [Privacy and security](docs/privacy.md)
- [Troubleshooting](docs/troubleshooting.md)
- [Versioning policy](docs/versioning.md)
- [Release process](docs/releasing.md)
- [Changelog](CHANGELOG.md)

Generated API reference is packaged in every artifact's `-javadoc.jar`. Maintainers can generate
the local Dokka reference with `./gradlew dokkaGenerate` tasks on individual modules.

## Build the repository

The project uses JDK 21 to build while published bytecode targets Java 11:

```bash
./gradlew spotlessCheck check verifyLocalPublications
```

The `app` module is the in-repository demonstration application. It and `media-lab` are not
published. Documentation snippets live in an unpublished compile fixture so CI catches API drift.

## License

Framewright is licensed under the [Apache License 2.0](LICENSE).
