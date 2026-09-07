package com.xheghun.framewright.drm

import android.media.MediaDrm
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import com.xheghun.analytics.CodecResult
import com.xheghun.analytics.DiagnosticEvent
import com.xheghun.analytics.DiagnosticEventPipeline
import com.xheghun.analytics.DrmKeyState
import com.xheghun.analytics.DrmLicenseRequestType
import com.xheghun.analytics.DrmRequestKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

@UnstableApi
class FramewrightDrmInspectorTest {
    private val clock = RecordingDrmClock()
    private val pipeline = DiagnosticEventPipeline()
    private val inspector =
        FramewrightDrmInspector(
            configuration = DrmInspectorConfiguration(),
            upstreamMediaDrmProvider = ExoMediaDrm.Provider { error("Provider is not used by this test") },
            clock = clock,
            eventIdGenerator = sequentialIds(),
        )

    @Test
    fun `key changes publish readable status and safe platform properties`() {
        inspector.attach(pipeline, "widevine-session")
        inspector.handleExpirationUpdate(byteArrayOf(1, 2), expirationTimeMs = 90_000)

        inspector.handleKeyStatusChange(
            drmSessionId = byteArrayOf(1, 2),
            keyStatuses =
                listOf(
                    ExoMediaDrm.KeyStatus(MediaDrm.KeyStatus.STATUS_USABLE, byteArrayOf(10, 11)),
                    ExoMediaDrm.KeyStatus(MediaDrm.KeyStatus.STATUS_OUTPUT_NOT_ALLOWED, byteArrayOf(12, 13)),
                ),
            hasNewUsableKey = true,
            properties = DrmDiagnosticProperties("L1", "HDCP_V2_2", "HDCP_V2_3"),
        )

        val statuses = pipeline.snapshot("widevine-session").events.filterIsInstance<DiagnosticEvent.DrmKeyStatus>()
        assertThat(statuses.map { it.keyId }).containsExactly("0a0b", "0c0d")
        assertThat(statuses.map { it.status }).containsExactly(DrmKeyState.USABLE, DrmKeyState.OUTPUT_RESTRICTED)
        assertThat(statuses.first().securityLevel).isEqualTo("L1")
        assertThat(statuses.first().expirationTimeMs).isEqualTo(90_000)
        assertThat(statuses.first().hdcpLevel).isEqualTo("HDCP_V2_2")
        assertThat(statuses.first().maxHdcpLevel).isEqualTo("HDCP_V2_3")
        assertThat(statuses.first().hasNewUsableKey).isEqualTo(true)
    }

    @Test
    fun `license retry publishes each attempt and preserves the original failure`() {
        val expectedFailure = IllegalStateException("license unavailable")
        var invocationCount = 0
        val successfulResponse = MediaDrmCallback.Response(byteArrayOf(4, 5, 6))
        val callback =
            inspector.wrapMediaDrmCallback(
                callback(
                    onKeyRequest = {
                        invocationCount++
                        clock.elapsedTime += 25
                        if (invocationCount == 1) throw expectedFailure
                        successfulResponse
                    },
                ),
            )
        inspector.attach(pipeline, "widevine-session")
        val request =
            ExoMediaDrm.KeyRequest(
                byteArrayOf(1, 2, 3),
                "https://license.example.test/?token=secret",
                ExoMediaDrm.KeyRequest.REQUEST_TYPE_INITIAL,
            )

        val thrown = assertThrows<IllegalStateException> { callback.executeKeyRequest(C.WIDEVINE_UUID, request) }
        val response = callback.executeKeyRequest(C.WIDEVINE_UUID, request)

        assertThat(thrown).isSameInstanceAs(expectedFailure)
        assertThat(response).isSameInstanceAs(successfulResponse)
        val requests = pipeline.snapshot("widevine-session").events.filterIsInstance<DiagnosticEvent.DrmRequest>()
        assertThat(requests.map { it.requestKind }).containsExactly(DrmRequestKind.LICENSE, DrmRequestKind.LICENSE)
        assertThat(requests.map { it.licenseRequestType })
            .containsExactly(DrmLicenseRequestType.INITIAL, DrmLicenseRequestType.INITIAL)
        assertThat(requests.map { it.attemptNumber }).containsExactly(1, 2)
        assertThat(requests.map { it.durationMs }).containsExactly(25L, 25L)
        assertThat(requests.map { it.successful }).containsExactly(false, true)
        assertThat(requests.first().errorMessage).isNull()
        assertThat(requests.first().errorCode).isEqualTo(IllegalStateException::class.java.name)
        val export = pipeline.exportSessionJson("widevine-session")
        check(export is CodecResult.Success)
        assertThat(export.data).doesNotContain("secret")
        assertThat(export.data).doesNotContain("license.example.test")
    }

    @Test
    fun `provisioning requests are timed without exposing payload data`() {
        val callback =
            inspector.wrapMediaDrmCallback(
                callback(
                    onProvisionRequest = {
                        clock.elapsedTime += 40
                        MediaDrmCallback.Response(byteArrayOf(9, 8, 7))
                    },
                ),
            )
        inspector.attach(pipeline, "widevine-session")

        callback.executeProvisionRequest(
            UUID.randomUUID(),
            ExoMediaDrm.ProvisionRequest(byteArrayOf(1, 2, 3), "https://provision.example.test"),
        )

        val request =
            pipeline
                .snapshot("widevine-session")
                .events
                .filterIsInstance<DiagnosticEvent.DrmRequest>()
                .single()
        assertThat(request.requestKind).isEqualTo(DrmRequestKind.PROVISIONING)
        assertThat(request.licenseRequestType).isNull()
        assertThat(request.durationMs).isEqualTo(40)
    }

    @Test
    fun `detach and close are idempotent and stop further event publication`() {
        inspector.attach(pipeline, "widevine-session")
        inspector.detach()
        inspector.detach()
        inspector.close()

        inspector.handleExpirationUpdate(byteArrayOf(1), 100)

        assertThat(pipeline.snapshot("widevine-session").events).isEqualTo(emptyList())
    }

    @Test
    fun `instrumented provider preserves host listeners and delegate release`() {
        val upstream = RecordingExoMediaDrm()
        val forwardingPipeline = DiagnosticEventPipeline()
        val forwardingInspector =
            FramewrightDrmInspector(
                configuration = DrmInspectorConfiguration(),
                upstreamMediaDrmProvider = ExoMediaDrm.Provider { upstream.mediaDrm },
                clock = clock,
                eventIdGenerator = sequentialIds(),
            )
        forwardingInspector.attach(forwardingPipeline, "widevine-session")
        val instrumentedMediaDrm = forwardingInspector.exoMediaDrmProvider.acquireExoMediaDrm(C.WIDEVINE_UUID)
        var listenerMediaDrm: ExoMediaDrm? = null
        var listenerSessionId: ByteArray? = null
        var listenerStatus: ExoMediaDrm.KeyStatus? = null
        var listenerHasNewUsableKey = false
        var listenerExpirationTimeMs: Long? = null
        instrumentedMediaDrm.setOnKeyStatusChangeListener { mediaDrm, sessionId, statuses, hasNewUsableKey ->
            listenerMediaDrm = mediaDrm
            listenerSessionId = sessionId
            listenerStatus = statuses.single()
            listenerHasNewUsableKey = hasNewUsableKey
        }
        instrumentedMediaDrm.setOnExpirationUpdateListener { mediaDrm, _, expirationTimeMs ->
            listenerMediaDrm = mediaDrm
            listenerExpirationTimeMs = expirationTimeMs
        }
        val drmSessionId = byteArrayOf(1, 2)
        val usableStatus = ExoMediaDrm.KeyStatus(MediaDrm.KeyStatus.STATUS_USABLE, byteArrayOf(10, 11))

        upstream.emitExpirationUpdate(drmSessionId, 90_000)
        upstream.emitKeyStatusChange(drmSessionId, listOf(usableStatus), hasNewUsableKey = true)
        instrumentedMediaDrm.release()

        assertThat(listenerMediaDrm).isSameInstanceAs(instrumentedMediaDrm)
        assertThat(listenerSessionId).isSameInstanceAs(drmSessionId)
        assertThat(listenerStatus).isSameInstanceAs(usableStatus)
        assertThat(listenerHasNewUsableKey).isEqualTo(true)
        assertThat(listenerExpirationTimeMs).isEqualTo(90_000)
        assertThat(upstream.releaseCount).isEqualTo(1)
        val events = forwardingPipeline.snapshot("widevine-session").events
        assertThat(events.filterIsInstance<DiagnosticEvent.DrmExpirationUpdate>().single().expirationTimeMs)
            .isEqualTo(90_000)
        assertThat(events.filterIsInstance<DiagnosticEvent.DrmKeyStatus>().single().keyId).isEqualTo("0a0b")
    }

    @Test
    fun `diagnostics failure cannot replace callback responses or exceptions`() {
        val diagnosticsFailures = mutableListOf<Throwable>()
        val failingInspector =
            FramewrightDrmInspector(
                configuration = DrmInspectorConfiguration(onDiagnosticsError = diagnosticsFailures::add),
                upstreamMediaDrmProvider = ExoMediaDrm.Provider { error("Provider is not used by this test") },
                clock = clock,
                eventIdGenerator = { error("Event ID generation failed") },
            )
        failingInspector.attach(DiagnosticEventPipeline(), "widevine-session")
        val expectedResponse = MediaDrmCallback.Response(byteArrayOf(4, 5, 6))
        val successfulCallback =
            failingInspector.wrapMediaDrmCallback(
                callback(onKeyRequest = { expectedResponse }),
            )
        val expectedFailure = IllegalStateException("License callback failed")
        val failingCallback =
            failingInspector.wrapMediaDrmCallback(
                callback(onKeyRequest = { throw expectedFailure }),
            )
        val request = ExoMediaDrm.KeyRequest(byteArrayOf(1), "https://license.example.test")

        val response = successfulCallback.executeKeyRequest(C.WIDEVINE_UUID, request)
        val thrown =
            assertThrows<IllegalStateException> {
                failingCallback.executeKeyRequest(C.WIDEVINE_UUID, request)
            }

        assertThat(response).isSameInstanceAs(expectedResponse)
        assertThat(thrown).isSameInstanceAs(expectedFailure)
        assertThat(diagnosticsFailures.map { it.message })
            .containsExactly("Event ID generation failed", "Event ID generation failed")
    }

    @Test
    fun `platform key status values map without inventing unknown states`() {
        assertThat(MediaDrm.KeyStatus.STATUS_USABLE.toAnalyticsStatus()).isEqualTo(DrmKeyState.USABLE)
        assertThat(MediaDrm.KeyStatus.STATUS_EXPIRED.toAnalyticsStatus()).isEqualTo(DrmKeyState.EXPIRED)
        assertThat(MediaDrm.KeyStatus.STATUS_PENDING.toAnalyticsStatus()).isEqualTo(DrmKeyState.STATUS_PENDING)
        assertThat(MediaDrm.KeyStatus.STATUS_INTERNAL_ERROR.toAnalyticsStatus()).isEqualTo(DrmKeyState.INTERNAL_ERROR)
        assertThat(Int.MAX_VALUE.toAnalyticsStatus()).isEqualTo(DrmKeyState.UNKNOWN)
    }

    private fun callback(
        onKeyRequest: () -> MediaDrmCallback.Response = { MediaDrmCallback.Response(byteArrayOf()) },
        onProvisionRequest: () -> MediaDrmCallback.Response = { MediaDrmCallback.Response(byteArrayOf()) },
    ) = object : MediaDrmCallback {
        override fun executeProvisionRequest(
            uuid: UUID,
            request: ExoMediaDrm.ProvisionRequest,
        ): MediaDrmCallback.Response = onProvisionRequest()

        override fun executeKeyRequest(
            uuid: UUID,
            request: ExoMediaDrm.KeyRequest,
        ): MediaDrmCallback.Response = onKeyRequest()
    }

    private fun sequentialIds(): () -> String {
        var nextId = 1
        return { "drm-event-${nextId++}" }
    }

    private class RecordingDrmClock(
        var wallTime: Long = 1_000,
        var elapsedTime: Long = 100,
    ) : DrmDiagnosticsClock {
        override fun wallTimeMs(): Long = wallTime

        override fun elapsedRealtimeMs(): Long = elapsedTime
    }

    private class RecordingExoMediaDrm {
        private var keyStatusListener: ExoMediaDrm.OnKeyStatusChangeListener? = null
        private var expirationUpdateListener: ExoMediaDrm.OnExpirationUpdateListener? = null
        var releaseCount = 0
            private set

        val mediaDrm: ExoMediaDrm =
            java.lang.reflect.Proxy.newProxyInstance(
                ExoMediaDrm::class.java.classLoader,
                arrayOf(ExoMediaDrm::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "setOnKeyStatusChangeListener" -> {
                        keyStatusListener = arguments?.firstOrNull() as? ExoMediaDrm.OnKeyStatusChangeListener
                        null
                    }
                    "setOnExpirationUpdateListener" -> {
                        expirationUpdateListener = arguments?.firstOrNull() as? ExoMediaDrm.OnExpirationUpdateListener
                        null
                    }
                    "getPropertyString" ->
                        when (arguments?.firstOrNull()) {
                            "securityLevel" -> "L1"
                            "hdcpLevel" -> "HDCP_V2_2"
                            "maxHdcpLevel" -> "HDCP_V2_3"
                            else -> ""
                        }
                    "release" -> {
                        releaseCount++
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    "toString" -> "RecordingExoMediaDrm"
                    else -> defaultValue(method.returnType)
                }
            } as ExoMediaDrm

        fun emitKeyStatusChange(
            sessionId: ByteArray,
            statuses: List<ExoMediaDrm.KeyStatus>,
            hasNewUsableKey: Boolean,
        ) {
            checkNotNull(keyStatusListener).onKeyStatusChange(mediaDrm, sessionId, statuses, hasNewUsableKey)
        }

        fun emitExpirationUpdate(
            sessionId: ByteArray,
            expirationTimeMs: Long,
        ) {
            checkNotNull(expirationUpdateListener).onExpirationUpdate(mediaDrm, sessionId, expirationTimeMs)
        }

        private fun defaultValue(returnType: Class<*>): Any? =
            when (returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                else -> null
            }
    }
}
