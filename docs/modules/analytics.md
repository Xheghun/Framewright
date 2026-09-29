# Analytics

Coordinate: `io.github.xheghun:framewright-analytics:$latestVersion`

This pure Kotlin module defines the stable diagnostic event model. It has no Android or Media3
dependency and can be used to build another player adapter or process exported sessions.

`DiagnosticEventPipeline` records events in a bounded `SessionAggregator`, dispatches them to
synchronous `DiagnosticEventSink` implementations, and exposes a hot `SharedFlow` for live
observers. Sink failure is isolated and reported in `PublishResult`; it does not prevent aggregation
or delivery to other sinks.

```kotlin
val pipeline = DiagnosticEventPipeline(
    sinks = listOf(yourSink),
    onSinkError = { sink, event, error -> report(sink, event, error) },
)

val result = pipeline.tryPublish(event)
val snapshot = pipeline.snapshot(event.sessionId)
val json = pipeline.exportSessionJson(event.sessionId)
```

`DiagnosticEventJsonCodec` returns `CodecResult` instead of throwing for malformed JSON,
unsupported schema versions, unknown event types, and invalid payloads. `SessionSummaryCalculator`
derives startup time, rebuffering, dropped frames, bitrate switches, and terminal status from a
snapshot.

Use [session export](../session-export.md) for lifecycle and persistence guidance.
