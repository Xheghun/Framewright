package com.xheghun.framewright.drm

import android.media.MediaDrm
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class FramewrightDrmInspectorIntegrationTest {
    @Test
    fun instrumentedProviderAcquiresARealWidevinePlugin() {
        assumeTrue("Widevine is unavailable on this device", MediaDrm.isCryptoSchemeSupported(C.WIDEVINE_UUID))
        val reportedErrors = mutableListOf<Throwable>()
        val inspector =
            FramewrightDrmInspector(
                DrmInspectorConfiguration(onDiagnosticsError = reportedErrors::add),
            )

        val mediaDrm = inspector.exoMediaDrmProvider.acquireExoMediaDrm(C.WIDEVINE_UUID)
        try {
            val securityLevel = mediaDrm.getPropertyString("securityLevel")

            assertTrue("Widevine returned an empty security level", securityLevel.isNotBlank())
            assertTrue("Listener installation reported errors: $reportedErrors", reportedErrors.isEmpty())
        } finally {
            mediaDrm.release()
            inspector.close()
        }
    }
}
