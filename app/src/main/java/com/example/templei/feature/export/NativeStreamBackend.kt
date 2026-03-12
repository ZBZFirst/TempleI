package com.example.templei.feature.export

import android.util.Log

/**
 * Backend contract for native stream transport wiring.
 *
 * PR A introduced this boundary so Screen 2 state flow can stay stable while
 * runtime internals migrate to FFmpeg-backed transport.
 */
interface NativeStreamBackend {
    val id: BackendId

    fun isAvailable(): Boolean

    fun availabilityMessage(): String

    fun start(endpoint: ObsEndpointSpec, streamMode: CaptureCoordinator.StreamPathMode): Result<Unit>

    fun pushVideoAccessUnit(accessUnit: VideoEncoderNode.EncodedAccessUnit): Result<Unit>

    fun pushAudioAccessUnit(accessUnit: AudioEncoderNode.EncodedAccessUnit): Result<Unit>

    fun stop(): Result<Unit>

    fun diagnosticsSummary(): String

    enum class BackendId {
        Ffmpeg,
    }
}

/**
 * FFmpeg backend selector.
 *
 * Legacy TS/SRT node fallback has been removed; Screen 2 now only reports FFmpeg
 * backend readiness while PR B/PR C wiring is completed.
 */
object NativeStreamBackends {
    private val ffmpegBackend: NativeStreamBackend = FfmpegStreamBackend

    enum class IngressFailureDomain {
        None,
        IngressRejected,
        BackendNotReady,
        NativeError,
    }

    data class IngressRuntimeStats(
        val videoIngressCalls: Long,
        val audioIngressCalls: Long,
        val ingressSuccessCount: Long,
        val ingressFailureCount: Long,
        val ingressRejectedCount: Long,
        val backendNotReadyCount: Long,
        val nativeErrorCount: Long,
        val lastFailureDomain: IngressFailureDomain,
        val lastFailureMessage: String,
    )

    private val ingressLock = Any()
    private var videoIngressCalls: Long = 0
    private var audioIngressCalls: Long = 0
    private var ingressSuccessCount: Long = 0
    private var ingressFailureCount: Long = 0
    private var ingressRejectedCount: Long = 0
    private var backendNotReadyCount: Long = 0
    private var nativeErrorCount: Long = 0
    private var lastFailureDomain: IngressFailureDomain = IngressFailureDomain.None
    private var lastFailureMessage: String = ""

    @Volatile
    private var backendOverrideForTesting: NativeStreamBackend? = null

    fun resetIngressRuntimeStats() {
        synchronized(ingressLock) {
            videoIngressCalls = 0
            audioIngressCalls = 0
            ingressSuccessCount = 0
            ingressFailureCount = 0
            ingressRejectedCount = 0
            backendNotReadyCount = 0
            nativeErrorCount = 0
            lastFailureDomain = IngressFailureDomain.None
            lastFailureMessage = ""
        }
    }

    fun ingressRuntimeStats(): IngressRuntimeStats {
        synchronized(ingressLock) {
            return IngressRuntimeStats(
                videoIngressCalls = videoIngressCalls,
                audioIngressCalls = audioIngressCalls,
                ingressSuccessCount = ingressSuccessCount,
                ingressFailureCount = ingressFailureCount,
                ingressRejectedCount = ingressRejectedCount,
                backendNotReadyCount = backendNotReadyCount,
                nativeErrorCount = nativeErrorCount,
                lastFailureDomain = lastFailureDomain,
                lastFailureMessage = lastFailureMessage,
            )
        }
    }

    private fun classifyFailureDomain(message: String): IngressFailureDomain {
        val normalized = message.lowercase()
        return when {
            normalized.contains("ingress rejected") -> IngressFailureDomain.IngressRejected
            normalized.contains("runtime unavailable") || normalized.contains("backend not ready") -> IngressFailureDomain.BackendNotReady
            else -> IngressFailureDomain.NativeError
        }
    }

    private fun recordIngressResult(isVideo: Boolean, result: Result<Unit>) {
        synchronized(ingressLock) {
            if (isVideo) {
                videoIngressCalls += 1
            } else {
                audioIngressCalls += 1
            }
            if (result.isSuccess) {
                ingressSuccessCount += 1
                return
            }

            ingressFailureCount += 1
            val message = result.exceptionOrNull()?.message.orEmpty()
            val domain = classifyFailureDomain(message)
            when (domain) {
                IngressFailureDomain.IngressRejected -> ingressRejectedCount += 1
                IngressFailureDomain.BackendNotReady -> backendNotReadyCount += 1
                IngressFailureDomain.NativeError -> nativeErrorCount += 1
                IngressFailureDomain.None -> Unit
            }
            lastFailureDomain = domain
            lastFailureMessage = message
        }
    }

    fun activeBackend(): NativeStreamBackend {
        return backendOverrideForTesting ?: ffmpegBackend
    }

    fun availabilitySummary(): String {
        val active = activeBackend()
        return "active=${active.id.name} ${active.availabilityMessage()}"
    }

    fun diagnosticsSummary(): String {
        val backendSummary = activeBackend().diagnosticsSummary()
        val ingress = ingressRuntimeStats()
        val lastFailure = if (ingress.lastFailureMessage.isBlank()) "none" else ingress.lastFailureMessage
        return backendSummary +
            " ingress(videoCalls=${ingress.videoIngressCalls},audioCalls=${ingress.audioIngressCalls},success=${ingress.ingressSuccessCount},failure=${ingress.ingressFailureCount},ingress_rejected=${ingress.ingressRejectedCount},backend_not_ready=${ingress.backendNotReadyCount},native_error=${ingress.nativeErrorCount},lastDomain=${ingress.lastFailureDomain},lastFailure=$lastFailure)"
    }

    fun pushVideoAccessUnit(accessUnit: VideoEncoderNode.EncodedAccessUnit): Result<Unit> {
        val result = activeBackend().pushVideoAccessUnit(accessUnit)
        recordIngressResult(isVideo = true, result = result)
        return result
    }

    fun pushAudioAccessUnit(accessUnit: AudioEncoderNode.EncodedAccessUnit): Result<Unit> {
        val result = activeBackend().pushAudioAccessUnit(accessUnit)
        recordIngressResult(isVideo = false, result = result)
        return result
    }

    internal fun installBackendForTesting(backend: NativeStreamBackend?) {
        backendOverrideForTesting = backend
        resetIngressRuntimeStats()
    }
}

internal fun deriveFfmpegHealthHint(
    started: Boolean,
    statsSnapshot: String,
    runtimeInfo: String,
): String? {
    if (!started) {
        return null
    }

    val videoAu = Regex("""videoAu=(\d+)""").find(statsSnapshot)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
    val audioAu = Regex("""audioAu=(\d+)""").find(statsSnapshot)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
    val packetCount = Regex("""writePacketsSucceeded=(\d+)""").find(statsSnapshot)?.groupValues?.get(1)?.toLongOrNull()
        ?: Regex("""packets=(\d+)""").find(statsSnapshot)?.groupValues?.get(1)?.toLongOrNull()
        ?: -1L
    val ptsFixups = Regex("""ptsFixups=(\d+)""").find(statsSnapshot)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
    val avDeltaMaxAbsUs = Regex("""avDeltaMaxAbsUs=(\d+)""").find(statsSnapshot)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
    val runtimeLooksStub = runtimeInfo.contains("runtimeMode=stub", ignoreCase = true) || runtimeInfo.contains("stub", ignoreCase = true)

    if (runtimeLooksStub && videoAu == 0L && audioAu == 0L) {
        return "control path started but media ingress is idle (videoAu=0 audioAu=0); native runtime reports stub mode so OBS will not receive MPEG-TS/SRT payload yet"
    }

    if (videoAu == 0L && audioAu == 0L) {
        return "control path started but encoded access units are not reaching backend ingress yet"
    }

    if ((videoAu > 0L || audioAu > 0L) && packetCount == 0L) {
        return "encoded access units reached native ingress but mux/write packet output is still idle (packets=0)"
    }

    if (ptsFixups > 0L) {
        return "timestamp guard active: out-of-order PTS observed and corrected (ptsFixups=$ptsFixups)"
    }

    if (avDeltaMaxAbsUs > 200_000L) {
        return "A/V clock alignment warning: first-frame delta exceeded 200ms (avDeltaMaxAbsUs=$avDeltaMaxAbsUs)"
    }

    return null
}

private object FfmpegStreamBackend : NativeStreamBackend {
    private const val STREAM_TAG = "TempleI-Stream"
    private const val MUX_TAG = "TempleI-Mux"
    private const val SRT_TAG = "TempleI-SRT"
    private const val NET_TAG = "TempleI-Net"
    private const val ERROR_TAG = "TempleI-Error"
    private const val FFMPEG_NATIVE_LIBRARY = "templei_ffmpeg"
    private const val MAX_CONNECT_RETRIES = 3
    private const val CONNECT_RETRY_BACKOFF_MS = 120L

    private var runtime: RuntimeBinding = RuntimeBinding.Uninitialized
    private var started = false
    private var videoEnabled = false
    private var audioEnabled = false
    private var lastError: String = ""
    private var lastStatsSnapshot: String = "stats unavailable"
    private var lastRetryAttempts: Int = 0
    private var terminalFailureCount: Int = 0

    override val id: NativeStreamBackend.BackendId = NativeStreamBackend.BackendId.Ffmpeg

    override fun isAvailable(): Boolean {
        val runtimeResult = resolveRuntime()
        if (runtimeResult.isFailure) {
            return false
        }
        return runtimeResult.getOrThrow().probeRuntime()
    }

    override fun availabilityMessage(): String {
        val runtimeResult = resolveRuntime()
        if (runtimeResult.isFailure) {
            return runtimeResult.exceptionOrNull()?.message ?: "ffmpeg runtime unavailable"
        }
        return if (runtimeResult.getOrThrow().probeRuntime()) {
            "ffmpeg runtime bridge ready"
        } else {
            FfmpegNativeBridge.nativeRuntimeInfo().ifBlank { "ffmpeg runtime probe failed" }
        }
    }

    override fun start(endpoint: ObsEndpointSpec, streamMode: CaptureCoordinator.StreamPathMode): Result<Unit> {
        when (streamMode) {
            CaptureCoordinator.StreamPathMode.ConnectionOnly -> {
                videoEnabled = false
                audioEnabled = false
            }

            CaptureCoordinator.StreamPathMode.FullAv -> {
                videoEnabled = true
                audioEnabled = true
            }

            CaptureCoordinator.StreamPathMode.VideoOnly -> {
                videoEnabled = true
                audioEnabled = false
            }

            CaptureCoordinator.StreamPathMode.AudioOnly -> {
                videoEnabled = false
                audioEnabled = true
            }
        }

        if (!videoEnabled && !audioEnabled) {
            val message = "connection-only mode cannot start native stream output; select Video, Audio, or Both"
            lastError = message
            return Result.failure(IllegalStateException(message))
        }

        val runtimeResult = resolveRuntime()
        if (runtimeResult.isFailure) {
            return Result.failure(runtimeResult.exceptionOrNull() ?: IllegalStateException("ffmpeg runtime unavailable"))
        }

        val runtimeInstance = runtimeResult.getOrThrow()
        Log.i(NET_TAG, "tsMs=${System.currentTimeMillis()} milestone=network host=${endpoint.host} port=${endpoint.port} latency=${endpoint.latencyMs} mode=${endpoint.mode}")
        val prepareResult = runtimeInstance.prepare(endpoint, videoEnabled, audioEnabled)
        Log.i(MUX_TAG, "tsMs=${System.currentTimeMillis()} milestone=mux prepared videoEnabled=$videoEnabled audioEnabled=$audioEnabled")
        if (prepareResult.isFailure) {
            lastError = prepareResult.exceptionOrNull()?.message.orEmpty()
            Log.e(ERROR_TAG, "tsMs=${System.currentTimeMillis()} native-return-code prepare failed error=$lastError")
            return prepareResult
        }

        var attempt = 0
        var startFailure: Throwable? = null
        while (attempt < MAX_CONNECT_RETRIES) {
            Log.i(SRT_TAG, "tsMs=${System.currentTimeMillis()} milestone=SRT connect attempt attempt=${attempt + 1} url=${endpoint.toSrtUrl()}")
            val startResult = runtimeInstance.start()
            if (startResult.isSuccess) {
                started = true
                lastRetryAttempts = attempt
                lastError = ""
                Log.i(STREAM_TAG, "tsMs=${System.currentTimeMillis()} milestone=stream started connectionState=connected retries=$attempt")
                lastStatsSnapshot = runtimeInstance.statsSnapshot()
                return Result.success(Unit)
            }
            started = false
            startFailure = startResult.exceptionOrNull()
            Log.e(ERROR_TAG, "tsMs=${System.currentTimeMillis()} native-return-code start failed attempt=${attempt + 1} reason=${startFailure?.message.orEmpty()}")
            attempt += 1
            if (attempt < MAX_CONNECT_RETRIES) {
                Thread.sleep(CONNECT_RETRY_BACKOFF_MS)
            }
        }

        lastRetryAttempts = attempt
        terminalFailureCount += 1
        val failureMessage = startFailure?.message.orEmpty().ifBlank { "native start failed after retry budget" }
        lastError = "connect retries exhausted ($attempt/$MAX_CONNECT_RETRIES): $failureMessage"
        Log.e(ERROR_TAG, "tsMs=${System.currentTimeMillis()} native-return-code connect retries exhausted error=$lastError")
        return Result.failure(IllegalStateException(lastError))
    }

    override fun pushVideoAccessUnit(accessUnit: VideoEncoderNode.EncodedAccessUnit): Result<Unit> {
        if (!started || !videoEnabled) {
            return Result.failure(IllegalStateException("ingress rejected: video path not active"))
        }

        val runtimeResult = resolveRuntime()
        if (runtimeResult.isFailure) {
            return Result.failure(runtimeResult.exceptionOrNull() ?: IllegalStateException("ffmpeg runtime unavailable"))
        }

        val pushResult = runtimeResult.getOrThrow().pushVideo(accessUnit)
        if (pushResult.isFailure) {
            lastError = pushResult.exceptionOrNull()?.message.orEmpty()
            Log.e(ERROR_TAG, "tsMs=${System.currentTimeMillis()} packet write failure type=video reason=$lastError")
        }
        lastStatsSnapshot = runtimeResult.getOrThrow().statsSnapshot()
        return pushResult
    }

    override fun pushAudioAccessUnit(accessUnit: AudioEncoderNode.EncodedAccessUnit): Result<Unit> {
        if (!started || !audioEnabled) {
            return Result.failure(IllegalStateException("ingress rejected: audio path not active"))
        }

        val runtimeResult = resolveRuntime()
        if (runtimeResult.isFailure) {
            return Result.failure(runtimeResult.exceptionOrNull() ?: IllegalStateException("ffmpeg runtime unavailable"))
        }

        val pushResult = runtimeResult.getOrThrow().pushAudio(accessUnit)
        if (pushResult.isFailure) {
            lastError = pushResult.exceptionOrNull()?.message.orEmpty()
            Log.e(ERROR_TAG, "tsMs=${System.currentTimeMillis()} packet write failure type=audio reason=$lastError")
        }
        lastStatsSnapshot = runtimeResult.getOrThrow().statsSnapshot()
        return pushResult
    }

    override fun stop(): Result<Unit> {
        val runtimeInstance = resolveRuntime().getOrNull()
        runtimeInstance?.stop()
        Log.i(STREAM_TAG, "tsMs=${System.currentTimeMillis()} milestone=stream stopped")
        started = false
        videoEnabled = false
        audioEnabled = false
        lastStatsSnapshot = runtimeInstance?.statsSnapshot() ?: "stats unavailable"
        lastRetryAttempts = 0
        return Result.success(Unit)
    }

    override fun diagnosticsSummary(): String {
        val runtimeInfo = runCatching { FfmpegNativeBridge.nativeRuntimeInfo() }.getOrDefault("runtime-info unavailable")
        val healthHint = deriveFfmpegHealthHint(started, lastStatsSnapshot, runtimeInfo)
        val hintSegment = if (healthHint != null) " healthHint={$healthHint}" else ""
        val transportState = deriveTransportConnectionState(lastStatsSnapshot)
        return "started=$started connState=$transportState retries=$lastRetryAttempts terminalFailures=$terminalFailureCount stats={$lastStatsSnapshot} runtime={$runtimeInfo} lastErr={${lastError.ifBlank { "none" }}}$hintSegment"
    }


    private fun deriveTransportConnectionState(statsSnapshot: String): String {
        val connectSuccess = Regex("""connectSuccess=(\d+)""").find(statsSnapshot)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val connectFailures = Regex("""connectFailures=(\d+)""").find(statsSnapshot)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val consecutiveWriteFailures = Regex("""consecutiveWriteFailures=(\d+)""").find(statsSnapshot)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val connectionState = when {
            started && connectSuccess > 0L && consecutiveWriteFailures == 0L -> "connected"
            started && consecutiveWriteFailures > 0L -> "retrying"
            connectFailures > 0L || terminalFailureCount > 0 -> "faulted"
            else -> "idle"
        }
        Log.d(NET_TAG, "tsMs=${System.currentTimeMillis()} milestone=connection state state=$connectionState connectSuccess=$connectSuccess connectFailures=$connectFailures consecutiveWriteFailures=$consecutiveWriteFailures")
        return connectionState
    }

    private fun resolveRuntime(): Result<Runtime> {
        return when (val current = runtime) {
            RuntimeBinding.Uninitialized -> {
                val loaded = loadRuntime()
                runtime = loaded
                when (loaded) {
                    is RuntimeBinding.Loaded -> Result.success(loaded.runtime)
                    is RuntimeBinding.Unavailable -> Result.failure(IllegalStateException(loaded.reason))
                    RuntimeBinding.Uninitialized -> Result.failure(IllegalStateException("ffmpeg runtime unavailable"))
                }
            }

            is RuntimeBinding.Loaded -> Result.success(current.runtime)
            is RuntimeBinding.Unavailable -> Result.failure(IllegalStateException(current.reason))
        }
    }

    private fun loadRuntime(): RuntimeBinding {
        return runCatching {
            System.loadLibrary(FFMPEG_NATIVE_LIBRARY)
            RuntimeBinding.Loaded(JniRuntime)
        }.getOrElse { error ->
            val reason = error.message ?: error::class.java.simpleName
            RuntimeBinding.Unavailable("ffmpeg native bridge load failed: $reason")
        }
    }

    private interface Runtime {
        fun probeRuntime(): Boolean

        fun prepare(
            endpoint: ObsEndpointSpec,
            videoEnabled: Boolean,
            audioEnabled: Boolean,
        ): Result<Unit>
        fun start(): Result<Unit>
        fun pushVideo(accessUnit: VideoEncoderNode.EncodedAccessUnit): Result<Unit>
        fun pushAudio(accessUnit: AudioEncoderNode.EncodedAccessUnit): Result<Unit>
        fun stop()
        fun statsSnapshot(): String
    }

    private sealed interface RuntimeBinding {
        data object Uninitialized : RuntimeBinding
        data class Loaded(val runtime: Runtime) : RuntimeBinding
        data class Unavailable(val reason: String) : RuntimeBinding
    }

    private object JniRuntime : Runtime {
        override fun probeRuntime(): Boolean {
            return FfmpegNativeBridge.nativeProbeRuntime()
        }

        override fun prepare(
            endpoint: ObsEndpointSpec,
            videoEnabled: Boolean,
            audioEnabled: Boolean,
        ): Result<Unit> {
            val ok = FfmpegNativeBridge.nativePrepare(
                endpoint.host,
                endpoint.port,
                endpoint.latencyMs,
                endpoint.mode,
                videoEnabled,
                audioEnabled,
            )
            return if (ok) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(lastError("native prepare failed")))
            }
        }

        override fun start(): Result<Unit> {
            val ok = FfmpegNativeBridge.nativeStart()
            return if (ok) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(lastError("native start failed")))
            }
        }

        override fun pushVideo(accessUnit: VideoEncoderNode.EncodedAccessUnit): Result<Unit> {
            val ok = FfmpegNativeBridge.nativePushVideoAccessUnit(
                accessUnit.data,
                accessUnit.presentationTimeUs,
                accessUnit.flags,
            )
            return if (ok) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(lastError("native video push failed")))
            }
        }

        override fun pushAudio(accessUnit: AudioEncoderNode.EncodedAccessUnit): Result<Unit> {
            val ok = FfmpegNativeBridge.nativePushAudioAccessUnit(
                accessUnit.data,
                accessUnit.presentationTimeUs,
                accessUnit.flags,
            )
            return if (ok) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(lastError("native audio push failed")))
            }
        }

        override fun stop() {
            FfmpegNativeBridge.nativeStop()
        }

        override fun statsSnapshot(): String {
            return FfmpegNativeBridge.nativeStatsSnapshot()
        }

        private fun lastError(defaultMessage: String): String {
            return FfmpegNativeBridge.nativeLastError().ifBlank { defaultMessage }
        }
    }
}
