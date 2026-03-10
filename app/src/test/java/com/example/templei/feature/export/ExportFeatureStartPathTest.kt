package com.example.templei.feature.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFeatureStartPathTest {
    @Test
    fun `start path transitions through starting to streaming then idle on stop`() {
        val backend = ScriptedBackend(
            available = true,
            startResult = Result.success(Unit),
            diagnostics = "started=true connState=connected stats={packetsWritten=10} runtime={runtimeMode=jni} lastErr={none}",
        )
        NativeStreamBackends.installBackendForTesting(backend)
        try {
            val config = ExportFeature.ObsStreamConfig(host = "192.168.1.50", port = 9000)

            val start = ExportFeature.startStream(config)
            assertEquals(ExportFeature.SessionState.Starting, backend.stateSeenDuringStart)
            assertEquals(ExportFeature.SessionState.Streaming, start.state)
            assertEquals(ExportFeature.SessionState.Streaming, ExportFeature.currentState())

            val stop = ExportFeature.stopStream()
            assertEquals(ExportFeature.SessionState.Idle, stop.state)
            assertEquals(ExportFeature.SessionState.Idle, ExportFeature.currentState())
        } finally {
            NativeStreamBackends.installBackendForTesting(null)
        }
    }

    @Test
    fun `start path transitions to faulted when backend start fails`() {
        val backend = ScriptedBackend(
            available = true,
            startResult = Result.failure(IllegalStateException("simulated start failure")),
            diagnostics = "started=false connState=faulted stats={packetsWritten=0} runtime={runtimeMode=jni} lastErr={simulated start failure}",
        )
        NativeStreamBackends.installBackendForTesting(backend)
        try {
            val config = ExportFeature.ObsStreamConfig(host = "192.168.1.50", port = 9000)

            val start = ExportFeature.startStream(config)

            assertEquals(ExportFeature.SessionState.Starting, backend.stateSeenDuringStart)
            assertEquals(ExportFeature.SessionState.Faulted, start.state)
            assertEquals(ExportFeature.SessionState.Faulted, ExportFeature.currentState())
            assertTrue(start.error.orEmpty().contains("start transport failed"))
        } finally {
            NativeStreamBackends.installBackendForTesting(null)
        }
    }

    @Test
    fun `integration scaffold keeps stream unhealthy when packets stay zero`() {
        val backend = ScriptedBackend(
            available = true,
            startResult = Result.success(Unit),
            diagnostics = "started=true connState=connected stats={packetsWritten=0} runtime={runtimeMode=jni} lastErr={none}",
        )
        NativeStreamBackends.installBackendForTesting(backend)
        try {
            val config = ExportFeature.ObsStreamConfig(host = "192.168.1.50", port = 9000)
            val start = ExportFeature.startStream(config)
            assertEquals(ExportFeature.SessionState.Streaming, start.state)

            val status = ExportFeature.interoperabilityStatus(config)
            assertTrue(status.contains("stream not healthy yet"))
            assertTrue(status.contains("packetWrite=pending"))
            assertTrue(status.contains("packets=0"))

            ExportFeature.stopStream()
        } finally {
            NativeStreamBackends.installBackendForTesting(null)
        }
    }

    private class ScriptedBackend(
        private val available: Boolean,
        private val startResult: Result<Unit>,
        private val diagnostics: String,
    ) : NativeStreamBackend {
        override val id: NativeStreamBackend.BackendId = NativeStreamBackend.BackendId.Ffmpeg
        var stateSeenDuringStart: ExportFeature.SessionState? = null

        override fun isAvailable(): Boolean = available

        override fun availabilityMessage(): String = if (available) "test backend ready" else "test backend unavailable"

        override fun start(endpoint: ObsEndpointSpec, streamMode: CaptureCoordinator.StreamPathMode): Result<Unit> {
            stateSeenDuringStart = ExportFeature.currentState()
            return startResult
        }

        override fun pushVideoAccessUnit(accessUnit: VideoEncoderNode.EncodedAccessUnit): Result<Unit> = Result.success(Unit)

        override fun pushAudioAccessUnit(accessUnit: AudioEncoderNode.EncodedAccessUnit): Result<Unit> = Result.success(Unit)

        override fun stop(): Result<Unit> = Result.success(Unit)

        override fun diagnosticsSummary(): String = diagnostics
    }
}
