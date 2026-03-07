package com.example.templei.feature.export

/**
 * MPEG-TS mux node contract for Screen 2 stream path.
 *
 * This node now exposes runtime probing so Screen 2 can distinguish
 * "runtime pending" from a loaded mux integration.
 */
object TsMuxerNode {
    private const val MUX_NATIVE_LIBRARY = "templei_mux"

    private var started = false
    private var runtime: RuntimeBinding = RuntimeBinding.Uninitialized
    private var packetOutputListener: ((ByteArray) -> Unit)? = null
    private var videoAccessUnitsIngested: Long = 0
    private var audioAccessUnitsIngested: Long = 0
    private var packetsDrained: Long = 0

    /**
     * Probe for runtime availability once and cache the result.
     */
    fun isAvailable(): Boolean = resolveRuntime().isSuccess

    fun availabilityMessage(): String {
        val resolved = resolveRuntime()
        return if (resolved.isSuccess) {
            "native mux runtime ready"
        } else {
            resolved.exceptionOrNull()?.message ?: "native mux path unavailable"
        }
    }

    fun setPacketOutputListener(listener: ((ByteArray) -> Unit)?) {
        packetOutputListener = listener
    }

    fun prepare(): Result<Unit> {
        val resolved = resolveRuntime()
        if (resolved.isFailure) {
            return Result.failure(resolved.exceptionOrNull() ?: IllegalStateException("native mux path unavailable"))
        }
        val result = resolved.getOrThrow().prepare()
        if (result.isSuccess) {
            videoAccessUnitsIngested = 0
            audioAccessUnitsIngested = 0
            packetsDrained = 0
        }
        return result
    }

    fun start(): Result<Unit> {
        val resolved = resolveRuntime()
        if (resolved.isFailure) {
            return Result.failure(resolved.exceptionOrNull() ?: IllegalStateException("native mux path unavailable"))
        }

        val startResult = resolved.getOrThrow().start()
        if (startResult.isSuccess) {
            started = true
        }
        return startResult
    }

    fun ingestVideo(accessUnit: VideoEncoderNode.EncodedAccessUnit): Result<Unit> {
        if (!started) {
            return Result.failure(IllegalStateException("mux not started"))
        }

        val resolved = resolveRuntime()
        if (resolved.isFailure) {
            return Result.failure(resolved.exceptionOrNull() ?: IllegalStateException("native mux path unavailable"))
        }

        val ingestResult = resolved.getOrThrow().ingestVideo(accessUnit)
        if (ingestResult.isSuccess) {
            videoAccessUnitsIngested += 1
        }
        if (ingestResult.isFailure) {
            return ingestResult
        }

        drainPacketToOutput()
        return Result.success(Unit)
    }

    fun ingestAudio(accessUnit: AudioEncoderNode.EncodedAccessUnit): Result<Unit> {
        if (!started) {
            return Result.failure(IllegalStateException("mux not started"))
        }

        val resolved = resolveRuntime()
        if (resolved.isFailure) {
            return Result.failure(resolved.exceptionOrNull() ?: IllegalStateException("native mux path unavailable"))
        }

        val ingestResult = resolved.getOrThrow().ingestAudio(accessUnit)
        if (ingestResult.isSuccess) {
            audioAccessUnitsIngested += 1
        }
        if (ingestResult.isFailure) {
            return ingestResult
        }

        drainPacketToOutput()
        return Result.success(Unit)
    }

    fun stop() {
        if (started) {
            resolveRuntime().onSuccess { it.stop() }
        }
        started = false
        packetOutputListener = null
    }

    fun isStarted(): Boolean = started

    /**
     * Test-only hook to provide an injectable runtime while native wiring is in progress.
     */
    internal fun installRuntimeForTesting(testRuntime: Runtime?) {
        runtime = when (testRuntime) {
            null -> RuntimeBinding.Uninitialized
            else -> RuntimeBinding.Loaded(testRuntime)
        }
        started = false
    }

    private fun drainPacketToOutput() {
        val runtimeInstance = resolveRuntime().getOrNull() ?: return
        while (true) {
            val packet = runtimeInstance.drainPacket()
            if (packet.isEmpty()) {
                break
            }
            packetsDrained += 1
            packetOutputListener?.invoke(packet)
        }
    }

    private fun resolveRuntime(): Result<Runtime> {
        return when (val current = runtime) {
            RuntimeBinding.Uninitialized -> {
                val loaded = loadNativeRuntime()
                runtime = loaded
                when (loaded) {
                    RuntimeBinding.Uninitialized -> Result.failure(IllegalStateException("native mux path unavailable"))
                    is RuntimeBinding.Loaded -> Result.success(loaded.runtime)
                    is RuntimeBinding.Unavailable -> Result.failure(IllegalStateException(loaded.reason))
                }
            }

            is RuntimeBinding.Loaded -> Result.success(current.runtime)
            is RuntimeBinding.Unavailable -> Result.failure(IllegalStateException(current.reason))
        }
    }

    private fun loadNativeRuntime(): RuntimeBinding {
        return runCatching {
            System.loadLibrary(MUX_NATIVE_LIBRARY)
            RuntimeBinding.Loaded(JniMuxRuntime)
        }.getOrElse {
            RuntimeBinding.Unavailable("native mux runtime pending (missing $MUX_NATIVE_LIBRARY)")
        }
    }


    data class RuntimeStats(
        val started: Boolean,
        val videoAccessUnitsIngested: Long,
        val audioAccessUnitsIngested: Long,
        val packetsDrained: Long,
    )

    fun runtimeStats(): RuntimeStats {
        return RuntimeStats(
            started = started,
            videoAccessUnitsIngested = videoAccessUnitsIngested,
            audioAccessUnitsIngested = audioAccessUnitsIngested,
            packetsDrained = packetsDrained,
        )
    }

    internal interface Runtime {
        fun prepare(): Result<Unit>
        fun start(): Result<Unit>
        fun stop()
        fun ingestVideo(accessUnit: VideoEncoderNode.EncodedAccessUnit): Result<Unit>
        fun ingestAudio(accessUnit: AudioEncoderNode.EncodedAccessUnit): Result<Unit>
        fun drainPacket(): ByteArray
    }

    private sealed interface RuntimeBinding {
        data object Uninitialized : RuntimeBinding
        data class Loaded(val runtime: Runtime) : RuntimeBinding
        data class Unavailable(val reason: String) : RuntimeBinding
    }

    /**
     * JNI-backed runtime adapter while native mux internals are integrated.
     */
    private object JniMuxRuntime : Runtime {
        override fun prepare(): Result<Unit> {
            return if (TsMuxNativeBridge.nativePrepare()) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(TsMuxNativeBridge.nativeLastError().ifBlank { "native mux prepare failed" }))
            }
        }

        override fun start(): Result<Unit> {
            return if (TsMuxNativeBridge.nativeStart()) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(TsMuxNativeBridge.nativeLastError().ifBlank { "native mux start failed" }))
            }
        }

        override fun stop() {
            TsMuxNativeBridge.nativeStop()
        }

        override fun ingestVideo(accessUnit: VideoEncoderNode.EncodedAccessUnit): Result<Unit> {
            return if (TsMuxNativeBridge.nativeIngestVideo(
                    accessUnit.data,
                    accessUnit.presentationTimeUs,
                    accessUnit.flags,
                    accessUnit.trackIndex,
                )
            ) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(TsMuxNativeBridge.nativeLastError().ifBlank { "native mux video ingest failed" }))
            }
        }

        override fun ingestAudio(accessUnit: AudioEncoderNode.EncodedAccessUnit): Result<Unit> {
            return if (TsMuxNativeBridge.nativeIngestAudio(
                    accessUnit.data,
                    accessUnit.presentationTimeUs,
                    accessUnit.flags,
                    accessUnit.trackIndex,
                )
            ) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(TsMuxNativeBridge.nativeLastError().ifBlank { "native mux audio ingest failed" }))
            }
        }

        override fun drainPacket(): ByteArray {
            return TsMuxNativeBridge.nativeDrainPacket()
        }
    }
}
