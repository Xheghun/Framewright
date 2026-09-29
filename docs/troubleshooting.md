# Troubleshooting

## Attachment fails with an application-thread exception

Call `FramewrightMedia3.attach`, `trackPrepare`, `endSession`, snapshots, export, and `close` on the
same looper as `player.applicationLooper`. In a conventional player this is the main thread.

## No session or prepare events appear

Wrap the actual `player.prepare()` invocation with `trackPrepare`. Calling `player.prepare()` first
means Framewright cannot establish the correct preparation start or session boundary.

## Live events are missing but export contains them

The live flow has no replay and uses bounded buffering. Start collection before preparation and keep
collectors lightweight. Persistence and aggregation are independent from live delivery.

## The latest stored session is incomplete

Call `storage.eventSink.flush()` and check its `StorageResult` before querying or exporting. A sink
can also report `OVERLOADED` if its bounded command queue cannot accept more data.

## Bandwidth events never arrive

Install the same `FramewrightBandwidthMeter` instance in `ExoPlayer.Builder` and in the
`contributors` list. Only network transfers produce estimator samples, and the estimator requires
its configured minimum number of samples before changing the driving estimate.

## Codec capability data is unknown

Capability lookup depends on the decoder name and MIME type reported by Media3 and on platform codec
APIs. A missing decoder, unsupported MIME type, non-video format, or vendor query failure can produce
an unknown result. Use `listDecoders()` to inspect the device catalog.

## DRM request events or key status are missing

Use the inspector's `exoMediaDrmProvider`, wrap the actual `MediaDrmCallback`, and add that same
inspector to Framewright contributors. Key status and HDCP properties depend on Android version,
device DRM implementation, and active output.

## Kotlin reports incompatible metadata

Framewright v0.1 emits Kotlin 2.0 metadata. Confirm the consuming project uses Kotlin 2.0 or newer,
remove stale local artifacts, and inspect dependency resolution for a different Framewright build.
See [compatibility](compatibility.md).

## Dependency resolution selects an unexpected Media3 version

Framewright v0.1 is tested with Media3 1.10.1. Align all `androidx.media3` dependencies in the host
to that version and inspect the result with Gradle's `dependencyInsight` task.
