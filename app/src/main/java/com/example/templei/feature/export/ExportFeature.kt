package com.example.templei.feature.export

import android.content.Context
import android.content.SharedPreferences

/**
 * Screen 2 export/stream state holder for OBS-over-LAN ingest setup.
 *
 * This module intentionally separates UI wiring from transport internals.
 * TODO: Replace contract nodes with native MPEG-TS mux + SRT sender integration.
 */
object ExportFeature {
    private const val TAG = "TempleI-ExportFeature"
    enum class SessionState {
        Idle,
        Ready,
        Starting,
        Streaming,
        Stopping,
        Faulted,
    }

    data class ObsStreamConfig(
        val host: String = "",
        val port: Int = DEFAULT_PORT,
        val profile: String = PROFILE_BALANCED,
        val streamMode: CaptureCoordinator.StreamPathMode = CaptureCoordinator.StreamPathMode.FullAv,
    )

    data class ValidationResult(
        val isValid: Boolean,
        val message: String,
    )

    data class StreamResult(
        val state: SessionState,
        val error: String? = null,
    )

    private const val PREFS_NAME = "obs_stream_prefs"
    private const val KEY_HOST = "obs_host"
    private const val KEY_PORT = "obs_port"
    private const val KEY_PROFILE = "obs_profile"
    private const val KEY_STREAM_MODE = "obs_stream_mode"

    private const val DEFAULT_PORT = 9000
    private const val PROFILE_BALANCED = "Balanced"

    private var sessionState: SessionState = SessionState.Idle
    private var lastError: String = ""
    private var lastValidation: String = "Not validated"
    private var lastConnectionTest: String = "Not tested"
    private var lastDiagnosticSummary: String = "diagnostics pending"
    private var lastDiagnosticAtMs: Long = 0

    private const val DIAGNOSTIC_REFRESH_INTERVAL_MS = 1_000L
    private const val FRAME_BUDGET_US = 33_333L

    private val transportGateway: StreamTransportGateway = DefaultTransportGateway

    fun loadConfig(context: Context): ObsStreamConfig {
        val prefs = context.preferences()
        return ObsStreamConfig(
            host = prefs.getString(KEY_HOST, "").orEmpty(),
            port = prefs.getInt(KEY_PORT, DEFAULT_PORT),
            profile = prefs.getString(KEY_PROFILE, PROFILE_BALANCED).orEmpty(),
            streamMode = runCatching {
                CaptureCoordinator.StreamPathMode.valueOf(
                    prefs.getString(KEY_STREAM_MODE, CaptureCoordinator.StreamPathMode.FullAv.name)
                        ?: CaptureCoordinator.StreamPathMode.FullAv.name,
                )
            }.getOrDefault(CaptureCoordinator.StreamPathMode.FullAv),
        )
    }

    fun saveConfig(context: Context, config: ObsStreamConfig) {
        context.preferences().edit()
            .putString(KEY_HOST, config.host.trim())
            .putInt(KEY_PORT, config.port)
            .putString(KEY_PROFILE, config.profile)
            .putString(KEY_STREAM_MODE, config.streamMode.name)
            .apply()
    }

    fun resetConfig(context: Context): ObsStreamConfig {
        val reset = ObsStreamConfig()
        saveConfig(context, reset)
        sessionState = SessionState.Idle
        lastError = ""
        lastValidation = "Reset to default OBS preset"
        lastConnectionTest = "Not tested"
        lastDiagnosticSummary = "diagnostics pending"
        lastDiagnosticAtMs = 0
        return reset
    }

    fun buildObsUrl(config: ObsStreamConfig): String {
        return config.toEndpointSpec().toSrtUrl()
    }

    fun validateConfig(config: ObsStreamConfig): ValidationResult {
        val host = config.host.trim()
        val hostMissing = host.isEmpty()
        val invalidPort = config.port !in 1..65535

        val result = when {
            hostMissing -> ValidationResult(false, "host missing")
            invalidPort -> ValidationResult(false, "port invalid")
            else -> ValidationResult(true, "ready")
        }

        sessionState = if (result.isValid) SessionState.Ready else SessionState.Idle
        lastValidation = result.message
        if (!result.isValid) {
            lastError = result.message
        }
        return result
    }

    fun testEndpoint(config: ObsStreamConfig): String {
        val preflightMessage = preflightStartMessage(config)
        lastConnectionTest = if (preflightMessage != null) {
            preflightMessage
        } else {
            "endpoint configuration valid"
        }
        return lastConnectionTest
    }

    fun startStream(config: ObsStreamConfig): StreamResult {
        val preflightMessage = preflightStartMessage(config)
        if (preflightMessage != null) {
            sessionState = SessionState.Faulted
            lastError = preflightMessage
            return StreamResult(state = sessionState, error = lastError)
        }

        sessionState = SessionState.Starting
        StreamPipelineMetrics.reset()
        lastDiagnosticSummary = "diagnostics pending"
        lastDiagnosticAtMs = 0
        val started = transportGateway.startStream(config.toEndpointSpec(), config.streamMode)
        return if (started.isSuccess) {
            sessionState = SessionState.Streaming
            lastError = ""
            StreamResult(state = sessionState)
        } else {
            sessionState = SessionState.Faulted
            lastError = "start transport failed: ${started.exceptionOrNull()?.message.orEmpty()}"
            StreamResult(state = sessionState, error = lastError)
        }
    }

    fun stopStream(): StreamResult {
        sessionState = SessionState.Stopping
        val stopped = transportGateway.stopStream()
        return if (stopped.isSuccess) {
            sessionState = SessionState.Idle
            lastDiagnosticSummary = "diagnostics pending"
            lastDiagnosticAtMs = 0
            StreamResult(state = sessionState)
        } else {
            sessionState = SessionState.Faulted
            lastError = stopped.exceptionOrNull()?.message.orEmpty()
            StreamResult(state = sessionState, error = lastError)
        }
    }


    fun markFault(message: String): StreamResult {
        sessionState = SessionState.Faulted
        lastError = message
        return StreamResult(state = sessionState, error = lastError)
    }

    fun currentState(): SessionState = sessionState

    fun lastError(): String = lastError

    fun lastValidation(): String = lastValidation

    fun lastConnectionTest(): String = lastConnectionTest

    fun pipelineMetricsSnapshot(): StreamPipelineMetrics.Snapshot = StreamPipelineMetrics.snapshot()

    fun interoperabilityStatus(config: ObsStreamConfig): String {
        val host = config.host.trim()
        if (host.isEmpty()) {
            return "set OBS host and port, then copy Input into OBS Media Source"
        }

        if (config.port !in 1..65535) {
            return "set a valid port (1-65535) for OBS listener"
        }

        val preflightMessage = preflightStartMessage(config)
        if (preflightMessage != null) {
            return "$preflightMessage; waiting for live transport health"
        }

        val videoStats = VideoEncoderNode.runtimeStats()
        val audioStats = AudioEncoderNode.runtimeStats()
        val pipelineSnapshot = StreamPipelineMetrics.snapshot()
        val ingressSummary =
            "ingress(videoCalls=${pipelineSnapshot.muxVideoIngestCount},audioCalls=${pipelineSnapshot.muxAudioIngestCount})"
        val backendDiagnostics = transportGateway.diagnosticsSummary()
        return when {
            sessionState != SessionState.Streaming -> "ffmpeg backend ready; start to begin stream session"
            else -> {
                val diagnostics = refreshDiagnosticsSnapshotIfDue()
                "streaming health: mode=${config.streamMode.name} " +
                    "video(frames=${videoStats.framesEncoded},queued=${videoStats.framesQueuedIn},dropNoInput=${videoStats.framesDroppedNoInputBuffer},state=${videoStats.state}) " +
                    "audio(frames=${audioStats.framesEncoded}) " +
                    "$ingressSummary " +
                    "backend=${transportGateway.activeBackendName()} backendDiag={$backendDiagnostics} " +
                    "diag{$diagnostics}"
            }
        }
    }

    fun nextStreamMode(current: CaptureCoordinator.StreamPathMode): CaptureCoordinator.StreamPathMode {
        return when (current) {
            CaptureCoordinator.StreamPathMode.FullAv -> CaptureCoordinator.StreamPathMode.VideoOnly
            CaptureCoordinator.StreamPathMode.VideoOnly -> CaptureCoordinator.StreamPathMode.AudioOnly
            CaptureCoordinator.StreamPathMode.AudioOnly -> CaptureCoordinator.StreamPathMode.FullAv
        }
    }

    private fun refreshDiagnosticsSnapshotIfDue(nowMs: Long = System.currentTimeMillis()): String {
        if (nowMs - lastDiagnosticAtMs >= DIAGNOSTIC_REFRESH_INTERVAL_MS || lastDiagnosticSummary == "diagnostics pending") {
            val snapshot = StreamPipelineMetrics.captureDiagnosticSnapshot(
                frameBudgetUs = FRAME_BUDGET_US,
                nowMs = nowMs,
            )
            lastDiagnosticSummary = snapshot.compactSummary()
            lastDiagnosticAtMs = nowMs
        }
        return lastDiagnosticSummary
    }

    private fun ObsStreamConfig.toEndpointSpec(): ObsEndpointSpec {
        return ObsEndpointSpec(
            host = host.trim(),
            port = port,
            latencyMs = 120,
            mode = "caller",
        )
    }

    private fun transportAvailabilityMessage(): String = transportGateway.availabilityMessage()

    private fun preflightStartMessage(config: ObsStreamConfig): String? {
        val host = config.host.trim()
        if (host.isEmpty()) {
            return "preflight failed: host missing"
        }

        if (config.port !in 1..65535) {
            return "preflight failed: port invalid"
        }

        if (!transportGateway.isAvailable()) {
            return "preflight failed: ${transportAvailabilityMessage()}"
        }

        return null
    }

    interface StreamTransportGateway {
        fun isAvailable(): Boolean
        fun availabilityMessage(): String
        fun activeBackendName(): String
        fun startStream(endpoint: ObsEndpointSpec, streamMode: CaptureCoordinator.StreamPathMode): Result<Unit>
        fun stopStream(): Result<Unit>
        fun diagnosticsSummary(): String
    }

    private object DefaultTransportGateway : StreamTransportGateway {
        private var activeBackendId: NativeStreamBackend.BackendId = NativeStreamBackend.BackendId.Ffmpeg

        override fun isAvailable(): Boolean {
            return NativeStreamBackends.activeBackend().isAvailable()
        }

        override fun availabilityMessage(): String {
            return NativeStreamBackends.availabilitySummary()
        }

        override fun activeBackendName(): String {
            return activeBackendId.name
        }

        override fun startStream(endpoint: ObsEndpointSpec, streamMode: CaptureCoordinator.StreamPathMode): Result<Unit> {
            val backend = NativeStreamBackends.activeBackend()
            activeBackendId = backend.id
            return backend.start(endpoint, streamMode)
        }

        override fun stopStream(): Result<Unit> {
            val backend = NativeStreamBackends.activeBackend()
            val stopResult = backend.stop()
            activeBackendId = backend.id
            return stopResult
        }

        override fun diagnosticsSummary(): String {
            return NativeStreamBackends.diagnosticsSummary()
        }
    }

    private fun Context.preferences(): SharedPreferences {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
