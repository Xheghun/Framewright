# Storage

Coordinate: `io.github.xheghun:framewright-storage:$latestVersion`

The storage module persists diagnostic events in Room and reconstructs complete analytics session
snapshots. Create one instance per database:

```kotlin
val storage = FramewrightStorage.create(
    context = applicationContext,
    databaseName = "framewright-diagnostics.db",
    onStorageError = { error, cause -> report(error, cause) },
)
```

Pass `storage.eventSink` to `Media3DiagnosticsConfiguration.eventSinks`. The sink accepts events
synchronously from playback callbacks and persists them on an IO worker in bounded batches.

Before a read that must include recent events, call `eventSink.flush()` and handle `StorageResult`.
Then use `sessionStore` to list, load, export, or delete sessions. `createInMemory` is available for
tests and short-lived tooling.

Close storage from a coroutine when the owning scope ends:

```kotlin
when (val result = storage.close()) {
    is StorageResult.Success -> Unit
    is StorageResult.Failure -> report(result.error, result.cause)
}
```

For export ordering and failure behavior, see [session export](../session-export.md).
