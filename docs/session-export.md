# Session lifecycle and export

## Live events versus complete sessions

`Media3DiagnosticsSession.events` is a hot `SharedFlow` for UI and logging. It has no replay. An
event can be absent from a live collector when there are no subscribers or the collector cannot
keep up, while still being retained by the in-memory session aggregator and delivered to sinks.

Use `currentSnapshot()` for a point-in-time session plus calculated summary:

```kotlin
val playbackSnapshot = diagnostics.currentSnapshot()
val rebufferCount = playbackSnapshot?.summary?.rebufferCount
```

Use `exportCurrentSession()` for immediate in-memory JSON:

```kotlin
when (val result = diagnostics.exportCurrentSession()) {
    is CodecResult.Success -> uploadOrShare(result.data)
    is CodecResult.Failure -> reportSerializationFailure(result.error)
    null -> Unit // No session has started.
}
```

The in-memory pipeline retains a bounded session history. Use storage for durable history.

## Persist and query sessions

Pass the Room sink during attachment:

```kotlin
val storage = FramewrightStorage.create(applicationContext)
val diagnostics = FramewrightMedia3.attach(
    context = context,
    player = player,
    configuration = Media3DiagnosticsConfiguration(
        eventSinks = listOf(storage.eventSink),
    ),
)
```

The sink writes asynchronously in batches. Flush before a read that must include all events already
submitted by the playback thread:

```kotlin
when (val flush = storage.eventSink.flush()) {
    is StorageResult.Failure -> Log.e("Framewright", "Flush failed: ${flush.error}", flush.cause)
    is StorageResult.Success -> {
        val sessions = storage.sessionStore.listSessions()
        // Inspect StorageResult before using its data.
    }
}
```

`DiagnosticSessionStore` supports listing, loading, exporting, and deleting by session ID. Exported
JSON includes a schema version and the ordered diagnostic events.

## Shutdown

`FramewrightStorage.close()` is suspending. It drains accepted commands, closes the Room database,
and returns a `StorageResult`. Do not reuse its sink or store afterward.

Never perform session export, database work, or network upload on the main thread. The storage API
is suspending, but file creation and upload remain the host application's responsibility.
