package com.xheghun.analytics

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

interface AnalyticsError

sealed interface CodecError : AnalyticsError {
    data class MalformedJson(
        val message: String,
    ) : CodecError

    data class UnsupportedSchemaVersion(
        val version: Int,
    ) : CodecError

    data class UnknownEventType(
        val type: String,
    ) : CodecError

    data class InvalidPayload(
        val type: String,
        val message: String,
    ) : CodecError
}

sealed interface CodecResult<out T> {
    data class Success<T>(
        val data: T,
    ) : CodecResult<T>

    data class Failure(
        val error: CodecError,
    ) : CodecResult<Nothing>
}

@Serializable
private data class WireSession(
    val schemaVersion: Int,
    val sessionId: String,
    val truncated: Boolean,
    val events: List<WireEvent>,
)

@Serializable
private data class WireEvent(
    val sessionId: String,
    val eventId: String,
    val timestampMs: Long,
    val elapsedRealtimeMs: Long,
    val type: String,
    val playerState: String? = null,
    val payload: JsonObject,
)

class DiagnosticEventJsonCodec(
    private val json: Json =
        Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = true
        },
) {
    fun encodeSession(snapshot: SessionSnapshot): CodecResult<String> =
        try {
            CodecResult.Success(
                json.encodeToString(
                    WireSession(
                        schemaVersion = snapshot.schemaVersion,
                        sessionId = snapshot.sessionId,
                        truncated = snapshot.truncated,
                        events = snapshot.events.map(::toWire),
                    ),
                ),
            )
        } catch (error: Exception) {
            CodecResult.Failure(CodecError.InvalidPayload("SESSION", error.message ?: "Unable to encode session"))
        }

    fun decodeSession(value: String): CodecResult<SessionSnapshot> {
        val wire =
            try {
                json.decodeFromString<WireSession>(value)
            } catch (error: SerializationException) {
                return CodecResult.Failure(CodecError.MalformedJson(error.message ?: "Malformed JSON"))
            } catch (error: IllegalArgumentException) {
                return CodecResult.Failure(CodecError.MalformedJson(error.message ?: "Malformed JSON"))
            }
        if (wire.schemaVersion != DIAGNOSTIC_SCHEMA_VERSION) {
            return CodecResult.Failure(CodecError.UnsupportedSchemaVersion(wire.schemaVersion))
        }
        val events = mutableListOf<DiagnosticEvent>()
        for (event in wire.events) {
            when (val decoded = fromWire(event)) {
                is CodecResult.Success -> events += decoded.data
                is CodecResult.Failure -> return decoded
            }
        }
        return CodecResult.Success(
            SessionSnapshot(wire.schemaVersion, wire.sessionId, wire.truncated, events),
        )
    }

    private fun toWire(event: DiagnosticEvent): WireEvent =
        WireEvent(
            sessionId = event.sessionId,
            eventId = event.eventId,
            timestampMs = event.timestampMs,
            elapsedRealtimeMs = event.elapsedRealtimeMs,
            type = event.type.name,
            playerState = event.metadata.playerState?.name,
            payload = payloadFor(event),
        )

    private fun payloadFor(event: DiagnosticEvent): JsonObject =
        buildJsonObject {
            when (event) {
                is DiagnosticEvent.SessionStart -> {
                    put("mediaUri", event.mediaUri)
                    putNullable("drmScheme", event.drmScheme?.name)
                    put("deviceModel", event.deviceModel)
                    put("osVersion", event.osVersion)
                    put("appVersion", event.appVersion)
                }
                is DiagnosticEvent.SessionEnd -> {
                    put("durationMs", event.durationMs)
                    put("reason", event.reason.name)
                }
                is DiagnosticEvent.RenderFirstFrame -> put("elapsedSincePrepareMs", event.elapsedSincePrepareMs)
                is DiagnosticEvent.RebufferStart -> put("bufferedMsAtStart", event.bufferedMsAtStart)
                is DiagnosticEvent.RebufferEnd -> put("durationMs", event.durationMs)
                is DiagnosticEvent.TrackSwitch -> {
                    putNullableObject("fromFormat", event.fromFormat?.let(::formatJson))
                    put("toFormat", formatJson(event.toFormat))
                    put("reason", event.reason.name)
                    put("estimatedBandwidthBps", event.estimatedBandwidthBps)
                    put("bufferedDurationMs", event.bufferedDurationMs)
                }
                is DiagnosticEvent.DecoderInit -> {
                    put("decoderName", event.decoderName)
                    put("mimeType", event.mimeType)
                    put("trackType", event.trackType.name)
                    put("initializationDurationMs", event.initializationDurationMs)
                    put("isHardwareAccelerated", event.isHardwareAccelerated)
                }
                is DiagnosticEvent.DroppedFrames -> {
                    put("count", event.count)
                    put("elapsedMs", event.elapsedMs)
                }
                is DiagnosticEvent.LoadError -> {
                    put("uri", event.uri)
                    putNullable("httpStatus", event.httpStatus)
                    put("errorClass", event.errorClass.name)
                    put("retryCount", event.retryCount)
                    put("wasCanceled", event.wasCanceled)
                    putNullable("errorMessage", event.errorMessage)
                }
                is DiagnosticEvent.DrmKeyStatus -> {
                    put("keyId", event.keyId)
                    put("status", event.status.name)
                    put("securityLevel", event.securityLevel)
                    putNullable("expirationTimeMs", event.expirationTimeMs)
                }
                is DiagnosticEvent.BandwidthSample -> {
                    put("segmentSizeBytes", event.segmentSizeBytes)
                    put("downloadDurationMs", event.downloadDurationMs)
                    put("instantaneousBps", event.instantaneousBps)
                    put("fastEstimateBps", event.fastEstimateBps)
                    put("slowEstimateBps", event.slowEstimateBps)
                    put("defaultEstimateBps", event.defaultEstimateBps)
                    put("confidence", event.confidence)
                }
                is DiagnosticEvent.PlaybackError -> {
                    put("errorCode", event.errorCode)
                    putNullable("errorMessage", event.errorMessage)
                    putNullable("cause", event.cause)
                    put("isFatal", event.isFatal)
                }
            }
        }

    private fun fromWire(wire: WireEvent): CodecResult<DiagnosticEvent> {
        val type =
            try {
                EventType.valueOf(wire.type)
            } catch (_: IllegalArgumentException) {
                return CodecResult.Failure(CodecError.UnknownEventType(wire.type))
            }
        return try {
            val metadata =
                DiagnosticEventMetadata(
                    wire.sessionId,
                    wire.eventId,
                    wire.timestampMs,
                    wire.elapsedRealtimeMs,
                    wire.playerState?.let(PlayerState::valueOf),
                )
            val p = wire.payload
            CodecResult.Success(
                when (type) {
                    EventType.SESSION_START ->
                        DiagnosticEvent.SessionStart(
                            metadata,
                            p.string("mediaUri"),
                            p.nullableString("drmScheme")?.let(DrmScheme::valueOf),
                            p.string("deviceModel"),
                            p.string("osVersion"),
                            p.string("appVersion"),
                        )
                    EventType.SESSION_END ->
                        DiagnosticEvent.SessionEnd(
                            metadata,
                            p.long("durationMs"),
                            SessionEndReason.valueOf(p.string("reason")),
                        )
                    EventType.RENDER_FIRST_FRAME -> DiagnosticEvent.RenderFirstFrame(metadata, p.long("elapsedSincePrepareMs"))
                    EventType.REBUFFER_START -> DiagnosticEvent.RebufferStart(metadata, p.long("bufferedMsAtStart"))
                    EventType.REBUFFER_END -> DiagnosticEvent.RebufferEnd(metadata, p.long("durationMs"))
                    EventType.TRACK_SWITCH ->
                        DiagnosticEvent.TrackSwitch(
                            metadata,
                            p.nullableObject("fromFormat")?.toFormat(),
                            p.obj("toFormat").toFormat(),
                            TrackSwitchReason.valueOf(p.string("reason")),
                            p.long("estimatedBandwidthBps"),
                            p.long("bufferedDurationMs"),
                        )
                    EventType.DECODER_INIT ->
                        DiagnosticEvent.DecoderInit(
                            metadata,
                            p.string("decoderName"),
                            p.string("mimeType"),
                            TrackType.valueOf(p.string("trackType")),
                            p.long("initializationDurationMs"),
                            p.boolean("isHardwareAccelerated"),
                        )
                    EventType.DROPPED_FRAMES -> DiagnosticEvent.DroppedFrames(metadata, p.int("count"), p.long("elapsedMs"))
                    EventType.LOAD_ERROR ->
                        DiagnosticEvent.LoadError(
                            metadata,
                            p.string("uri"),
                            p.nullableInt("httpStatus"),
                            LoadErrorClass.valueOf(p.string("errorClass")),
                            p.int("retryCount"),
                            p.boolean("wasCanceled"),
                            p.nullableString("errorMessage"),
                        )
                    EventType.DRM_KEY_STATUS ->
                        DiagnosticEvent.DrmKeyStatus(
                            metadata,
                            p.string("keyId"),
                            DrmKeyState.valueOf(p.string("status")),
                            p.string("securityLevel"),
                            p.nullableLong("expirationTimeMs"),
                        )
                    EventType.BANDWIDTH_SAMPLE ->
                        DiagnosticEvent.BandwidthSample(
                            metadata,
                            p.long("segmentSizeBytes"),
                            p.long("downloadDurationMs"),
                            p.long("instantaneousBps"),
                            p.long("fastEstimateBps"),
                            p.long("slowEstimateBps"),
                            p.long("defaultEstimateBps"),
                            p.double("confidence"),
                        )
                    EventType.PLAYBACK_ERROR ->
                        DiagnosticEvent.PlaybackError(
                            metadata,
                            p.string("errorCode"),
                            p.nullableString("errorMessage"),
                            p.nullableString("cause"),
                            p.boolean("isFatal"),
                        )
                },
            )
        } catch (error: Exception) {
            CodecResult.Failure(CodecError.InvalidPayload(wire.type, error.message ?: "Invalid payload"))
        }
    }
}

private fun formatJson(format: FormatSnapshot) =
    buildJsonObject {
        putNullable("width", format.width)
        putNullable("height", format.height)
        put("bitrate", format.bitrate)
        putNullable("mimeType", format.mimeType)
        putNullable("codecs", format.codecs)
    }

private fun JsonObject.toFormat() =
    FormatSnapshot(nullableInt("width"), nullableInt("height"), int("bitrate"), nullableString("mimeType"), nullableString("codecs"))

private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

private fun JsonObject.nullableString(key: String) = getValue(key).jsonPrimitive.contentOrNull

private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long

private fun JsonObject.nullableLong(key: String) = getValue(key).jsonPrimitive.contentOrNull?.toLong()

private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int

private fun JsonObject.nullableInt(key: String) = getValue(key).jsonPrimitive.contentOrNull?.toInt()

private fun JsonObject.double(key: String) = getValue(key).jsonPrimitive.double

private fun JsonObject.boolean(key: String) = getValue(key).jsonPrimitive.boolean

private fun JsonObject.obj(key: String) = getValue(key).jsonObject

private fun JsonObject.nullableObject(key: String) = getValue(key).takeUnless { it is JsonNull }?.jsonObject

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
    key: String,
    value: String?,
) {
    put(
        key,
        value?.let(::JsonPrimitive) ?: JsonNull,
    )
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
    key: String,
    value: Int?,
) {
    put(
        key,
        value?.let(::JsonPrimitive) ?: JsonNull,
    )
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
    key: String,
    value: Long?,
) {
    put(
        key,
        value?.let(::JsonPrimitive) ?: JsonNull,
    )
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableObject(
    key: String,
    value: JsonObject?,
) {
    put(key, value ?: JsonNull)
}
