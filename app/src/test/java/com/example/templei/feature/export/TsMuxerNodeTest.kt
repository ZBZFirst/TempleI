package com.example.templei.feature.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TsMuxerNodeTest {
    @Test
    fun `isAvailable false when native runtime is missing`() {
        TsMuxerNode.installRuntimeForTesting(null)

        assertFalse(TsMuxerNode.isAvailable())
        assertTrue(TsMuxerNode.availabilityMessage().contains("runtime"))
    }

    @Test
    fun `prepare and start succeed with injected runtime`() {
        val fakeRuntime = FakeRuntime()
        TsMuxerNode.installRuntimeForTesting(fakeRuntime)

        val prepareResult = TsMuxerNode.prepare()
        val startResult = TsMuxerNode.start()

        assertTrue(prepareResult.isSuccess)
        assertTrue(startResult.isSuccess)
        assertTrue(TsMuxerNode.isStarted())
        assertEquals(1, fakeRuntime.prepareCalls)
        assertEquals(1, fakeRuntime.startCalls)

        TsMuxerNode.stop()
        assertFalse(TsMuxerNode.isStarted())
        assertEquals(1, fakeRuntime.stopCalls)
    }

    private class FakeRuntime : TsMuxerNode.Runtime {
        var prepareCalls = 0
        var startCalls = 0
        var stopCalls = 0

        override fun prepare(): Result<Unit> {
            prepareCalls += 1
            return Result.success(Unit)
        }

        override fun start(): Result<Unit> {
            startCalls += 1
            return Result.success(Unit)
        }

        override fun stop() {
            stopCalls += 1
        }

        override fun ingestVideo(accessUnit: VideoEncoderNode.EncodedAccessUnit): Result<Unit> {
            return Result.success(Unit)
        }

        override fun ingestAudio(accessUnit: AudioEncoderNode.EncodedAccessUnit): Result<Unit> {
            return Result.success(Unit)
        }

        override fun drainPacket(): ByteArray {
            return ByteArray(0)
        }
    }
}
