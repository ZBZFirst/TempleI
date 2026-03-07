package com.example.templei.feature.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtTransportNodeTest {
    @Test
    fun `isAvailable false when native runtime is missing`() {
        SrtTransportNode.installRuntimeForTesting(null)

        assertFalse(SrtTransportNode.isAvailable())
        assertTrue(SrtTransportNode.availabilityMessage().contains("runtime"))
    }

    @Test
    fun `connect and startSending succeed with injected runtime`() {
        val fakeRuntime = FakeRuntime()
        SrtTransportNode.installRuntimeForTesting(fakeRuntime)

        val endpoint = ObsEndpointSpec(host = "192.168.1.20", port = 9000, latencyMs = 120, mode = "listener")
        val connectResult = SrtTransportNode.connect(endpoint)
        val sendResult = SrtTransportNode.startSending()
        val packetResult = SrtTransportNode.sendPacket(byteArrayOf(0x47))

        assertTrue(connectResult.isSuccess)
        assertTrue(sendResult.isSuccess)
        assertTrue(packetResult.isSuccess)
        assertTrue(SrtTransportNode.isConnected())
        assertEquals(1, fakeRuntime.connectCalls)
        assertEquals(1, fakeRuntime.startCalls)
        assertEquals(1, fakeRuntime.packetCalls)

        SrtTransportNode.stopSending()
        assertFalse(SrtTransportNode.isConnected())
        assertEquals(1, fakeRuntime.stopCalls)
    }

    private class FakeRuntime : SrtTransportNode.Runtime {
        var connectCalls = 0
        var startCalls = 0
        var stopCalls = 0
        var packetCalls = 0

        override fun connect(endpoint: ObsEndpointSpec): Result<Unit> {
            connectCalls += 1
            return Result.success(Unit)
        }

        override fun startSending(): Result<Unit> {
            startCalls += 1
            return Result.success(Unit)
        }

        override fun sendPacket(packet: ByteArray): Result<Unit> {
            packetCalls += 1
            return Result.success(Unit)
        }

        override fun stopSending() {
            stopCalls += 1
        }
    }
}
