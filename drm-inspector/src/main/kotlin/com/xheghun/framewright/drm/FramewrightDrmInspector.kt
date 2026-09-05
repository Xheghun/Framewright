package com.xheghun.framewright.drm

import android.media.MediaDrm
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import com.xheghun.analytics.AbstractPlayerEventSource
import com.xheghun.analytics.DiagnosticEvent
import com.xheghun.analytics.DiagnosticEventMetadata
import com.xheghun.analytics.DiagnosticEventPipeline
import com.xheghun.analytics.DrmKeyState
import com.xheghun.analytics.DrmLicenseRequestType
import com.xheghun.analytics.DrmRequestKind
import java.util.UUID
import java.util.WeakHashMap

/**
 * Optional Media3 DRM instrumentation. The host retains ownership of its player, DRM session
 * manager, and license callback.
 */
@UnstableApi
class FramewrightDrmInspector internal constructor(
    private val configuration: DrmInspectorConfiguration,
    upstreamMediaDrmProvider: ExoMediaDrm.Provider,
    private val clock: DrmDiagnosticsClock,
    private val eventIdGenerator: () -> String,
) : AbstractPlayerEventSource(),
    AutoCloseable {
    constructor(
        configuration: DrmInspectorConfiguration = DrmInspectorConfiguration(),
    ) : this(
        configuration = configuration,
        upstreamMediaDrmProvider = FrameworkMediaDrm.DEFAULT_PROVIDER,
        clock = SystemDrmDiagnosticsClock,
        eventIdGenerator = { UUID.randomUUID().toString() },
    )

    private val attachmentLock = Any()
    private var attachment: Attachment? = null
    private val expirationBySessionId = mutableMapOf<String, Long>()

    /** Provider to pass to [androidx.media3.exoplayer.drm.DefaultDrmSessionManager.Builder]. */
    val exoMediaDrmProvider =
        ExoMediaDrm.Provider { scheme ->
            ObservableExoMediaDrm(
                delegate = upstreamMediaDrmProvider.acquireExoMediaDrm(scheme),
                onKeyStatusChange = ::handleKeyStatusChange,
                onExpirationUpdate = ::handleExpirationUpdate,
                onDiagnosticsError = ::reportDiagnosticsError,
            )
        }

    /**
     * Wraps the host's callback without changing requests, responses, retry decisions, or thrown
     * errors.
     */
    fun wrapMediaDrmCallback(callback: MediaDrmCallback): MediaDrmCallback =
        InstrumentedMediaDrmCallback(
            delegate = callback,
            clock = clock,
            includeErrorMessages = configuration.includeErrorMessages,
            publish = ::publish,
            onDiagnosticsError = ::reportDiagnosticsError,
        )

    override fun onAttach(
        pipeline: DiagnosticEventPipeline,
        sessionId: String,
    ) {
        synchronized(attachmentLock) {
            expirationBySessionId.clear()
            attachment = Attachment(pipeline, sessionId)
        }
    }

    override fun onDetach() {
        synchronized(attachmentLock) {
            attachment = null
            expirationBySessionId.clear()
        }
    }

    override fun close() = detach()

    internal fun handleKeyStatusChange(
        mediaDrm: ExoMediaDrm,
        drmSessionId: ByteArray,
        keyStatuses: List<ExoMediaDrm.KeyStatus>,
        hasNewUsableKey: Boolean,
    ) {
        handleKeyStatusChange(
            drmSessionId = drmSessionId,
            keyStatuses = keyStatuses,
            hasNewUsableKey = hasNewUsableKey,
            properties = mediaDrm.readDiagnosticProperties(),
        )
    }

    internal fun handleKeyStatusChange(
        drmSessionId: ByteArray,
        keyStatuses: List<ExoMediaDrm.KeyStatus>,
        hasNewUsableKey: Boolean,
        properties: DrmDiagnosticProperties,
    ) {
        val sessionKey = drmSessionId.toSafeIdentifier()
        val expiration = synchronized(attachmentLock) { expirationBySessionId[sessionKey] }
        keyStatuses.forEach { keyStatus ->
            publish { metadata ->
                DiagnosticEvent.DrmKeyStatus(
                    metadata = metadata,
                    keyId = keyStatus.keyId.toSafeIdentifier(),
                    status = keyStatus.statusCode.toAnalyticsStatus(),
                    securityLevel = properties.securityLevel ?: "unknown",
                    expirationTimeMs = expiration,
                    hdcpLevel = properties.hdcpLevel,
                    maxHdcpLevel = properties.maxHdcpLevel,
                    hasNewUsableKey = hasNewUsableKey,
                )
            }
        }
    }

    internal fun handleExpirationUpdate(
        drmSessionId: ByteArray,
        expirationTimeMs: Long,
    ) {
        val safeExpiration = expirationTimeMs.coerceAtLeast(0)
        synchronized(attachmentLock) {
            expirationBySessionId[drmSessionId.toSafeIdentifier()] = safeExpiration
        }
        publish { metadata -> DiagnosticEvent.DrmExpirationUpdate(metadata, safeExpiration) }
    }

    private fun publish(event: (DiagnosticEventMetadata) -> DiagnosticEvent) {
        synchronized(attachmentLock) {
            val activeAttachment = attachment ?: return
            activeAttachment.pipeline.tryPublish(
                event(
                    DiagnosticEventMetadata(
                        sessionId = activeAttachment.sessionId,
                        eventId = eventIdGenerator(),
                        timestampMs = clock.wallTimeMs(),
                        elapsedRealtimeMs = clock.elapsedRealtimeMs(),
                    ),
                ),
            )
        }
    }

    private fun reportDiagnosticsError(error: Throwable) {
        runCatching { configuration.onDiagnosticsError(error) }
    }

    private data class Attachment(
        val pipeline: DiagnosticEventPipeline,
        val sessionId: String,
    )
}

@UnstableApi
private class ObservableExoMediaDrm(
    private val delegate: ExoMediaDrm,
    private val onKeyStatusChange: (ExoMediaDrm, ByteArray, List<ExoMediaDrm.KeyStatus>, Boolean) -> Unit,
    private val onExpirationUpdate: (ByteArray, Long) -> Unit,
    private val onDiagnosticsError: (Throwable) -> Unit,
) : ExoMediaDrm by delegate {
    @Volatile
    private var externalKeyStatusListener: ExoMediaDrm.OnKeyStatusChangeListener? = null

    @Volatile
    private var externalExpirationListener: ExoMediaDrm.OnExpirationUpdateListener? = null

    init {
        installKeyStatusListener()
        installExpirationListener()
    }

    override fun setOnKeyStatusChangeListener(listener: ExoMediaDrm.OnKeyStatusChangeListener?) {
        externalKeyStatusListener = listener
        installKeyStatusListener()
    }

    override fun setOnExpirationUpdateListener(listener: ExoMediaDrm.OnExpirationUpdateListener?) {
        externalExpirationListener = listener
        installExpirationListener()
    }

    private fun installKeyStatusListener() {
        runCatching {
            delegate.setOnKeyStatusChangeListener { _, sessionId, keyStatuses, hasNewUsableKey ->
                runCatching { onKeyStatusChange(this, sessionId, keyStatuses, hasNewUsableKey) }
                    .onFailure(onDiagnosticsError)
                externalKeyStatusListener?.onKeyStatusChange(this, sessionId, keyStatuses, hasNewUsableKey)
            }
        }.onFailure(onDiagnosticsError)
    }

    private fun installExpirationListener() {
        runCatching {
            delegate.setOnExpirationUpdateListener { _, sessionId, expirationTimeMs ->
                runCatching { onExpirationUpdate(sessionId, expirationTimeMs) }
                    .onFailure(onDiagnosticsError)
                externalExpirationListener?.onExpirationUpdate(this, sessionId, expirationTimeMs)
            }
        }.onFailure(onDiagnosticsError)
    }
}

@UnstableApi
private class InstrumentedMediaDrmCallback(
    private val delegate: MediaDrmCallback,
    private val clock: DrmDiagnosticsClock,
    private val includeErrorMessages: Boolean,
    private val publish: ((DiagnosticEventMetadata) -> DiagnosticEvent) -> Unit,
    private val onDiagnosticsError: (Throwable) -> Unit,
) : MediaDrmCallback {
    private val attemptLock = Any()
    private val attemptsByRequest = WeakHashMap<Any, Int>()

    override fun executeProvisionRequest(
        uuid: UUID,
        request: ExoMediaDrm.ProvisionRequest,
    ): MediaDrmCallback.Response =
        execute(
            request = request,
            requestKind = DrmRequestKind.PROVISIONING,
            licenseRequestType = null,
        ) { delegate.executeProvisionRequest(uuid, request) }

    override fun executeKeyRequest(
        uuid: UUID,
        request: ExoMediaDrm.KeyRequest,
    ): MediaDrmCallback.Response =
        execute(
            request = request,
            requestKind = DrmRequestKind.LICENSE,
            licenseRequestType = request.requestType.toAnalyticsRequestType(),
        ) { delegate.executeKeyRequest(uuid, request) }

    private inline fun execute(
        request: Any,
        requestKind: DrmRequestKind,
        licenseRequestType: DrmLicenseRequestType?,
        operation: () -> MediaDrmCallback.Response,
    ): MediaDrmCallback.Response {
        val attemptNumber =
            synchronized(attemptLock) {
                val attempt = (attemptsByRequest[request] ?: 0) + 1
                attemptsByRequest[request] = attempt
                attempt
            }
        val startedAtMs = readElapsedRealtimeMs()
        val response =
            try {
                operation()
            } catch (error: Throwable) {
                publishRequestSafely(
                    requestKind,
                    licenseRequestType,
                    attemptNumber,
                    startedAtMs,
                    successful = false,
                    error = error,
                )
                throw error
            }
        synchronized(attemptLock) { attemptsByRequest.remove(request) }
        publishRequestSafely(requestKind, licenseRequestType, attemptNumber, startedAtMs, successful = true)
        return response
    }

    private fun publishRequestSafely(
        requestKind: DrmRequestKind,
        licenseRequestType: DrmLicenseRequestType?,
        attemptNumber: Int,
        startedAtMs: Long,
        successful: Boolean,
        error: Throwable? = null,
    ) {
        try {
            publishRequest(requestKind, licenseRequestType, attemptNumber, startedAtMs, successful, error)
        } catch (diagnosticsError: Throwable) {
            runCatching { onDiagnosticsError(diagnosticsError) }
        }
    }

    private fun publishRequest(
        requestKind: DrmRequestKind,
        licenseRequestType: DrmLicenseRequestType?,
        attemptNumber: Int,
        startedAtMs: Long,
        successful: Boolean,
        error: Throwable? = null,
    ) {
        publish { metadata ->
            DiagnosticEvent.DrmRequest(
                metadata = metadata,
                requestKind = requestKind,
                licenseRequestType = licenseRequestType,
                attemptNumber = attemptNumber,
                durationMs = (readElapsedRealtimeMs() - startedAtMs).coerceAtLeast(0),
                successful = successful,
                errorCode = error?.javaClass?.name,
                errorMessage = error?.message.takeIf { includeErrorMessages },
            )
        }
    }

    private fun readElapsedRealtimeMs(): Long =
        try {
            clock.elapsedRealtimeMs()
        } catch (diagnosticsError: Throwable) {
            runCatching { onDiagnosticsError(diagnosticsError) }
            0
        }
}

internal data class DrmDiagnosticProperties(
    val securityLevel: String?,
    val hdcpLevel: String?,
    val maxHdcpLevel: String?,
)

@UnstableApi
private fun ExoMediaDrm.readDiagnosticProperties() =
    DrmDiagnosticProperties(
        securityLevel = optionalProperty("securityLevel"),
        hdcpLevel = optionalProperty("hdcpLevel"),
        maxHdcpLevel = optionalProperty("maxHdcpLevel"),
    )

@UnstableApi
private fun ExoMediaDrm.optionalProperty(name: String): String? =
    runCatching { getPropertyString(name) }.getOrNull()?.takeIf(String::isNotBlank)

internal fun Int.toAnalyticsStatus(): DrmKeyState =
    when (this) {
        MediaDrm.KeyStatus.STATUS_USABLE -> DrmKeyState.USABLE
        MediaDrm.KeyStatus.STATUS_EXPIRED -> DrmKeyState.EXPIRED
        MediaDrm.KeyStatus.STATUS_OUTPUT_NOT_ALLOWED -> DrmKeyState.OUTPUT_RESTRICTED
        MediaDrm.KeyStatus.STATUS_PENDING,
        MediaDrm.KeyStatus.STATUS_USABLE_IN_FUTURE,
        -> DrmKeyState.STATUS_PENDING
        MediaDrm.KeyStatus.STATUS_INTERNAL_ERROR -> DrmKeyState.INTERNAL_ERROR
        else -> DrmKeyState.UNKNOWN
    }

@UnstableApi
internal fun Int.toAnalyticsRequestType(): DrmLicenseRequestType =
    when (this) {
        ExoMediaDrm.KeyRequest.REQUEST_TYPE_INITIAL -> DrmLicenseRequestType.INITIAL
        ExoMediaDrm.KeyRequest.REQUEST_TYPE_RENEWAL -> DrmLicenseRequestType.RENEWAL
        ExoMediaDrm.KeyRequest.REQUEST_TYPE_RELEASE -> DrmLicenseRequestType.RELEASE
        ExoMediaDrm.KeyRequest.REQUEST_TYPE_UPDATE -> DrmLicenseRequestType.UPDATE
        ExoMediaDrm.KeyRequest.REQUEST_TYPE_NONE -> DrmLicenseRequestType.NONE
        else -> DrmLicenseRequestType.UNKNOWN
    }

private fun ByteArray.toSafeIdentifier(): String =
    buildString(size * 2) {
        this@toSafeIdentifier.forEach { value ->
            val unsigned = value.toInt() and 0xFF
            append(HEX_DIGITS[unsigned ushr 4])
            append(HEX_DIGITS[unsigned and 0x0F])
        }
    }

private const val HEX_DIGITS = "0123456789abcdef"

internal interface DrmDiagnosticsClock {
    fun wallTimeMs(): Long

    fun elapsedRealtimeMs(): Long
}

private object SystemDrmDiagnosticsClock : DrmDiagnosticsClock {
    override fun wallTimeMs(): Long = System.currentTimeMillis()

    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}
