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
    fun `all v1 event types round trip without domain loss`() {
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

        assertThat(encoded.data).contains("\"schemaVersion\": 1")
        assertThat(encoded.data).contains("\"type\": \"RENDER_FIRST_FRAME\"")
        assertThat(encoded.data).contains("\"playerState\": \"READY\"")
        assertThat(encoded.data).contains("\"payload\": {")
        assertThat(encoded.data).contains("\"elapsedSincePrepareMs\": 450")
        val expected =
            """
            {
              "schemaVersion": 1,
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
    fun `unsupported schema version returns typed failure`() {
        val json = """{"schemaVersion":2,"sessionId":"s","truncated":false,"events":[]}"""
        val result = codec.decodeSession(json)

        assertThat(result).isInstanceOf<CodecResult.Failure>()
        assertThat((result as CodecResult.Failure).error).isInstanceOf<CodecError.UnsupportedSchemaVersion>()
    }

    @Test
    fun `unknown event type returns typed failure`() {
        val json =
            """
            {
              "schemaVersion": 1,
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
              "schemaVersion": 1,
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
