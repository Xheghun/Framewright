package com.xheghun.analytics

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class DiagnosticEventJsonCodecTest {
    private val codec = DiagnosticEventJsonCodec()

    @Test
    fun `single event round trips with schema version`() {
        val event =
            DiagnosticEvent.DecoderInit(
                metadata(),
                decoderName = "vendor.decoder",
                mimeType = "video/avc",
                trackType = TrackType.VIDEO,
                initializationDurationMs = 12,
                isHardwareAccelerated = null,
            )

        val encoded = codec.encodeEvent(event) as CodecResult.Success
        val decoded = codec.decodeEvent(encoded.data) as CodecResult.Success

        assertThat(encoded.data).contains("\"schemaVersion\": 2")
        assertThat(decoded.data).isEqualTo(event)
    }

    @Test
    fun `all v2 event types round trip without domain loss`() {
        val snapshot = SessionSnapshot(sessionId = "session-1", truncated = true, events = allEventTypes())

        val encoded = codec.encodeSession(snapshot) as CodecResult.Success
        val decoded = codec.decodeSession(encoded.data) as CodecResult.Success

        assertThat(decoded.data).isEqualTo(snapshot)
    }

    @Test
    fun `wire format has stable type metadata and nested payload`() {
        val event = DiagnosticEvent.RenderFirstFrame(metadata(), 450)
        val encoded =
            codec.encodeSession(
                SessionSnapshot(sessionId = "session-1", truncated = false, events = listOf(event)),
            ) as CodecResult.Success

        assertThat(encoded.data).contains("\"schemaVersion\": 2")
        assertThat(encoded.data).contains("\"type\": \"RENDER_FIRST_FRAME\"")
        assertThat(encoded.data).contains("\"playerState\": \"READY\"")
        assertThat(encoded.data).contains("\"payload\": {")
        assertThat(encoded.data).contains("\"elapsedSincePrepareMs\": 450")
        val expected =
            """
            {
              "schemaVersion": 2,
              "sessionId": "session-1",
              "truncated": false,
              "events": [{
                "sessionId": "session-1",
                "eventId": "event-1",
                "timestampMs": 1000,
                "elapsedRealtimeMs": 1000,
                "type": "RENDER_FIRST_FRAME",
                "playerState": "READY",
                "payload": { "elapsedSincePrepareMs": 450 }
              }]
            }
            """.trimIndent()
        assertThat(Json.parseToJsonElement(encoded.data)).isEqualTo(Json.parseToJsonElement(expected))
    }

    @Test
    fun `unknown decoder acceleration round trips as null`() {
        val decoder =
            DiagnosticEvent.DecoderInit(
                metadata = metadata(),
                decoderName = "vendor.decoder",
                mimeType = "video/avc",
                trackType = TrackType.VIDEO,
                initializationDurationMs = 12,
                isHardwareAccelerated = null,
            )
        val snapshot = SessionSnapshot(sessionId = "session-1", truncated = false, events = listOf(decoder))

        val encoded = codec.encodeSession(snapshot) as CodecResult.Success
        val decoded = codec.decodeSession(encoded.data) as CodecResult.Success

        assertThat(encoded.data).contains("\"isHardwareAccelerated\": null")
        assertThat(decoded.data).isEqualTo(snapshot)
    }

    @Test
    fun `legacy track switch without available formats decodes with empty ladder`() {
        val json =
            """
            {
              "schemaVersion": 2,
              "event": {
                "sessionId": "session-1",
                "eventId": "track-switch-1",
                "timestampMs": 1000,
                "elapsedRealtimeMs": 1000,
                "type": "TRACK_SWITCH",
                "playerState": "READY",
                "payload": {
                  "fromFormat": null,
                  "toFormat": {"width": 1280, "height": 720, "bitrate": 2000000, "mimeType": "video/avc", "codecs": "avc1"},
                  "reason": "INITIAL",
                  "estimatedBandwidthBps": 3000000,
                  "bufferedDurationMs": 5000
                }
              }
            }
            """.trimIndent()

        val result = codec.decodeEvent(json) as CodecResult.Success
        val trackSwitch = result.data as DiagnosticEvent.TrackSwitch

        assertThat(trackSwitch.availableVideoFormats).isEqualTo(emptyList())
    }

    @Test
    fun `unsupported schema version returns typed failure`() {
        val json = """{"schemaVersion":3,"sessionId":"s","truncated":false,"events":[]}"""
        val result = codec.decodeSession(json)

        assertThat(result).isInstanceOf<CodecResult.Failure>()
        assertThat((result as CodecResult.Failure).error).isInstanceOf<CodecError.UnsupportedSchemaVersion>()
    }

    @Test
    fun `unknown event type returns typed failure`() {
        val json =
            """
            {
              "schemaVersion": 2,
              "sessionId": "s",
              "truncated": false,
              "events": [{
                "sessionId": "s",
                "eventId": "e",
                "timestampMs": 1,
                "elapsedRealtimeMs": 1,
                "type": "NEW_EVENT",
                "playerState": null,
                "payload": {}
              }]
            }
            """.trimIndent()
        val result = codec.decodeSession(json)

        assertThat((result as CodecResult.Failure).error).isInstanceOf<CodecError.UnknownEventType>()
    }

    @Test
    fun `malformed payload returns typed failure`() {
        val json =
            """
            {
              "schemaVersion": 2,
              "sessionId": "s",
              "truncated": false,
              "events": [{
                "sessionId": "s",
                "eventId": "e",
                "timestampMs": 1,
                "elapsedRealtimeMs": 1,
                "type": "RENDER_FIRST_FRAME",
                "playerState": null,
                "payload": {}
              }]
            }
            """.trimIndent()
        val result = codec.decodeSession(json)

        assertThat((result as CodecResult.Failure).error).isInstanceOf<CodecError.InvalidPayload>()
    }
}
