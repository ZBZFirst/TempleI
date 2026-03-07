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

        val connectResult = resolved.getOrThrow().connect(endpoint)
        connected = connectResult.isSuccess
        if (!connected) {
            sending = false
        }
        return connectResult
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
        return startResult
    }

    fun stopSending() {
        if (sending) {
            resolveRuntime().onSuccess { it.stopSending() }
        }
        connected = false
        sending = false
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
            RuntimeBinding.Loaded(NoOpNativeRuntime)
        }.getOrElse {
            RuntimeBinding.Unavailable("native srt runtime pending (missing $SRT_NATIVE_LIBRARY)")
        }
    }

    internal interface Runtime {
        fun connect(endpoint: ObsEndpointSpec): Result<Unit>
        fun startSending(): Result<Unit>
        fun stopSending()
    }

    private sealed interface RuntimeBinding {
        data object Uninitialized : RuntimeBinding
        data class Loaded(val runtime: Runtime) : RuntimeBinding
        data class Unavailable(val reason: String) : RuntimeBinding
    }

    /**
     * Placeholder binding while JNI entry points are introduced.
     */
    private object NoOpNativeRuntime : Runtime {
        override fun connect(endpoint: ObsEndpointSpec): Result<Unit> = Result.success(Unit)

        override fun startSending(): Result<Unit> = Result.success(Unit)

        override fun stopSending() = Unit
    }
}
