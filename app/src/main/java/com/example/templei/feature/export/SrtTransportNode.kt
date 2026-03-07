package com.example.templei.feature.export

/**
 * SRT sender node contract for Screen 2 stream path.
 *
 * This node now exposes runtime probing so Screen 2 can distinguish
 * "runtime pending" from a loaded sender integration.
 */
object SrtTransportNode {
    private const val SRT_NATIVE_LIBRARY = "templei_srt"

    private var connected = false
    private var sending = false
    private var runtime: RuntimeBinding = RuntimeBinding.Uninitialized
    private var packetsSent: Long = 0
    private var reconnectAttempts: Int = 0
    private var lastEndpoint: ObsEndpointSpec? = null

    /**
     * Probe for runtime availability once and cache the result.
     */
    fun isAvailable(): Boolean = resolveRuntime().isSuccess

    fun availabilityMessage(): String {
        val resolved = resolveRuntime()
        return if (resolved.isSuccess) {
            "native srt runtime ready"
        } else {
            resolved.exceptionOrNull()?.message ?: "sender unavailable"
        }
    }

    fun connect(endpoint: ObsEndpointSpec): Result<Unit> {
        val resolved = resolveRuntime()
        if (resolved.isFailure) {
            return Result.failure(resolved.exceptionOrNull() ?: IllegalStateException("sender unavailable"))
        }
        if (endpoint.host.isBlank()) {
            return Result.failure(IllegalArgumentException("host missing"))
        }

        val retryBudgetMs = listOf(0L, 150L, 400L)
        val runtimeInstance = resolved.getOrThrow()
        var lastError: Throwable = IllegalStateException("sender unavailable")

        for (delayMs in retryBudgetMs) {
            if (delayMs > 0) {
                Thread.sleep(delayMs)
            }

            val connectResult = runtimeInstance.connect(endpoint)
            if (connectResult.isSuccess) {
                connected = true
                sending = false
                reconnectAttempts = 0
                lastEndpoint = endpoint
                return Result.success(Unit)
            }
            reconnectAttempts += 1
            lastError = connectResult.exceptionOrNull() ?: lastError
        }

        connected = false
        sending = false
        lastEndpoint = null
        return Result.failure(lastError)
    }

    fun startSending(): Result<Unit> {
        if (!connected) {
            return Result.failure(IllegalStateException("transport not connected"))
        }

        val resolved = resolveRuntime()
        if (resolved.isFailure) {
            return Result.failure(resolved.exceptionOrNull() ?: IllegalStateException("sender unavailable"))
        }

        val startResult = resolved.getOrThrow().startSending()
        sending = startResult.isSuccess
        if (sending) {
            packetsSent = 0
        }
        return startResult
    }

    fun sendPacket(packet: ByteArray): Result<Unit> {
        if (!sending) {
            return Result.failure(IllegalStateException("transport not sending"))
        }

        if (packet.isEmpty()) {
            return Result.success(Unit)
        }

        val resolved = resolveRuntime()
        if (resolved.isFailure) {
            return Result.failure(resolved.exceptionOrNull() ?: IllegalStateException("sender unavailable"))
        }

        val sendResult = resolved.getOrThrow().sendPacket(packet)
        if (sendResult.isSuccess) {
            packetsSent += 1
        }
        return sendResult
    }

    fun stopSending() {
        if (sending) {
            resolveRuntime().onSuccess { it.stopSending() }
        }
        connected = false
        sending = false
        lastEndpoint = null
    }

    fun isConnected(): Boolean = connected

    /**
     * Test-only hook to provide an injectable runtime while native wiring is in progress.
     */
    internal fun installRuntimeForTesting(testRuntime: Runtime?) {
        runtime = when (testRuntime) {
            null -> RuntimeBinding.Uninitialized
            else -> RuntimeBinding.Loaded(testRuntime)
        }
        connected = false
        sending = false
        lastEndpoint = null
    }

    private fun resolveRuntime(): Result<Runtime> {
        return when (val current = runtime) {
            RuntimeBinding.Uninitialized -> {
                val loaded = loadNativeRuntime()
                runtime = loaded
                when (loaded) {
                    RuntimeBinding.Uninitialized -> Result.failure(IllegalStateException("sender unavailable"))
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
            System.loadLibrary(SRT_NATIVE_LIBRARY)
            RuntimeBinding.Loaded(JniSrtRuntime)
        }.getOrElse {
            RuntimeBinding.Unavailable("native srt runtime pending (missing $SRT_NATIVE_LIBRARY)")
        }
    }


    data class RuntimeStats(
        val connected: Boolean,
        val sending: Boolean,
        val packetsSent: Long,
        val reconnectAttempts: Int,
        val endpoint: ObsEndpointSpec?,
    )

    fun runtimeStats(): RuntimeStats {
        return RuntimeStats(
            connected = connected,
            sending = sending,
            packetsSent = packetsSent,
            reconnectAttempts = reconnectAttempts,
            endpoint = lastEndpoint,
        )
    }

    internal interface Runtime {
        fun connect(endpoint: ObsEndpointSpec): Result<Unit>
        fun startSending(): Result<Unit>
        fun sendPacket(packet: ByteArray): Result<Unit>
        fun stopSending()
    }

    private sealed interface RuntimeBinding {
        data object Uninitialized : RuntimeBinding
        data class Loaded(val runtime: Runtime) : RuntimeBinding
        data class Unavailable(val reason: String) : RuntimeBinding
    }

    /**
     * JNI-backed runtime adapter while native sender internals are integrated.
     */
    private object JniSrtRuntime : Runtime {
        override fun connect(endpoint: ObsEndpointSpec): Result<Unit> {
            return if (SrtNativeBridge.nativeConnect(endpoint.host, endpoint.port, endpoint.latencyMs, endpoint.mode)) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(SrtNativeBridge.nativeLastError().ifBlank { "native srt connect failed" }))
            }
        }

        override fun startSending(): Result<Unit> {
            return if (SrtNativeBridge.nativeStartSending()) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(SrtNativeBridge.nativeLastError().ifBlank { "native srt start failed" }))
            }
        }

        override fun sendPacket(packet: ByteArray): Result<Unit> {
            return if (SrtNativeBridge.nativeSendPacket(packet)) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(SrtNativeBridge.nativeLastError().ifBlank { "native srt send failed" }))
            }
        }

        override fun stopSending() {
            SrtNativeBridge.nativeStopSending()
        }
    }
}
