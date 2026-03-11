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
        val streamMode: CaptureCoordinator.StreamPathMode = CaptureCoordinator.StreamPathMode.ConnectionOnly,
    )

    data class ValidationResult(
        val isValid: Boolean,
        val message: String,
    )

    data class StreamResult(
        val state: SessionState,
        val error: String? = null,
    )

    data class EndpointValidationSnapshot(
        val isValid: Boolean,
        val message: String,
        val obsInputUrl: String,
        val transportCallerUrl: String,
    )

    data class RuntimeHealthSnapshot(
        val runtimeMode: String,
        val connectionState: String,
        val packetsWritten: Long,
        val lastNativeError: String,
        val runtimeActive: Boolean,
    )

    data class DiagnosticsSnapshot(
        val runId: String,
        val content: String,
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
    private var lastEffectiveTransportUrl: String = "n/a"

    private const val DIAGNOSTIC_REFRESH_INTERVAL_MS = 1_000L
    private const val FRAME_BUDGET_US = 33_333L
    private const val CAMERA_QUEUE_WARN_DEPTH = 2
    private const val ENCODER_QUEUE_WARN_DEPTH = 16
    private const val PACKET_OUTPUT_WARNING_THRESHOLD = 24L
    private const val STARTUP_CAPTURE_SECONDS = 30

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
                    prefs.getString(KEY_STREAM_MODE, CaptureCoordinator.StreamPathMode.ConnectionOnly.name)
                        ?: CaptureCoordinator.StreamPathMode.ConnectionOnly.name,
                )
            }.getOrDefault(CaptureCoordinator.StreamPathMode.ConnectionOnly),
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
        lastEffectiveTransportUrl = "n/a"
        return reset
    }

    fun endpointValidationSnapshot(config: ObsStreamConfig): EndpointValidationSnapshot {
        val obsInputSpec = config.toObsInputSpec()
        val transportSpec = config.toTransportEndpointSpec()
        val obsInputUrl = obsInputSpec.toSrtUrl()
        val transportCallerUrl = transportSpec.toSrtUrl()

        val message = when {
            obsInputSpec.host.isBlank() || transportSpec.host.isBlank() -> "host missing"
            obsInputSpec.port !in 1..65535 || transportSpec.port !in 1..65535 -> "port invalid"
            obsInputSpec.mode != "listener" -> "OBS Input URL must use mode=listener"
            transportSpec.mode != "caller" -> "Android transport must use mode=caller"
            !obsInputUrl.contains("mode=listener") -> "OBS Input URL malformed: missing mode=listener"
            !transportCallerUrl.contains("mode=caller") -> "transport URL malformed: missing mode=caller"
            else -> "ready"
        }

        return EndpointValidationSnapshot(
            isValid = message == "ready",
            message = message,
            obsInputUrl = obsInputUrl,
            transportCallerUrl = transportCallerUrl,
        )
    }

    fun buildObsUrl(config: ObsStreamConfig): String {
        return config.toObsInputSpec().toSrtUrl()
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
            lastEffectiveTransportUrl = config.toTransportEndpointSpec().toSrtUrl()
            "endpoint configuration valid"
        }
        return lastConnectionTest
    }

    fun startStream(config: ObsStreamConfig): StreamResult {
        if (sessionState == SessionState.Streaming || sessionState == SessionState.Starting) {
            return StreamResult(state = sessionState)
        }

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
        val started = transportGateway.startStream(config.toTransportEndpointSpec(), config.streamMode)
        return if (started.isSuccess) {
            val runtimeHealth = runtimeHealthSnapshot()
            if (!runtimeHealth.runtimeActive) {
                sessionState = SessionState.Faulted
                val remediation = if (runtimeHealth.runtimeMode.equals("stub", ignoreCase = true)) {
                    "runtimeMode=stub; install/enable native runtime before streaming"
                } else {
                    "runtime mode unavailable or inactive (${runtimeHealth.runtimeMode}); verify native runtime binding"
                }
                lastError = "start blocked: $remediation"
                lastConnectionTest = "connection failed: $remediation"
                transportGateway.stopStream()
                StreamResult(state = sessionState, error = lastError)
            } else {
                sessionState = SessionState.Streaming
                lastError = ""
                lastEffectiveTransportUrl = config.toTransportEndpointSpec().toSrtUrl()
                lastConnectionTest = "CONNECTION SUCCESSFUL: SRT caller connected to OBS listener"
                StreamResult(state = sessionState)
            }
        } else {
            sessionState = SessionState.Faulted
            lastError = "start transport failed: ${started.exceptionOrNull()?.message.orEmpty()}"
            lastConnectionTest = "connection failed: ${started.exceptionOrNull()?.message.orEmpty()}"
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

    fun lastEffectiveTransportUrl(): String = lastEffectiveTransportUrl

    fun runtimeHealthSnapshot(): RuntimeHealthSnapshot {
        return parseRuntimeHealthSnapshot(transportGateway.diagnosticsSummary())
    }

    fun createDiagnosticsSnapshot(config: ObsStreamConfig, nowMs: Long = System.currentTimeMillis()): DiagnosticsSnapshot {
        val runId = "run-$nowMs"
        val obsInputUrl = buildObsUrl(config)
        val callerUrl = config.toTransportEndpointSpec().toSrtUrl()
        val backendDiagnostics = transportGateway.diagnosticsSummary()
        val runtime = parseRuntimeHealthSnapshot(backendDiagnostics)
        val writePacketsFailed = parseLongField(backendDiagnostics, "writePacketsFailed")
        val consecutiveWriteFailures = parseLongField(backendDiagnostics, "consecutiveWriteFailures")
        val muxPacketsProduced = parseLongField(backendDiagnostics, "muxPacketsProduced")
        val outputOpened = parseBooleanField(backendDiagnostics, "outputOpened")
        val headerWritten = parseBooleanField(backendDiagnostics, "headerWritten")
        val stageDiagnostics = refreshDiagnosticsSnapshotIfDue(nowMs)
        val adbFilter = "TempleI-ExportFeature:V TsMuxerNode:V SrtTransportNode:V NativeStreamBackend:V VideoEncoderNode:V AudioEncoderNode:V *:S"
        val adbCaptureCommand = "adb logcat -v time $adbFilter | head -n 200 > startup-$runId.log"

        val content = buildString {
            appendLine("runId=$runId")
            appendLine("capturedAtMs=$nowMs")
            appendLine("captureWindowSeconds=$STARTUP_CAPTURE_SECONDS")
            appendLine("obsInputUrl=$obsInputUrl")
            appendLine("transportCallerUrl=$callerUrl")
            appendLine("runtimeMode=${runtime.runtimeMode}")
            appendLine("connectionState=${runtime.connectionState}")
            appendLine("packetsWritten=${runtime.packetsWritten}")
            appendLine("muxPacketsProduced=$muxPacketsProduced")
            appendLine("writePacketsFailed=$writePacketsFailed")
            appendLine("consecutiveWriteFailures=$consecutiveWriteFailures")
            appendLine("outputOpened=$outputOpened")
            appendLine("headerWritten=$headerWritten")
            appendLine("lastNativeError=${runtime.lastNativeError}")
            appendLine("adbFilter=$adbFilter")
            appendLine("adbCaptureCommand=$adbCaptureCommand")
            appendLine("backendDiagnostics={$backendDiagnostics}")
            appendLine("pipelineDiagnostics={$stageDiagnostics}")
            appendLine("interopStatus={${interoperabilityStatus(config)}}")
        }

        return DiagnosticsSnapshot(runId = runId, content = content)
    }

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
        val packetCount = parsePacketCount(backendDiagnostics)
        val packetWriteWarning = derivePacketWriteWarning(
            muxVideoIngest = pipelineSnapshot.muxVideoIngestCount,
            muxAudioIngest = pipelineSnapshot.muxAudioIngestCount,
            packetCount = packetCount,
            warnThreshold = PACKET_OUTPUT_WARNING_THRESHOLD,
        )
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
            sessionState != SessionState.Streaming && config.streamMode == CaptureCoordinator.StreamPathMode.ConnectionOnly ->
                "connection-only mode validates endpoint only; choose Video, Audio, or Both before Start Stream"
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
                        packetCount = packetCount,
                        connectionState = connectionState,
                        packetWriteStatus = packetWriteStatus,
                        interopIssue = interopIssue,
                        queuePressure = queuePressure,
                    ),
                )
                logStageGateTransitionIfNeeded(stageGate)
                val connectionGate = if (connectionState == "connected" && packetCount > 0L && packetWriteStatus == "active") {
                    "STREAM HEALTHY"
                } else {
                    "stream not healthy yet"
                }
                "$connectionGate · streaming health: mode=${config.streamMode.name} media=$mediaIngressStatus packetWrite=$packetWriteStatus conn=$connectionState packets=$packetCount " +
                    "stage{${stageGate.summary}} firstFailedStage=${stageGate.firstFailedStage} reasonCode=${stageGate.reasonCode} " +
                    "ingressMismatch=$ingressMismatch pressure=$queuePressure packetWarning=$packetWriteWarning issue=$interopIssue " +
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
            CaptureCoordinator.StreamPathMode.ConnectionOnly -> CaptureCoordinator.StreamPathMode.VideoOnly
            CaptureCoordinator.StreamPathMode.VideoOnly -> CaptureCoordinator.StreamPathMode.AudioOnly
            CaptureCoordinator.StreamPathMode.AudioOnly -> CaptureCoordinator.StreamPathMode.FullAv
            CaptureCoordinator.StreamPathMode.FullAv -> CaptureCoordinator.StreamPathMode.ConnectionOnly
        }
    }

    fun streamModeLabel(mode: CaptureCoordinator.StreamPathMode): String {
        return when (mode) {
            CaptureCoordinator.StreamPathMode.ConnectionOnly -> "No Video or Audio, Connection Only"
            CaptureCoordinator.StreamPathMode.VideoOnly -> "Video Only"
            CaptureCoordinator.StreamPathMode.AudioOnly -> "Audio Only"
            CaptureCoordinator.StreamPathMode.FullAv -> "Video + Audio"
        }
    }



    private fun deriveMediaIngressStatus(
        config: ObsStreamConfig,
        videoStats: VideoEncoderNode.RuntimeStats,
        audioStats: AudioEncoderNode.RuntimeStats,
    ): String {
        return when (config.streamMode) {
            CaptureCoordinator.StreamPathMode.ConnectionOnly -> "n/a"
            CaptureCoordinator.StreamPathMode.VideoOnly -> if (videoStats.encodedAccessUnitCount > 0) "flowing" else "stalled"
            CaptureCoordinator.StreamPathMode.AudioOnly -> if (audioStats.encodedAccessUnitCount > 0) "flowing" else "stalled"
            CaptureCoordinator.StreamPathMode.FullAv -> if (videoStats.encodedAccessUnitCount > 0 && audioStats.encodedAccessUnitCount > 0) "flowing" else "stalled"
        }
    }

    private fun derivePacketWriteStatus(backendDiagnostics: String): String {
        val packets = parsePacketCount(backendDiagnostics)
        val consecutiveWriteFailures = Regex("""consecutiveWriteFailures=(\d+)""").find(backendDiagnostics)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val outputOpened = parseBooleanField(backendDiagnostics, "outputOpened")
        val headerWritten = parseBooleanField(backendDiagnostics, "headerWritten")
        return when {
            packets > 0L && consecutiveWriteFailures == 0L && outputOpened && headerWritten -> "active"
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
            CaptureCoordinator.StreamPathMode.ConnectionOnly -> "none"
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

    internal fun derivePacketWriteWarning(
        muxVideoIngest: Long,
        muxAudioIngest: Long,
        packetCount: Long,
        warnThreshold: Long,
    ): String {
        val ingressTotal = muxVideoIngest + muxAudioIngest
        if (packetCount > 0L) {
            return "none"
        }
        return if (ingressTotal >= warnThreshold) {
            "ingress-active-without-packets"
        } else {
            "warming-up"
        }
    }

    private fun parsePacketCount(backendDiagnostics: String): Long {
        return Regex("""writePacketsSucceeded=(\d+)""").find(backendDiagnostics)?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex("""packetsWritten=(\d+)""").find(backendDiagnostics)?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex("""packets=(\d+)""").find(backendDiagnostics)?.groupValues?.get(1)?.toLongOrNull()
            ?: 0L
    }

    private fun parseLongField(backendDiagnostics: String, field: String): Long {
        return Regex("""$field=(\d+)""").find(backendDiagnostics)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }

    private fun parseBooleanField(backendDiagnostics: String, field: String): Boolean {
        return Regex("""$field=(true|false)""").find(backendDiagnostics)?.groupValues?.get(1)?.toBoolean() ?: false
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

    internal fun parseRuntimeHealthSnapshot(backendDiagnostics: String): RuntimeHealthSnapshot {
        val runtimeMode = Regex("""runtime=\{[^}]*runtimeMode=([a-zA-Z_]+)""")
            .find(backendDiagnostics)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase()
            ?: "unknown"
        val connectionState = deriveConnectionState(backendDiagnostics)
        val packetsWritten = parsePacketCount(backendDiagnostics)
        val lastNativeError = Regex("""lastErr=\{([^}]*)\}""")
            .find(backendDiagnostics)
            ?.groupValues
            ?.getOrNull(1)
            ?.ifBlank { "none" }
            ?: "none"
        val runtimeActive = runtimeMode != "unknown" && runtimeMode != "stub"

        return RuntimeHealthSnapshot(
            runtimeMode = runtimeMode,
            connectionState = connectionState,
            packetsWritten = packetsWritten,
            lastNativeError = lastNativeError,
            runtimeActive = runtimeActive,
        )
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

    private fun ObsStreamConfig.toObsInputSpec(): ObsEndpointSpec {
        return ObsEndpointSpec(
            host = host.trim(),
            port = port,
            latencyMs = 120,
            mode = "listener",
        )
    }

    private fun ObsStreamConfig.toTransportEndpointSpec(): ObsEndpointSpec {
        return ObsEndpointSpec(
            host = host.trim(),
            port = port,
            latencyMs = 120,
            mode = "caller",
        )
    }

    private fun transportAvailabilityMessage(): String = transportGateway.availabilityMessage()

    private fun preflightStartMessage(config: ObsStreamConfig): String? {
        val endpointValidation = endpointValidationSnapshot(config)
        if (!endpointValidation.isValid) {
            return "preflight failed: ${endpointValidation.message}"
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
    val captureOk =
        inputs.streamMode == CaptureCoordinator.StreamPathMode.AudioOnly ||
            inputs.streamMode == CaptureCoordinator.StreamPathMode.ConnectionOnly ||
            inputs.cameraFramesEnqueued > 0
    val videoEncodeOk =
        inputs.streamMode == CaptureCoordinator.StreamPathMode.AudioOnly ||
            inputs.streamMode == CaptureCoordinator.StreamPathMode.ConnectionOnly ||
            inputs.videoEncodedAu > 0
    val audioEncodeOk =
        inputs.streamMode == CaptureCoordinator.StreamPathMode.VideoOnly ||
            inputs.streamMode == CaptureCoordinator.StreamPathMode.ConnectionOnly ||
            inputs.audioEncodedAu > 0
    val nativeIngressOk = when (inputs.streamMode) {
        CaptureCoordinator.StreamPathMode.ConnectionOnly -> true
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
