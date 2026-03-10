package com.example.templei.feature.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFeatureRuntimeHealthTest {
    @Test
    fun `runtime health parser flags stub runtime as inactive`() {
        val snapshot = ExportFeature.parseRuntimeHealthSnapshot(
            "started=true connState=connected stats={packetsWritten=10} runtime={runtimeMode=stub} lastErr={none}",
        )

        assertEquals("stub", snapshot.runtimeMode)
        assertEquals("connected", snapshot.connectionState)
        assertEquals(10L, snapshot.packetsWritten)
        assertEquals("none", snapshot.lastNativeError)
        assertFalse(snapshot.runtimeActive)
    }

    @Test
    fun `runtime health parser flags non-stub runtime as active`() {
        val snapshot = ExportFeature.parseRuntimeHealthSnapshot(
            "started=true connState=connected stats={packetsWritten=42} runtime={runtimeMode=jni} lastErr={none}",
        )

        assertEquals("jni", snapshot.runtimeMode)
        assertTrue(snapshot.runtimeActive)
    }

    @Test
    fun `runtime health parser defaults unknown fields safely`() {
        val snapshot = ExportFeature.parseRuntimeHealthSnapshot("started=false")

        assertEquals("unknown", snapshot.runtimeMode)
        assertEquals("unknown", snapshot.connectionState)
        assertEquals(0L, snapshot.packetsWritten)
        assertEquals("none", snapshot.lastNativeError)
        assertFalse(snapshot.runtimeActive)
    }
}
