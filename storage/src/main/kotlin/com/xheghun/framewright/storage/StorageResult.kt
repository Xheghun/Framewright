package com.xheghun.framewright.storage

enum class StorageError {
    NOT_FOUND,
    DATABASE,
    SERIALIZATION,
    CLOSED,
    OVERLOADED,
}

sealed interface StorageResult<out T> {
    data class Success<T>(
        val data: T,
    ) : StorageResult<T>

    data class Failure(
        val error: StorageError,
        val cause: Throwable? = null,
    ) : StorageResult<Nothing>
}
