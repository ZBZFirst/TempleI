package com.example.templei.feature.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFeatureEndpointValidationTest {
    @Test
    fun `endpoint validation requires host`() {
        val snapshot = ExportFeature.endpointValidationSnapshot(
            ExportFeature.ObsStreamConfig(host = "", port = 9000),
        )

        assertFalse(snapshot.isValid)
        assertEquals("host missing", snapshot.message)
    }

    @Test
    fun `endpoint validation requires port in range`() {
        val snapshot = ExportFeature.endpointValidationSnapshot(
            ExportFeature.ObsStreamConfig(host = "192.168.1.50", port = 0),
        )

        assertFalse(snapshot.isValid)
        assertEquals("port invalid", snapshot.message)
    }

    @Test
    fun `endpoint validation enforces listener and caller modes`() {
        val snapshot = ExportFeature.endpointValidationSnapshot(
            ExportFeature.ObsStreamConfig(host = "192.168.1.50", port = 9000),
        )

        assertTrue(snapshot.isValid)
        assertTrue(snapshot.obsInputUrl.contains("mode=listener"))
        assertTrue(snapshot.transportCallerUrl.contains("mode=caller"))
    }

    @Test
    fun `test endpoint returns preflight failure for malformed endpoint`() {
        val result = ExportFeature.testEndpoint(
            ExportFeature.ObsStreamConfig(host = "", port = 9000),
        )

        assertEquals("preflight failed: host missing", result)
    }
}
