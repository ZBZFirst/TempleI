package com.example.templei.feature.export

/**
 * SRT sender node contract for Screen 2 stream path.
 *
 * This node now exposes runtime probing so Screen 2 can distinguish
 * "runtime pending" from a loaded sender integration.
 */
object SrtTransportNode {
    private const val SRT_SHARED_LIBRARY = "srt"
    private const val SRT_NATIVE_LIBRARY = "templei_srt"

    private var connected = false
    private var sending = false
    private var runtime: RuntimeBinding = RuntimeBinding.Uninitialized
    private var packetsSent: Long = 0
    private var bytesSent: Long = 0
    private var bytesHandedToSrt: Long = 0
    private var reconnectAttempts: Int = 0
    private var lastEndpoint: ObsEndpointSpec? = null
    private var socketState: String = "UNINITIALIZED"
    private var nativeStatsSnapshot: String = ""
    private var lastSendResult: String = "not attempted"

    /**
     * Probe for runtime availability once and cache the result.
     */
    fun isAvailable(): Boolean = resolveRuntime().isSuccess

    fun availabilityMessage(): String {
        val resolved = resolveRuntime()
        return if (resolved.isSuccess) {
            "native srt runtime ready"
        } else {
            val reason = resolved.exceptionOrNull()?.message ?: "sender unavailable"
            val info = runCatching { SrtNativeBridge.nativeRuntimeInfo() }.getOrDefault("")
            if (info.isBlank()) reason else "$reason ($info)"
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
                packetsSent = 0
                bytesSent = 0
                bytesHandedToSrt = 0
                lastSendResult = "not attempted"
                refreshNativeSnapshot()
                return Result.success(Unit)
            }
            reconnectAttempts += 1
            lastError = connectResult.exceptionOrNull() ?: lastError
            refreshNativeSnapshot()
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
            bytesSent = 0
            bytesHandedToSrt = 0
            lastSendResult = "not attempted"
        }
        refreshNativeSnapshot()
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
        bytesHandedToSrt += packet.size
        if (sendResult.isSuccess) {
            packetsSent += 1
            bytesSent += packet.size
            lastSendResult = "ok:${packet.size}"
        } else {
            val reason = sendResult.exceptionOrNull()?.message ?: "send failed"
            lastSendResult = "failed:$reason"
        }
        refreshNativeSnapshot()
        return sendResult
    }

    fun stopSending() {
        if (sending || connected) {
            resolveRuntime().onSuccess { it.stopSending() }
        }
        connected = false
        sending = false
        lastEndpoint = null
        lastSendResult = "stopped"
        refreshNativeSnapshot()
    }

    fun resetRuntimeState() {
        stopSending()
        runtime = RuntimeBinding.Uninitialized
        packetsSent = 0
        bytesSent = 0
        bytesHandedToSrt = 0
        reconnectAttempts = 0
        lastSendResult = "reset"
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
        packetsSent = 0
        bytesSent = 0
        bytesHandedToSrt = 0
        socketState = "UNINITIALIZED"
        nativeStatsSnapshot = ""
        lastSendResult = "not attempted"
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

    private fun refreshNativeSnapshot() {
        socketState = runCatching { SrtNativeBridge.nativeSocketState() }.getOrDefault(socketState)
        nativeStatsSnapshot = runCatching { SrtNativeBridge.nativeStatsSnapshot() }.getOrDefault(nativeStatsSnapshot)
    }

    private fun loadNativeRuntime(): RuntimeBinding {
        return runCatching {
            // Attempt to preload libsrt from app jniLibs so JNI side symbol lookup can succeed.
            runCatching { System.loadLibrary(SRT_SHARED_LIBRARY) }
            System.loadLibrary(SRT_NATIVE_LIBRARY)
            RuntimeBinding.Loaded(JniSrtRuntime)
        }.getOrElse {
            RuntimeBinding.Unavailable(
                "native srt runtime pending: package libsrt.so in app/src/main/jniLibs/arm64-v8a/ " +
                    "(loader=$SRT_NATIVE_LIBRARY, cause=${it.message.orEmpty()})"
            )
        }
    }


    data class RuntimeStats(
        val connected: Boolean,
        val sending: Boolean,
        val packetsSent: Long,
        val bytesSent: Long,
        val bytesHandedToSrt: Long,
        val reconnectAttempts: Int,
        val endpoint: ObsEndpointSpec?,
        val socketState: String,
        val nativeStatsSnapshot: String,
        val lastSendResult: String,
    )

    fun runtimeStats(): RuntimeStats {
        refreshNativeSnapshot()
        return RuntimeStats(
            connected = connected,
            sending = sending,
            packetsSent = packetsSent,
            bytesSent = bytesSent,
            bytesHandedToSrt = bytesHandedToSrt,
            reconnectAttempts = reconnectAttempts,
            endpoint = lastEndpoint,
            socketState = socketState,
            nativeStatsSnapshot = nativeStatsSnapshot,
            lastSendResult = lastSendResult,
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
            return if (SrtNativeBridge.nativeConnect(endpoint.host, endpoint.port, endpoint.latencyMs, endpoint.mode, endpoint.timeoutUs)) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(buildNativeFailure("native srt connect failed")))
            }
        }

        override fun startSending(): Result<Unit> {
            return if (SrtNativeBridge.nativeStartSending()) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(buildNativeFailure("native srt start failed")))
            }
        }

        override fun sendPacket(packet: ByteArray): Result<Unit> {
            return if (SrtNativeBridge.nativeSendPacket(packet)) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(buildNativeFailure("native srt send failed")))
            }
        }

        override fun stopSending() {
            SrtNativeBridge.nativeStopSending()
        }

        private fun buildNativeFailure(defaultMessage: String): String {
            val message = SrtNativeBridge.nativeLastError().ifBlank { defaultMessage }
            val runtimeInfo = SrtNativeBridge.nativeRuntimeInfo()
            return if (runtimeInfo.isBlank()) message else "$message ($runtimeInfo)"
        }
    }
}
