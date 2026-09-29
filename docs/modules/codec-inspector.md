# Codec inspector

Coordinate: `io.github.xheghun:framewright-codec-inspector:0.1.0`

Create one inspector and pass it as the adapter's decoder capability resolver:

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

Media3 decoder-initialization events are enriched with the selected decoder's platform
classification, profile levels, video ranges, adaptive/tunneled/secure support, and selected-format
support. A full device catalog is not inserted into session telemetry.

Use `listDecoders()` for an on-demand catalog and handle both `CodecInspectionResult.Success` and
`CodecInspectionResult.Failure`. The catalog is loaded lazily and cached for the inspector's
lifetime. Vendor codec data can be incomplete or inconsistent, so unknown capability values are a
normal result.
