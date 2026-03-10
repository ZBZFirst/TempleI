package com.example.templei.feature.export

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

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
    private const val CAMERA_QUEUE_WARN_DEPTH = 2
    private const val ENCODER_QUEUE_WARN_DEPTH = 16

    @Volatile
    private var lastStageGateSignature: String = ""

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
        NativeStreamBackends.resetIngressRuntimeStats()
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
            return "set OBS host and port, then copy Input URL into OBS Media Source"
        }

        if (config.port !in 1..65535) {
            return "set a valid port (1-65535) for OBS listener"
        }

        val preflightMessage = preflightStartMessage(config)
        if (preflightMessage != null) {
            return "$preflightMessage; waiting for live transport health"
        }

        val captureStats = CaptureCoordinator.runtimeStats()
        val videoStats = VideoEncoderNode.runtimeStats()
        val audioStats = AudioEncoderNode.runtimeStats()
        val pipelineSnapshot = StreamPipelineMetrics.snapshot()
        val backendIngressStats = NativeStreamBackends.ingressRuntimeStats()
        val ingressSummary =
            "ingress(videoCalls=${pipelineSnapshot.muxVideoIngestCount},audioCalls=${pipelineSnapshot.muxAudioIngestCount},ingress_rejected=${backendIngressStats.ingressRejectedCount},backend_not_ready=${backendIngressStats.backendNotReadyCount},native_error=${backendIngressStats.nativeErrorCount})"
        val backendDiagnostics = transportGateway.diagnosticsSummary()
        val connectionState = deriveConnectionState(backendDiagnostics)
        val mediaIngressStatus = deriveMediaIngressStatus(config, videoStats, audioStats)
        val packetWriteStatus = derivePacketWriteStatus(backendDiagnostics)
        val ingressMismatch = deriveIngressMismatch(
            streamMode = config.streamMode,
            videoEncodedAu = videoStats.encodedAccessUnitCount,
            audioEncodedAu = audioStats.encodedAccessUnitCount,
            videoIngressCalls = captureStats.videoIngressCalls,
            audioIngressCalls = captureStats.audioIngressCalls,
        )
        val queuePressure = deriveQueuePressure(
            cameraQueueDepth = captureStats.cameraQueueDepth,
            encoderQueueDepth = captureStats.encoderQueueDepth,
            cameraDropCount = pipelineSnapshot.cameraToEncoderDropCount,
            encoderDropCount = pipelineSnapshot.encoderToMuxDropCount,
        )
        return when {
            sessionState != SessionState.Streaming -> "ffmpeg backend ready; start to begin stream session"
            else -> {
                val diagnostics = refreshDiagnosticsSnapshotIfDue()
                val interopIssue = deriveInteropIssue(backendDiagnostics)
                val stageGate = deriveInteropStageGate(
                    InteropStageInputs(
                        streamMode = config.streamMode,
                        cameraFramesEnqueued = captureStats.cameraFramesEnqueued,
                        videoEncodedAu = videoStats.encodedAccessUnitCount,
                        audioEncodedAu = audioStats.encodedAccessUnitCount,
                        videoIngressCalls = captureStats.videoIngressCalls,
                        audioIngressCalls = captureStats.audioIngressCalls,
                        muxVideoIngest = pipelineSnapshot.muxVideoIngestCount,
                        muxAudioIngest = pipelineSnapshot.muxAudioIngestCount,
                        packetCount = parsePacketCount(backendDiagnostics),
                        connectionState = connectionState,
                        packetWriteStatus = packetWriteStatus,
                        interopIssue = interopIssue,
                        queuePressure = queuePressure,
                    ),
                )
                logStageGateTransitionIfNeeded(stageGate)
                "streaming health: mode=${config.streamMode.name} media=$mediaIngressStatus packetWrite=$packetWriteStatus conn=$connectionState " +
                    "stage{${stageGate.summary}} firstFailedStage=${stageGate.firstFailedStage} reasonCode=${stageGate.reasonCode} " +
                    "ingressMismatch=$ingressMismatch pressure=$queuePressure issue=$interopIssue " +
                    "camera(enqueued=${captureStats.cameraFramesEnqueued},dequeued=${captureStats.cameraFramesDequeued},dropped=${captureStats.cameraFramesDropped},depth=${captureStats.cameraQueueDepth}) " +
                    "video(frames=${videoStats.framesEncoded},encodedAu=${videoStats.encodedAccessUnitCount},keyframes=${videoStats.keyFrameCount},lastPtsUs=${videoStats.lastVideoPresentationTimeUs},queued=${videoStats.framesQueuedIn},dropNoInput=${videoStats.framesDroppedNoInputBuffer},state=${videoStats.state}) " +
                    "audio(frames=${audioStats.framesEncoded},encodedAu=${audioStats.encodedAccessUnitCount},codecConfigEvents=${audioStats.codecConfigEventCount},lastPtsUs=${audioStats.lastAudioPresentationTimeUs},state=${audioStats.state}) " +
                    "$ingressSummary " +
                    "backend=${transportGateway.activeBackendName()} " +
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



    private fun deriveMediaIngressStatus(
        config: ObsStreamConfig,
        videoStats: VideoEncoderNode.RuntimeStats,
        audioStats: AudioEncoderNode.RuntimeStats,
    ): String {
        return when (config.streamMode) {
            CaptureCoordinator.StreamPathMode.VideoOnly -> if (videoStats.encodedAccessUnitCount > 0) "flowing" else "stalled"
            CaptureCoordinator.StreamPathMode.AudioOnly -> if (audioStats.encodedAccessUnitCount > 0) "flowing" else "stalled"
            CaptureCoordinator.StreamPathMode.FullAv -> if (videoStats.encodedAccessUnitCount > 0 && audioStats.encodedAccessUnitCount > 0) "flowing" else "stalled"
        }
    }

    private fun derivePacketWriteStatus(backendDiagnostics: String): String {
        val packets = parsePacketCount(backendDiagnostics)
        val consecutiveWriteFailures = Regex("""consecutiveWriteFailures=(\d+)""").find(backendDiagnostics)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return when {
            packets > 0L && consecutiveWriteFailures == 0L -> "active"
            packets == 0L && consecutiveWriteFailures > 0L -> "faulted"
            else -> "pending"
        }
    }

    private fun deriveIngressMismatch(
        streamMode: CaptureCoordinator.StreamPathMode,
        videoEncodedAu: Long,
        audioEncodedAu: Long,
        videoIngressCalls: Long,
        audioIngressCalls: Long,
    ): String {
        return when (streamMode) {
            CaptureCoordinator.StreamPathMode.VideoOnly -> if (videoEncodedAu > 0L && videoIngressCalls == 0L) "video-unmapped" else "none"
            CaptureCoordinator.StreamPathMode.AudioOnly -> if (audioEncodedAu > 0L && audioIngressCalls == 0L) "audio-unmapped" else "none"
            CaptureCoordinator.StreamPathMode.FullAv -> when {
                videoEncodedAu > 0L && videoIngressCalls == 0L -> "video-unmapped"
                audioEncodedAu > 0L && audioIngressCalls == 0L -> "audio-unmapped"
                else -> "none"
            }
        }
    }

    private fun deriveQueuePressure(
        cameraQueueDepth: Int,
        encoderQueueDepth: Int,
        cameraDropCount: Long,
        encoderDropCount: Long,
    ): String {
        return when {
            cameraDropCount > 0L || encoderDropCount > 0L -> "drop"
            cameraQueueDepth >= CAMERA_QUEUE_WARN_DEPTH || encoderQueueDepth >= ENCODER_QUEUE_WARN_DEPTH -> "backlog"
            else -> "none"
        }
    }

    private fun parsePacketCount(backendDiagnostics: String): Long {
        return Regex("""packetsWritten=(\d+)""").find(backendDiagnostics)?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex("""packets=(\d+)""").find(backendDiagnostics)?.groupValues?.get(1)?.toLongOrNull()
            ?: 0L
    }

    private fun logStageGateTransitionIfNeeded(stageGate: InteropStageGate) {
        val signature = "${stageGate.firstFailedStage}:${stageGate.reasonCode}:${stageGate.summary}"
        if (signature != lastStageGateSignature) {
            lastStageGateSignature = signature
            Log.i(TAG, "interop stage gate update: $signature")
        }
    }

    private fun deriveConnectionState(backendDiagnostics: String): String {
        val match = Regex("""connState=([a-zA-Z]+)""").find(backendDiagnostics)
        return match?.groupValues?.getOrNull(1)?.lowercase() ?: "unknown"
    }

    private fun deriveInteropIssue(backendDiagnostics: String): String {
        val healthHint = Regex("""healthHint=\{([^}]*)\}""")
            .find(backendDiagnostics)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()
        if (healthHint.isNotEmpty()) {
            return healthHint
        }

        return "none"
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

internal data class InteropStageInputs(
    val streamMode: CaptureCoordinator.StreamPathMode,
    val cameraFramesEnqueued: Long,
    val videoEncodedAu: Long,
    val audioEncodedAu: Long,
    val videoIngressCalls: Long,
    val audioIngressCalls: Long,
    val muxVideoIngest: Long,
    val muxAudioIngest: Long,
    val packetCount: Long,
    val connectionState: String,
    val packetWriteStatus: String,
    val interopIssue: String,
    val queuePressure: String,
)

internal data class InteropStageGate(
    val summary: String,
    val firstFailedStage: String,
    val reasonCode: String,
)

internal fun deriveInteropStageGate(inputs: InteropStageInputs): InteropStageGate {
    val captureOk = inputs.streamMode == CaptureCoordinator.StreamPathMode.AudioOnly || inputs.cameraFramesEnqueued > 0
    val videoEncodeOk = inputs.streamMode == CaptureCoordinator.StreamPathMode.AudioOnly || inputs.videoEncodedAu > 0
    val audioEncodeOk = inputs.streamMode == CaptureCoordinator.StreamPathMode.VideoOnly || inputs.audioEncodedAu > 0
    val nativeIngressOk = when (inputs.streamMode) {
        CaptureCoordinator.StreamPathMode.VideoOnly -> inputs.videoIngressCalls > 0
        CaptureCoordinator.StreamPathMode.AudioOnly -> inputs.audioIngressCalls > 0
        CaptureCoordinator.StreamPathMode.FullAv -> inputs.videoIngressCalls > 0 && inputs.audioIngressCalls > 0
    }
    val muxWriteOk = inputs.packetCount > 0
    val transportOk = inputs.connectionState == "connected" && inputs.packetWriteStatus == "active"

    val firstFailedStage = when {
        !captureOk -> "capture"
        !videoEncodeOk -> "videoEncode"
        !audioEncodeOk -> "audioEncode"
        !nativeIngressOk -> "nativeIngress"
        !muxWriteOk -> "muxWrite"
        !transportOk -> "transport"
        else -> "none"
    }

    val reasonCode = when {
        inputs.interopIssue.contains("stubbed", ignoreCase = true) -> "StubRuntime"
        inputs.queuePressure == "drop" -> "QueueDrop"
        inputs.queuePressure == "backlog" -> "QueueBacklog"
        inputs.packetWriteStatus == "faulted" -> "NativeWriteFault"
        firstFailedStage == "capture" -> "CaptureIdle"
        firstFailedStage == "videoEncode" -> "VideoEncoderIdle"
        firstFailedStage == "audioEncode" -> "AudioEncoderIdle"
        firstFailedStage == "nativeIngress" -> "IngressIdle"
        firstFailedStage == "muxWrite" -> "MuxWritePending"
        firstFailedStage == "transport" -> "TransportNotConnected"
        else -> "None"
    }

    val summary =
        "capture=${if (captureOk) "ok" else "pending"}," +
            "videoEncode=${if (videoEncodeOk) "ok" else "pending"}," +
            "audioEncode=${if (audioEncodeOk) "ok" else "pending"}," +
            "nativeIngress=${if (nativeIngressOk) "ok" else "pending"}," +
            "muxWrite=${if (muxWriteOk) "ok" else "pending"}," +
            "transport=${if (transportOk) "ok" else "pending"}"

    return InteropStageGate(
        summary = summary,
        firstFailedStage = firstFailedStage,
        reasonCode = reasonCode,
    )
}
