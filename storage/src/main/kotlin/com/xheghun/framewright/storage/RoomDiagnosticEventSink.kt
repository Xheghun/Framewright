package com.xheghun.framewright.storage

import com.xheghun.analytics.DiagnosticEvent
import com.xheghun.analytics.DiagnosticEventSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class RoomDiagnosticEventSink internal constructor(
    persistence: DiagnosticBatchPersistence,
    queueCapacity: Int = 2_048,
    private val batchSize: Int = 64,
    private val onStorageError: (StorageError, Throwable?) -> Unit = { _, _ -> },
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : DiagnosticEventSink {
    private sealed interface Command {
        data class Record(
            val event: DiagnosticEvent,
        ) : Command

        data class Flush(
            val result: CompletableDeferred<StorageResult<Unit>>,
        ) : Command

        data class Close(
            val result: CompletableDeferred<StorageResult<Unit>>,
        ) : Command
    }

    private val commands = Channel<Command>(queueCapacity)
    private val closed = AtomicBoolean(false)
    private val worker = scope.launch { consume(persistence) }

    init {
        require(queueCapacity > 0) { "queueCapacity must be greater than zero" }
        require(batchSize > 0) { "batchSize must be greater than zero" }
    }

    override fun record(event: DiagnosticEvent) {
        if (closed.get() || commands.trySend(Command.Record(event)).isFailure) {
            runCatching { onStorageError(if (closed.get()) StorageError.CLOSED else StorageError.OVERLOADED, null) }
        }
    }

    suspend fun flush(): StorageResult<Unit> {
        if (closed.get()) return StorageResult.Failure(StorageError.CLOSED)
        val result = CompletableDeferred<StorageResult<Unit>>()
        commands.send(Command.Flush(result))
        return result.await()
    }

    suspend fun close(): StorageResult<Unit> {
        if (!closed.compareAndSet(false, true)) return StorageResult.Success(Unit)
        val result = CompletableDeferred<StorageResult<Unit>>()
        commands.send(Command.Close(result))
        val closeResult = result.await()
        worker.join()
        return closeResult
    }

    private suspend fun consume(persistence: DiagnosticBatchPersistence) {
        var pending: Command? = null
        var lastFailure: StorageResult.Failure? = null
        while (true) {
            val command = pending ?: commands.receiveCatching().getOrNull() ?: return
            pending = null
            when (command) {
                is Command.Record -> {
                    val batch = mutableListOf(command.event)
                    while (batch.size < batchSize) {
                        when (val next = commands.tryReceive().getOrNull() ?: break) {
                            is Command.Record -> batch += next.event
                            else -> {
                                pending = next
                                break
                            }
                        }
                    }
                    when (val result = persistence.write(batch)) {
                        is StorageResult.Success -> Unit
                        is StorageResult.Failure -> {
                            lastFailure = result
                            runCatching { onStorageError(result.error, result.cause) }
                        }
                    }
                }
                is Command.Flush -> command.result.complete(lastFailure ?: StorageResult.Success(Unit))
                is Command.Close -> {
                    command.result.complete(lastFailure ?: StorageResult.Success(Unit))
                    commands.close()
                    return
                }
            }
        }
    }
}
