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
    private const val STREAM_TAG = "TempleI-Stream"
    private const val ERROR_TAG = "TempleI-Error"
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
        val streamMode: CaptureCoordinator.StreamPathMode = CaptureCoordinator.StreamPathMode.VideoOnly,
    )

    data class ValidationResult(
        val isValid: Boolean,
        val message: String,
    )

    data class StreamError(
        val code: String,
        val message: String,
        val failureReport: StreamFailureReport? = null,
    )

    data class StreamResult(
        val state: SessionState,
        val error: String? = null,
        val detail: StreamError? = null,
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

    private val stateMachine = StreamSessionStateMachine()

    private var sessionState: SessionState = SessionState.Idle
    private var lastError: String = ""
    private var lastFailureReport: StreamFailureReport? = null
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

    private fun syncSessionStateFromMachine() {
        sessionState = when (stateMachine.currentState()) {
            StreamSessionStateMachine.State.IDLE -> SessionState.Idle
            StreamSessionStateMachine.State.PREVIEW_READY -> SessionState.Ready
            StreamSessionStateMachine.State.STREAM_INITIALIZING -> SessionState.Starting
            StreamSessionStateMachine.State.STREAM_ACTIVE -> SessionState.Streaming
            StreamSessionStateMachine.State.STREAM_FAILED -> SessionState.Faulted
            StreamSessionStateMachine.State.STREAM_STOPPING -> SessionState.Stopping
        }
    }

    private fun transition(event: StreamSessionStateMachine.Event) {
        stateMachine.transition(event)
        syncSessionStateFromMachine()
    }

    private fun structuredError(code: String, message: String): StreamError {
        val report = StreamFailureReport.capture(message)
        lastFailureReport = report
        return StreamError(code = code, message = message, failureReport = report)
    }

    fun loadConfig(context: Context): ObsStreamConfig {
        val prefs = context.preferences()
        return ObsStreamConfig(
            host = prefs.getString(KEY_HOST, "").orEmpty(),
            port = prefs.getInt(KEY_PORT, DEFAULT_PORT),
            profile = prefs.getString(KEY_PROFILE, PROFILE_BALANCED).orEmpty(),
            streamMode = runCatching {
                CaptureCoordinator.StreamPathMode.valueOf(
                    prefs.getString(KEY_STREAM_MODE, CaptureCoordinator.StreamPathMode.VideoOnly.name)
                        ?: CaptureCoordinator.StreamPathMode.VideoOnly.name,
                )
            }.getOrDefault(CaptureCoordinator.StreamPathMode.VideoOnly).let {
                if (it == CaptureCoordinator.StreamPathMode.ConnectionOnly) CaptureCoordinator.StreamPathMode.VideoOnly else it
            },
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
        transition(StreamSessionStateMachine.Event.Reset)
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

        transition(if (result.isValid) StreamSessionStateMachine.Event.PreviewBound else StreamSessionStateMachine.Event.Reset)
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
        Log.i(STREAM_TAG, "tsMs=${System.currentTimeMillis()} milestone=startStream begin state=$sessionState")
        if (sessionState == SessionState.Streaming || sessionState == SessionState.Starting) {
            return StreamResult(state = sessionState)
        }

        val preflightMessage = preflightStartMessage(config)
        if (preflightMessage != null) {
            transition(StreamSessionStateMachine.Event.StartFailed(preflightMessage))
            lastError = preflightMessage
            val detail = structuredError("preflight_failed", preflightMessage)
            return StreamResult(state = sessionState, error = lastError, detail = detail)
        }

        transition(StreamSessionStateMachine.Event.StartRequested)
        Log.i(STREAM_TAG, "tsMs=${System.currentTimeMillis()} milestone=encoders starting")
        Log.i(STREAM_TAG, "tsMs=${System.currentTimeMillis()} milestone=pipeline initialized mode=${config.streamMode}")
        StreamPipelineMetrics.reset()
        NativeStreamBackends.resetIngressRuntimeStats()
        lastDiagnosticSummary = "diagnostics pending"
        lastDiagnosticAtMs = 0

        val started = runCatching {
            transportGateway.startStream(config.toTransportEndpointSpec(), config.streamMode)
        }.getOrElse { throwable ->
            Result.failure(IllegalStateException("transport start invocation failed: ${throwable.message.orEmpty()}"))
        }

        return if (started.isSuccess) {
            transition(StreamSessionStateMachine.Event.StartSucceeded)
            Log.i(STREAM_TAG, "tsMs=${System.currentTimeMillis()} milestone=encoders started")
            val runtimeHealth = runtimeHealthSnapshot()
            if (!runtimeHealth.runtimeActive) {
                Log.w(
                    STREAM_TAG,
                    "tsMs=${System.currentTimeMillis()} runtime gate bypassed after successful native start runtimeMode=${runtimeHealth.runtimeMode}",
                )
            }
            lastError = ""
            lastFailureReport = null
            lastEffectiveTransportUrl = config.toTransportEndpointSpec().toSrtUrl()
            lastConnectionTest = "CONNECTION SUCCESSFUL: SRT caller connected to OBS listener"
            Log.i(STREAM_TAG, "tsMs=${System.currentTimeMillis()} milestone=stream started url=$lastEffectiveTransportUrl")
            StreamResult(state = sessionState)
        } else {
            val message = "start transport failed: ${started.exceptionOrNull()?.message.orEmpty()}"
            transition(StreamSessionStateMachine.Event.StartFailed(message))
            lastError = message
            lastConnectionTest = "connection failed: ${started.exceptionOrNull()?.message.orEmpty()}"
            Log.e(ERROR_TAG, "tsMs=${System.currentTimeMillis()} stream-start-failed reason=$lastError")
            val detail = structuredError("start_failed", message)
            StreamResult(state = sessionState, error = lastError, detail = detail)
        }
    }

    fun stopStream(): StreamResult {
        transition(StreamSessionStateMachine.Event.StopRequested)
        val stopped = runCatching { transportGateway.stopStream() }
            .getOrElse { throwable -> Result.failure(IllegalStateException("transport stop invocation failed: ${throwable.message.orEmpty()}")) }
        return if (stopped.isSuccess) {
            transition(StreamSessionStateMachine.Event.StopSucceeded)
            lastFailureReport = null
            lastDiagnosticSummary = "diagnostics pending"
            lastDiagnosticAtMs = 0
            Log.i(STREAM_TAG, "tsMs=${System.currentTimeMillis()} milestone=stream stopped")
            StreamResult(state = sessionState)
        } else {
            val message = stopped.exceptionOrNull()?.message.orEmpty()
            transition(StreamSessionStateMachine.Event.StopFailed(message))
            lastError = message
            Log.e(ERROR_TAG, "tsMs=${System.currentTimeMillis()} stream-stop-failed reason=$lastError")
            val detail = structuredError("stop_failed", message)
            StreamResult(state = sessionState, error = lastError, detail = detail)
        }
    }


    fun markFault(message: String): StreamResult {
        transition(StreamSessionStateMachine.Event.StartFailed(message))
        lastError = message
        val detail = structuredError("marked_fault", message)
        return StreamResult(state = sessionState, error = lastError, detail = detail)
    }

    fun currentState(): SessionState = sessionState

    fun lastError(): String = lastError

    fun lastValidation(): String = lastValidation

    fun lastConnectionTest(): String = lastConnectionTest

    fun lastEffectiveTransportUrl(): String = lastEffectiveTransportUrl

    fun lastFailureReport(): StreamFailureReport? = lastFailureReport

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
        val trailerWritten = parseBooleanField(backendDiagnostics, "trailerWritten")
        val stageDiagnostics = refreshDiagnosticsSnapshotIfDue(nowMs)
        val adbFilter = "TempleI-Stream:V TempleI-VideoEnc:V TempleI-AudioEnc:V TempleI-Mux:V TempleI-SRT:V TempleI-Net:V TempleI-Error:V *:S"
        val adbCaptureCommand = "adb logcat -v time $adbFilter | head -n 200 > templei-startup-$runId.log"

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
            appendLine("trailerWritten=$trailerWritten")
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
            CaptureCoordinator.StreamPathMode.ConnectionOnly,
            CaptureCoordinator.StreamPathMode.VideoOnly,
            -> CaptureCoordinator.StreamPathMode.AudioOnly
            CaptureCoordinator.StreamPathMode.AudioOnly -> CaptureCoordinator.StreamPathMode.FullAv
            CaptureCoordinator.StreamPathMode.FullAv -> CaptureCoordinator.StreamPathMode.VideoOnly
        }
    }

    fun streamModeLabel(mode: CaptureCoordinator.StreamPathMode): String {
        return when (mode) {
            CaptureCoordinator.StreamPathMode.ConnectionOnly -> "Video Only"
            CaptureCoordinator.StreamPathMode.VideoOnly -> "Video Only"
            CaptureCoordinator.StreamPathMode.AudioOnly -> "Audio Only"
            CaptureCoordinator.StreamPathMode.FullAv -> "Video + Audio"
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
