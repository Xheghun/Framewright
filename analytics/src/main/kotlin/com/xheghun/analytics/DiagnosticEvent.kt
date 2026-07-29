package com.xheghun.analytics

import kotlinx.serialization.Serializable

@Serializable
enum class DrmScheme {
    WIDEVINE,
    FAIRPLAY,
    PLAYREADY,
    OTHER,
}

@Serializable
sealed class DiagnosticEvent {
    abstract val sessionId: String
    abstract val eventId: String
    abstract val timestampMs: Long

    @Serializable
    data class RenderFirstFrame(
        override val sessionId: String,
        override val eventId: String,
        override val timestampMs: Long,
        val elapsedSincePrepareMs: Long,
    ) : DiagnosticEvent()

    @Serializable
    data class TrackSwitch(
        override val sessionId: String,
        override val eventId: String,
        override val timestampMs: Long,
        val fromBitrate: Int,
        val toBitrate: Int,
        val reason: String,
        val estimatedBandwidthBps: Long,
        val bufferedDurationMs: Long,
    ) : DiagnosticEvent()

    @Serializable
    data class RebufferStart(
        override val sessionId: String,
        override val eventId: String,
        override val timestampMs: Long,
        val bufferedMsAtStart: Long,
    ) : DiagnosticEvent()

    @Serializable
    data class RebufferEnd(
        override val sessionId: String,
        override val eventId: String,
        override val timestampMs: Long,
        val durationMs: Long,
    ) : DiagnosticEvent()

    @Serializable
    data class DecoderInit(
        override val sessionId: String,
        override val eventId: String,
        override val timestampMs: Long,
        val decoderName: String,
        val isHardwareAccelerated: Boolean,
    ) : DiagnosticEvent()

    @Serializable
    data class DroppedFrames(
        override val sessionId: String,
        override val eventId: String,
        override val timestampMs: Long,
        val count: Int,
        val elapsedMs: Long,
    ) : DiagnosticEvent()

    @Serializable
    data class LoadError(
        override val sessionId: String,
        override val eventId: String,
        override val timestampMs: Long,
        val errorMessage: String,
        val wasCanceled: Boolean,
    ) : DiagnosticEvent()

    @Serializable
    data class DrmKeyStatus(
        override val sessionId: String,
        override val eventId: String,
        override val timestampMs: Long,
        val securityLevel: String,
    ) : DiagnosticEvent()

    @Serializable
    data class BandwidthSample(
        override val sessionId: String,
        override val eventId: String,
        override val timestampMs: Long,
        val sampleBps: Long,
        val fastEstimateBps: Long,
        val slowEstimateBps: Long,
    ) : DiagnosticEvent()

    @Serializable
    data class PlaybackError(
        override val sessionId: String,
        override val eventId: String,
        override val timestampMs: Long,
        val errorCode: String,
        val errorMessage: String? = null,
        val cause: String? = null,
        val isFatal: Boolean = true,
    ) : DiagnosticEvent()

    @Serializable
    data class SessionStart(
        override val sessionId: String,
        override val eventId: String,
        override val timestampMs: Long,
        val mediaUri: String,
        val drmScheme: DrmScheme? = null,
        val deviceModel: String = "unknown",
        val osVersion: String = "unknown",
        val appVersion: String = "unknown",
    ) : DiagnosticEvent()

    @Serializable
    data class SessionEnd(
        override val sessionId: String,
        override val eventId: String,
        override val timestampMs: Long,
        val durationMs: Long,
    ) : DiagnosticEvent()
}
