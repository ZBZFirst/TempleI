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


    @Test
    fun `ingest before start is buffered and flushed on start`() {
        val fakeRuntime = FakeRuntime(packetToDrain = byteArrayOf(0x47))
        TsMuxerNode.installRuntimeForTesting(fakeRuntime)

        TsMuxerNode.prepare()
        TsMuxerNode.ingestVideo(
            VideoEncoderNode.EncodedAccessUnit(
                data = byteArrayOf(0x00, 0x00, 0x01),
                presentationTimeUs = 1000,
                flags = 1,
            ),
        )
        TsMuxerNode.ingestAudio(
            AudioEncoderNode.EncodedAccessUnit(
                data = byteArrayOf(0x11, 0x22),
                presentationTimeUs = 2000,
                flags = 1,
            ),
        )

        // Buffered access units should flush once mux starts.
        TsMuxerNode.start()

        val stats = TsMuxerNode.runtimeStats()
        assertEquals(1, stats.videoAccessUnitsIngested)
        assertEquals(1, stats.audioAccessUnitsIngested)
        assertEquals(2, stats.packetsDrained)
    }

    @Test
    fun `ingest updates runtime stats`() {
        val fakeRuntime = FakeRuntime(packetToDrain = byteArrayOf(0x47))
        TsMuxerNode.installRuntimeForTesting(fakeRuntime)

        TsMuxerNode.prepare()
        TsMuxerNode.start()
        TsMuxerNode.ingestVideo(
            VideoEncoderNode.EncodedAccessUnit(
                data = byteArrayOf(0x00, 0x00, 0x01),
                presentationTimeUs = 1000,
                flags = 1,
            ),
        )
        TsMuxerNode.ingestAudio(
            AudioEncoderNode.EncodedAccessUnit(
                data = byteArrayOf(0x11, 0x22),
                presentationTimeUs = 2000,
                flags = 1,
            ),
        )

        val stats = TsMuxerNode.runtimeStats()
        assertEquals(1, stats.videoAccessUnitsIngested)
        assertEquals(1, stats.audioAccessUnitsIngested)
        assertEquals(2, stats.packetsDrained)
    }

    private class FakeRuntime(
        private val packetToDrain: ByteArray = ByteArray(0),
    ) : TsMuxerNode.Runtime {
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
            return packetToDrain
        }
    }
}
