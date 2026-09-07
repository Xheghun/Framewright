# Framewright

Framewright is a professional-grade video diagnostics toolkit for Android, designed to provide deep visibility into media playback performance, ABR (Adaptive Bitrate) behavior, and DRM health.

Unlike typical video players, Framewright focuses on **observability** and **reproducibility**, making it an essential tool for media engineers and developers working with complex playback stacks.

## Key Features

- **Media-Agnostic Analytics**: A core telemetry engine that decouples event logic from the specific player implementation.
- **ABR Explorer**: Real-time bandwidth comparison, bitrate-ladder state, and track-selection decisions.
- **Codec Inspector**: Cached decoder capability discovery and selected-format support reporting.
- **Diagnostics Overlay**: A performance-focused Compose overlay for monitoring resolution, codecs, and buffer health in-situ(WIP).
- **DRM Inspector**: Streaming Widevine lifecycle, request timing, key status, expiry, and device-security diagnostics.
- **Media Lab**: A fixture-driven simulation environment to reproduce edge-case bugs without real network infrastructure(WIP).

## Project Structure

Framewright is organized into specialized modules to ensure a clean separation of concerns:

| Module | Description |
| :--- | :--- |
| [`:analytics`](file:///analytics) | Pure Kotlin core for tracking session lifecycle and diagnostic events. |
| [`:media3-adapter`](file:///media3-adapter) | Attach-first Media3 instrumentation and event mapping; the host retains player ownership. |
| [`:bandwidth-monitor`](file:///bandwidth-monitor) | Custom bandwidth estimators and ABR tracking logic. |
| [`:codec-inspector`](file:///codec-inspector) | Optional Android decoder catalog and selected-codec capability inspection. |
| [`:drm-inspector`](file:///drm-inspector) | Optional host-installed Widevine request and key-status instrumentation. |
| [`:diagnostics-overlay`](file:///diagnostics-overlay) | Real-time UI overlay for playback stats. |
| [`:storage`](file:///storage) | Room-backed persistence for playback sessions. |
| [`:media-lab`](file:///media-lab) | Fixture-based playback simulation and case studies. |
| [`:app`](file:///app) | The main demonstration activity and UI. |

## 🛠 Getting Started

### Prerequisites
- Android Studio Ladybug or newer.
- Android SDK 35.
- Kotlin 2.1.0+.

### Build & Run
```bash
./gradlew :app:assembleDebug
```

### Persist diagnostic sessions

Create one process-scoped storage instance for each database and pass its sink when attaching
Framewright. The host still owns the player and its lifecycle.

```kotlin
val storage = FramewrightStorage.create(applicationContext)
val diagnostics = FramewrightMedia3.attach(
    context = context,
    player = player,
    configuration = Media3DiagnosticsConfiguration(
        eventSinks = listOf(storage.eventSink),
    ),
)

// Before reading or shutting down:
storage.eventSink.flush()
val sessions = storage.sessionStore.listSessions()
```

### Enable dual-estimator bandwidth diagnostics

Construct the optional meter before the player, install it as ExoPlayer's active `BandwidthMeter`,
and attach the same instance as an analytics contributor:

```kotlin
val bandwidthMeter = FramewrightBandwidthMeter(applicationContext)
val player = ExoPlayer.Builder(context)
    .setBandwidthMeter(bandwidthMeter)
    .build()

val diagnostics = FramewrightMedia3.attach(
    context = context,
    player = player,
    contributors = listOf(bandwidthMeter),
)
```

Framewright's dual-EWMA estimate drives selection. A private `DefaultBandwidthMeter` observes the
same transfers for comparison, and both values are emitted in each `BANDWIDTH_SAMPLE` event.
The sample app's **ABR Explorer** consumes those samples alongside Media3 track-switch callbacks,
keeps a bounded in-memory timeline, and displays the active format and decision history without
taking ownership of playback.

### Enable codec inspection

Create the optional inspector once and pass it to the Media3 diagnostics configuration. Framewright
then enriches decoder-initialization events with the selected decoder's platform capabilities while
the host application retains ownership of ExoPlayer.

```kotlin
val codecInspector = FramewrightCodecInspector()
val diagnostics = FramewrightMedia3.attach(
    context = context,
    player = player,
    configuration = Media3DiagnosticsConfiguration(
        decoderCapabilityResolver = codecInspector,
    ),
)
```

Use `codecInspector.listDecoders()` for an on-demand device catalog. Catalog entries are kept out of
session telemetry; only the decoder selected by Media3 is attached to its `DECODER_INIT` event.

### Enable Widevine DRM inspection

Install the optional inspector while constructing the host-owned DRM session manager, wrap the
existing `MediaDrmCallback`, and attach the same inspector as a diagnostics contributor:

```kotlin
val drmInspector = FramewrightDrmInspector()
val drmSessionManager = DefaultDrmSessionManager.Builder()
    .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, drmInspector.exoMediaDrmProvider)
    .build(drmInspector.wrapMediaDrmCallback(existingMediaDrmCallback))

val diagnostics = FramewrightMedia3.attach(
    context = context,
    player = player,
    contributors = listOf(drmInspector),
)
```

Framewright records streaming DRM lifecycle, request duration and retries, key status, expiration,
security level, and available HDCP properties. It does not record DRM session identifiers, license
or provisioning payloads, credentials, or license URLs. Offline-license management is outside the
v1 inspector scope. The sample app includes a selectable Widevine DASH test stream; its public test
license endpoint is for development diagnostics only.

---

> [!NOTE]
> This project is currently in active development as part of a technical deep-dive into Media3 and Android telemetry.
