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
    fun `ingress runtime stats classify rejected backend and native failures`() {
        val fake = FakeBackend()
        NativeStreamBackends.installBackendForTesting(fake)
        try {
            fake.videoResult = Result.failure(IllegalStateException("ingress rejected: video path not active"))
            NativeStreamBackends.pushVideoAccessUnit(VideoEncoderNode.EncodedAccessUnit(byteArrayOf(0x01), 1_000L, 0))

            fake.audioResult = Result.failure(IllegalStateException("ffmpeg runtime unavailable"))
            NativeStreamBackends.pushAudioAccessUnit(AudioEncoderNode.EncodedAccessUnit(byteArrayOf(0x02), 2_000L, 0))

            fake.videoResult = Result.failure(IllegalStateException("native push failed"))
            NativeStreamBackends.pushVideoAccessUnit(VideoEncoderNode.EncodedAccessUnit(byteArrayOf(0x03), 3_000L, 0))

            val stats = NativeStreamBackends.ingressRuntimeStats()
            assertEquals(2, stats.videoIngressCalls)
            assertEquals(1, stats.audioIngressCalls)
            assertEquals(3, stats.ingressFailureCount)
            assertEquals(1, stats.ingressRejectedCount)
            assertEquals(1, stats.backendNotReadyCount)
            assertEquals(1, stats.nativeErrorCount)
            assertEquals(NativeStreamBackends.IngressFailureDomain.NativeError, stats.lastFailureDomain)
        } finally {
            NativeStreamBackends.installBackendForTesting(null)
        }
    }

    @Test
    fun `diagnostics summary includes ingress failure domain counters`() {
        val fake = FakeBackend()
        NativeStreamBackends.installBackendForTesting(fake)
        try {
            fake.videoResult = Result.failure(IllegalStateException("ingress rejected: video path not active"))
            NativeStreamBackends.pushVideoAccessUnit(VideoEncoderNode.EncodedAccessUnit(byteArrayOf(0x01), 1_000L, 0))

            val summary = NativeStreamBackends.diagnosticsSummary()

            assertTrue(summary.contains("ingress_rejected=1"))
            assertTrue(summary.contains("backend_not_ready=0"))
            assertTrue(summary.contains("native_error=0"))
        } finally {
            NativeStreamBackends.installBackendForTesting(null)
        }
    }


    @Test
    fun `ingress runtime stats track successful calls and reset`() {
        val fake = FakeBackend()
        NativeStreamBackends.installBackendForTesting(fake)
        try {
            NativeStreamBackends.pushVideoAccessUnit(VideoEncoderNode.EncodedAccessUnit(byteArrayOf(0x01), 1_000L, 0))
            NativeStreamBackends.pushAudioAccessUnit(AudioEncoderNode.EncodedAccessUnit(byteArrayOf(0x02), 2_000L, 0))

            val beforeReset = NativeStreamBackends.ingressRuntimeStats()
            assertEquals(1, beforeReset.videoIngressCalls)
            assertEquals(1, beforeReset.audioIngressCalls)
            assertEquals(2, beforeReset.ingressSuccessCount)
            assertEquals(0, beforeReset.ingressFailureCount)

            NativeStreamBackends.resetIngressRuntimeStats()
            val afterReset = NativeStreamBackends.ingressRuntimeStats()
            assertEquals(0, afterReset.videoIngressCalls)
            assertEquals(0, afterReset.audioIngressCalls)
            assertEquals(0, afterReset.ingressSuccessCount)
            assertEquals(0, afterReset.ingressFailureCount)
            assertEquals(NativeStreamBackends.IngressFailureDomain.None, afterReset.lastFailureDomain)
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

    @Test
    fun `health hint flags stub runtime with zero ingress counters`() {
        val hint = deriveFfmpegHealthHint(
            started = true,
            statsSnapshot = "prepared=true started=true videoAu=0 audioAu=0",
            runtimeInfo = "ffmpeg JNI stub loaded (PR D av-ingest bring-up)",
        )

        assertTrue(hint.orEmpty().contains("media ingress is idle"))
        assertTrue(hint.orEmpty().contains("stub mode"))
    }

    @Test
    fun `health hint flags stalled ingress for non-stub runtime`() {
        val hint = deriveFfmpegHealthHint(
            started = true,
            statsSnapshot = "prepared=true started=true videoAu=0 audioAu=0",
            runtimeInfo = "ffmpeg runtime active",
        )

        assertTrue(hint.orEmpty().contains("not reaching backend ingress"))
    }

    @Test
    fun `health hint remains empty when ingress counters are flowing`() {
        val hint = deriveFfmpegHealthHint(
            started = true,
            statsSnapshot = "prepared=true started=true videoAu=9 audioAu=0",
            runtimeInfo = "ffmpeg JNI stub loaded (PR D av-ingest bring-up)",
        )

        assertEquals(null, hint)
    }

    @Test
    fun `health hint flags native ingress without packet output`() {
        val hint = deriveFfmpegHealthHint(
            started = true,
            statsSnapshot = "prepared=true started=true videoAu=24 audioAu=0 writePacketsSucceeded=0 packets=99 bytes=0",
            runtimeInfo = "ffmpeg symbols resolved (PR C timestamp-guard scaffold)",
        )

        assertTrue(hint.orEmpty().contains("packet output is still idle"))
    }

    @Test
    fun `health hint prefers canonical write packet field when available`() {
        val hint = deriveFfmpegHealthHint(
            started = true,
            statsSnapshot = "prepared=true started=true videoAu=24 audioAu=12 writePacketsSucceeded=5 packets=0 bytes=0",
            runtimeInfo = "ffmpeg runtime active",
        )

        assertEquals(null, hint)
    }

    @Test
    fun `health hint reports timestamp fixups when present`() {
        val hint = deriveFfmpegHealthHint(
            started = true,
            statsSnapshot = "prepared=true started=true videoAu=24 audioAu=12 packets=120 bytes=98233 ptsFixups=5",
            runtimeInfo = "ffmpeg symbols resolved (PR C timestamp-guard scaffold)",
        )

        assertTrue(hint.orEmpty().contains("timestamp guard active"))
        assertTrue(hint.orEmpty().contains("ptsFixups=5"))
    }



    @Test
    fun `health hint is empty when backend is not started`() {
        val hint = deriveFfmpegHealthHint(
            started = false,
            statsSnapshot = "prepared=true started=false videoAu=0 audioAu=0 packets=0",
            runtimeInfo = "ffmpeg runtime active",
        )

        assertEquals(null, hint)
    }

    @Test
    fun `health hint prioritizes timestamp fixup over av drift warning`() {
        val hint = deriveFfmpegHealthHint(
            started = true,
            statsSnapshot = "prepared=true started=true videoAu=24 audioAu=12 packets=120 bytes=98233 ptsFixups=3 avDeltaMaxAbsUs=250000",
            runtimeInfo = "ffmpeg symbols resolved",
        )

        assertTrue(hint.orEmpty().contains("timestamp guard active"))
        assertFalse(hint.orEmpty().contains("A/V clock alignment warning"))
    }

    @Test
    fun `health hint is empty once packet output is active without warnings`() {
        val hint = deriveFfmpegHealthHint(
            started = true,
            statsSnapshot = "prepared=true started=true videoAu=24 audioAu=12 packets=240 bytes=120000 ptsFixups=0 avDeltaMaxAbsUs=12000",
            runtimeInfo = "ffmpeg runtime active",
        )

        assertEquals(null, hint)
    }

    @Test
    fun `health hint flags av clock alignment drift`() {
        val hint = deriveFfmpegHealthHint(
            started = true,
            statsSnapshot = "prepared=true started=true videoAu=30 audioAu=30 packets=240 bytes=120000 avDeltaMaxAbsUs=250000",
            runtimeInfo = "ffmpeg symbols resolved (PR D av-clock scaffold)",
        )

        assertTrue(hint.orEmpty().contains("A/V clock alignment warning"))
        assertTrue(hint.orEmpty().contains("avDeltaMaxAbsUs=250000"))
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
        var videoResult: Result<Unit> = Result.success(Unit)
        var audioResult: Result<Unit> = Result.success(Unit)

        override val id: NativeStreamBackend.BackendId = NativeStreamBackend.BackendId.Ffmpeg

        override fun isAvailable(): Boolean = true

        override fun availabilityMessage(): String = "fake-ffmpeg-ready"

        override fun start(endpoint: ObsEndpointSpec, streamMode: CaptureCoordinator.StreamPathMode): Result<Unit> = Result.success(Unit)

        override fun pushVideoAccessUnit(accessUnit: VideoEncoderNode.EncodedAccessUnit): Result<Unit> {
            videoPushCount += 1
            return videoResult
        }

        override fun pushAudioAccessUnit(accessUnit: AudioEncoderNode.EncodedAccessUnit): Result<Unit> {
            audioPushCount += 1
            return audioResult
        }

        override fun stop(): Result<Unit> = Result.success(Unit)

        override fun diagnosticsSummary(): String = "videoPush=$videoPushCount audioPush=$audioPushCount"
    }

    private companion object {
        const val PROJECT_ROOT_PROPERTY = "templei.project.root"
    }
}
