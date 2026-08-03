package com.xheghun.analytics

internal fun metadata(
    sessionId: String = "session-1",
    eventId: String = "event-1",
    timestampMs: Long = 1_000,
    elapsedRealtimeMs: Long = timestampMs,
    playerState: PlayerState? = PlayerState.READY,
) = DiagnosticEventMetadata(sessionId, eventId, timestampMs, elapsedRealtimeMs, playerState)

internal fun allEventTypes(): List<DiagnosticEvent> =
    listOf(
        DiagnosticEvent.SessionStart(metadata(eventId = "1"), "https://example.test/master.m3u8", DrmScheme.WIDEVINE, "Pixel", "16", "1.0"),
        DiagnosticEvent.RenderFirstFrame(metadata(eventId = "2"), 450),
        DiagnosticEvent.RebufferStart(metadata(eventId = "3", timestampMs = 2_000), 250),
        DiagnosticEvent.RebufferEnd(metadata(eventId = "4", timestampMs = 2_200), 200),
        DiagnosticEvent.TrackSwitch(
            metadata = metadata(eventId = "5"),
            fromFormat = FormatSnapshot(1280, 720, 2_000_000, "video/avc", "avc1"),
            toFormat = FormatSnapshot(1920, 1080, 5_000_000, "video/avc", "avc1"),
            reason = TrackSwitchReason.BANDWIDTH_INCREASE,
            estimatedBandwidthBps = 6_200_000,
            bufferedDurationMs = 8_400,
        ),
        DiagnosticEvent.DecoderInit(
            metadata(eventId = "6"),
            "c2.qti.avc.decoder",
            "video/avc",
            TrackType.VIDEO,
            18,
            true,
        ),
        DiagnosticEvent.DroppedFrames(metadata(eventId = "7"), 3, 1_000),
        DiagnosticEvent.LoadError(
            metadata(eventId = "8"),
            "https://example.test/1.ts",
            503,
            LoadErrorClass.HTTP_5XX,
            2,
            false,
            "Unavailable",
        ),
        DiagnosticEvent.DrmKeyStatus(metadata(eventId = "9"), "a2V5", DrmKeyState.USABLE, "L1", 90_000),
        DiagnosticEvent.BandwidthSample(
            metadata(eventId = "10"),
            500_000,
            500,
            8_000_000,
            7_000_000,
            6_000_000,
            6_500_000,
            0.9,
        ),
        DiagnosticEvent.PlaybackError(metadata(eventId = "11"), "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", "Network", "timeout", true),
        DiagnosticEvent.SessionEnd(metadata(eventId = "12", timestampMs = 61_000), 60_000, SessionEndReason.PLAYBACK_ENDED),
    )
