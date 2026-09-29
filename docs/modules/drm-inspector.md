# DRM inspector

Coordinate: `io.github.xheghun:framewright-drm-inspector:0.1.0`

The inspector observes the host's streaming DRM integration. Install its provider, wrap the real
callback, and attach the same instance as a contributor:

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

Events cover provisioning/license duration and retry attempt, session lifecycle, key status,
expiration, Widevine security level, current HDCP level, and maximum HDCP capability. Current HDCP
describes the active output path; maximum HDCP describes the device's reported upper capability.
Either can be unavailable on a device.

The inspector does not record license URLs, request or response bodies, credentials, DRM session
IDs, or raw key IDs. Offline license acquisition and renewal are outside the v0.1 scope. Error text
is opt-in via `DrmInspectorConfiguration`; review [privacy](../privacy.md) first.
