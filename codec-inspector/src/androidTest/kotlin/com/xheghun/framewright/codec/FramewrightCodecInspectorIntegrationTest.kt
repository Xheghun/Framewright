package com.xheghun.framewright.codec

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xheghun.analytics.CodecClassificationSource
import com.xheghun.analytics.DecoderInspectionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FramewrightCodecInspectorIntegrationTest {
    @Test
    fun deviceDecoderCatalogCanInspectARealCodec() {
        val inspector = FramewrightCodecInspector()
        val catalogResult = inspector.listDecoders()

        assertTrue(catalogResult is CodecInspectionResult.Success)
        val catalog = (catalogResult as CodecInspectionResult.Success).data
        assertFalse("Android reported no media decoders", catalog.isEmpty())
        assertEquals(
            catalog.size,
            catalog.distinctBy { "${it.decoderName.lowercase()}:${it.mimeType.lowercase()}" }.size,
        )

        val selectedDecoder = catalog.first()
        val inspectionResult =
            inspector.inspect(
                DecoderInspectionRequest(
                    decoderName = selectedDecoder.decoderName,
                    mimeType = selectedDecoder.mimeType,
                ),
            )

        assertTrue(inspectionResult is CodecInspectionResult.Success)
        val capabilities = (inspectionResult as CodecInspectionResult.Success).data
        val expectedSource =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                CodecClassificationSource.PLATFORM
            } else {
                CodecClassificationSource.NAME_HEURISTIC
            }
        assertEquals(expectedSource, capabilities.classificationSource)
    }
}
