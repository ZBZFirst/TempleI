package com.example.templei.feature.export

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

    @Volatile
    private var backendOverrideForTesting: NativeStreamBackend? = null

    fun activeBackend(): NativeStreamBackend {
        return backendOverrideForTesting ?: ffmpegBackend
    }

    fun availabilitySummary(): String {
        val active = activeBackend()
        return "active=${active.id.name} ${active.availabilityMessage()}"
    }

    fun diagnosticsSummary(): String {
        return activeBackend().diagnosticsSummary()
    }

    fun pushVideoAccessUnit(accessUnit: VideoEncoderNode.EncodedAccessUnit): Result<Unit> {
        return activeBackend().pushVideoAccessUnit(accessUnit)
    }

    fun pushAudioAccessUnit(accessUnit: AudioEncoderNode.EncodedAccessUnit): Result<Unit> {
        return activeBackend().pushAudioAccessUnit(accessUnit)
    }

    internal fun installBackendForTesting(backend: NativeStreamBackend?) {
        backendOverrideForTesting = backend
    }
}

private object FfmpegStreamBackend : NativeStreamBackend {
    private const val FFMPEG_NATIVE_LIBRARY = "templei_ffmpeg"

    private var runtime: RuntimeBinding = RuntimeBinding.Uninitialized
    private var started = false
    private var videoEnabled = false
    private var audioEnabled = false
    private var lastError: String = ""
    private var lastStatsSnapshot: String = "stats unavailable"

    override val id: NativeStreamBackend.BackendId = NativeStreamBackend.BackendId.Ffmpeg

    override fun isAvailable(): Boolean {
        return resolveRuntime().isSuccess
    }

    override fun availabilityMessage(): String {
        val runtimeResult = resolveRuntime()
        return if (runtimeResult.isSuccess) {
            "ffmpeg runtime bridge ready"
        } else {
            runtimeResult.exceptionOrNull()?.message ?: "ffmpeg runtime unavailable"
        }
    }

    override fun start(endpoint: ObsEndpointSpec, streamMode: CaptureCoordinator.StreamPathMode): Result<Unit> {
        when (streamMode) {
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

        val runtimeResult = resolveRuntime()
        if (runtimeResult.isFailure) {
            return Result.failure(runtimeResult.exceptionOrNull() ?: IllegalStateException("ffmpeg runtime unavailable"))
        }

        val runtimeInstance = runtimeResult.getOrThrow()
        val prepareResult = runtimeInstance.prepare(endpoint, videoEnabled, audioEnabled)
        if (prepareResult.isFailure) {
            lastError = prepareResult.exceptionOrNull()?.message.orEmpty()
            return prepareResult
        }

        val startResult = runtimeInstance.start()
        started = startResult.isSuccess
        if (startResult.isFailure) {
            started = false
            lastError = startResult.exceptionOrNull()?.message.orEmpty()
            return startResult
        }

        lastError = ""
        lastStatsSnapshot = runtimeInstance.statsSnapshot()
        return Result.success(Unit)
    }

    override fun pushVideoAccessUnit(accessUnit: VideoEncoderNode.EncodedAccessUnit): Result<Unit> {
        if (!started || !videoEnabled) {
            return Result.success(Unit)
        }

        val runtimeResult = resolveRuntime()
        if (runtimeResult.isFailure) {
            return Result.failure(runtimeResult.exceptionOrNull() ?: IllegalStateException("ffmpeg runtime unavailable"))
        }

        val pushResult = runtimeResult.getOrThrow().pushVideo(accessUnit)
        if (pushResult.isFailure) {
            lastError = pushResult.exceptionOrNull()?.message.orEmpty()
        }
        lastStatsSnapshot = runtimeResult.getOrThrow().statsSnapshot()
        return pushResult
    }

    override fun pushAudioAccessUnit(accessUnit: AudioEncoderNode.EncodedAccessUnit): Result<Unit> {
        if (!started || !audioEnabled) {
            return Result.success(Unit)
        }

        val runtimeResult = resolveRuntime()
        if (runtimeResult.isFailure) {
            return Result.failure(runtimeResult.exceptionOrNull() ?: IllegalStateException("ffmpeg runtime unavailable"))
        }

        val pushResult = runtimeResult.getOrThrow().pushAudio(accessUnit)
        if (pushResult.isFailure) {
            lastError = pushResult.exceptionOrNull()?.message.orEmpty()
        }
        lastStatsSnapshot = runtimeResult.getOrThrow().statsSnapshot()
        return pushResult
    }

    override fun stop(): Result<Unit> {
        val runtimeInstance = resolveRuntime().getOrNull()
        runtimeInstance?.stop()
        started = false
        videoEnabled = false
        audioEnabled = false
        lastStatsSnapshot = runtimeInstance?.statsSnapshot() ?: "stats unavailable"
        return Result.success(Unit)
    }

    override fun diagnosticsSummary(): String {
        val runtimeInfo = runCatching { FfmpegNativeBridge.nativeRuntimeInfo() }.getOrDefault("runtime-info unavailable")
        return "started=$started stats={$lastStatsSnapshot} runtime={$runtimeInfo} lastErr={${lastError.ifBlank { "none" }}}"
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
