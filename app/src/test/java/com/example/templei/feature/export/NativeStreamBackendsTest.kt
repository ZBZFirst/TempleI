package com.example.templei.feature.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeStreamBackendsTest {
    @Test
    fun `active backend defaults to ffmpeg`() {
        NativeStreamBackends.installBackendForTesting(null)

        val active = NativeStreamBackends.activeBackend()

        assertEquals(NativeStreamBackend.BackendId.Ffmpeg, active.id)
        assertTrue(NativeStreamBackends.availabilitySummary().contains("active=Ffmpeg"))
    }

    @Test
    fun `testing override backend is selected`() {
        val fake = FakeBackend()
        NativeStreamBackends.installBackendForTesting(fake)
        try {
            val active = NativeStreamBackends.activeBackend()

            assertEquals(NativeStreamBackend.BackendId.Ffmpeg, active.id)
            assertTrue(active.isAvailable())
            assertTrue(NativeStreamBackends.availabilitySummary().contains("fake-ffmpeg-ready"))
        } finally {
            NativeStreamBackends.installBackendForTesting(null)
        }
    }

    @Test
    fun `delegate helpers route access units to active backend`() {
        val fake = FakeBackend()
        NativeStreamBackends.installBackendForTesting(fake)
        try {
            val video = VideoEncoderNode.EncodedAccessUnit(byteArrayOf(0x01), 1_000L, 0)
            val audio = AudioEncoderNode.EncodedAccessUnit(byteArrayOf(0x02), 2_000L, 0)

            NativeStreamBackends.pushVideoAccessUnit(video)
            NativeStreamBackends.pushAudioAccessUnit(audio)

            assertEquals(1, fake.videoPushCount)
            assertEquals(1, fake.audioPushCount)
            assertTrue(NativeStreamBackends.diagnosticsSummary().contains("videoPush=1"))
        } finally {
            NativeStreamBackends.installBackendForTesting(null)
        }
    }

    @Test
    fun `ffmpeg backend reports unavailable when runtime artifacts are missing`() {
        NativeStreamBackends.installBackendForTesting(null)
        val previousRoot = System.getProperty(PROJECT_ROOT_PROPERTY)
        try {
            System.setProperty(PROJECT_ROOT_PROPERTY, "/tmp/templei-native-backend-test")
            val active = NativeStreamBackends.activeBackend()

            assertFalse(active.isAvailable())
            assertTrue(active.availabilityMessage().contains("missing"))
        } finally {
            restoreSystemProperty(PROJECT_ROOT_PROPERTY, previousRoot)
            NativeStreamBackends.installBackendForTesting(null)
        }
    }


    @Test
    fun `ffmpeg backend no longer rejects full av mode guard`() {
        NativeStreamBackends.installBackendForTesting(null)
        val endpoint = ObsEndpointSpec(host = "192.168.1.20", port = 9000, latencyMs = 120, mode = "caller")

        val startResult = NativeStreamBackends.activeBackend().start(
            endpoint,
            CaptureCoordinator.StreamPathMode.FullAv,
        )

        assertTrue(startResult.isFailure)
        assertFalse(startResult.exceptionOrNull()?.message.orEmpty().contains("VideoOnly"))
    }

    private fun restoreSystemProperty(name: String, previous: String?) {
        if (previous == null) {
            System.clearProperty(name)
        } else {
            System.setProperty(name, previous)
        }
    }

    private class FakeBackend : NativeStreamBackend {
        var videoPushCount: Int = 0
        var audioPushCount: Int = 0

        override val id: NativeStreamBackend.BackendId = NativeStreamBackend.BackendId.Ffmpeg

        override fun isAvailable(): Boolean = true

        override fun availabilityMessage(): String = "fake-ffmpeg-ready"

        override fun start(endpoint: ObsEndpointSpec, streamMode: CaptureCoordinator.StreamPathMode): Result<Unit> = Result.success(Unit)

        override fun pushVideoAccessUnit(accessUnit: VideoEncoderNode.EncodedAccessUnit): Result<Unit> {
            videoPushCount += 1
            return Result.success(Unit)
        }

        override fun pushAudioAccessUnit(accessUnit: AudioEncoderNode.EncodedAccessUnit): Result<Unit> {
            audioPushCount += 1
            return Result.success(Unit)
        }

        override fun stop(): Result<Unit> = Result.success(Unit)

        override fun diagnosticsSummary(): String = "videoPush=$videoPushCount audioPush=$audioPushCount"
    }

    private companion object {
        const val PROJECT_ROOT_PROPERTY = "templei.project.root"
    }
}
