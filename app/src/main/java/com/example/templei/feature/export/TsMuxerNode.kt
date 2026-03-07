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

    fun prepare(): Result<Unit> {
        val resolved = resolveRuntime()
        if (resolved.isFailure) {
            return Result.failure(resolved.exceptionOrNull() ?: IllegalStateException("native mux path unavailable"))
        }
        return resolved.getOrThrow().prepare()
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

    fun stop() {
        if (started) {
            resolveRuntime().onSuccess { it.stop() }
        }
        started = false
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
            RuntimeBinding.Loaded(NoOpNativeRuntime)
        }.getOrElse {
            RuntimeBinding.Unavailable("native mux runtime pending (missing $MUX_NATIVE_LIBRARY)")
        }
    }

    internal interface Runtime {
        fun prepare(): Result<Unit>
        fun start(): Result<Unit>
        fun stop()
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
        override fun prepare(): Result<Unit> = Result.success(Unit)

        override fun start(): Result<Unit> = Result.success(Unit)

        override fun stop() = Unit
    }
}
