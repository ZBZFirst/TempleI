package com.example.templei.feature.export

import android.content.Context
import android.content.SharedPreferences

/**
 * Screen 2 export/stream state holder for OBS-over-LAN ingest setup.
 *
 * This module intentionally separates UI wiring from transport internals.
 * TODO: Replace the stub transport with native MPEG-TS mux + SRT sender integration.
 */
object ExportFeature {
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

    private const val DEFAULT_PORT = 9000
    private const val PROFILE_BALANCED = "Balanced"
    private const val PROFILE_LOW_LATENCY = "Low Latency"

    private var sessionState: SessionState = SessionState.Idle
    private var lastError: String = ""
    private var lastValidation: String = "Not validated"
    private var lastConnectionTest: String = "Not tested"

    private val transportGateway: StreamTransportGateway = StubTransportGateway

    fun loadConfig(context: Context): ObsStreamConfig {
        val prefs = context.preferences()
        return ObsStreamConfig(
            host = prefs.getString(KEY_HOST, "").orEmpty(),
            port = prefs.getInt(KEY_PORT, DEFAULT_PORT),
            profile = prefs.getString(KEY_PROFILE, PROFILE_BALANCED).orEmpty(),
        )
    }

    fun saveConfig(context: Context, config: ObsStreamConfig) {
        context.preferences().edit()
            .putString(KEY_HOST, config.host.trim())
            .putInt(KEY_PORT, config.port)
            .putString(KEY_PROFILE, config.profile)
            .apply()
    }

    fun resetConfig(context: Context): ObsStreamConfig {
        val reset = ObsStreamConfig()
        saveConfig(context, reset)
        sessionState = SessionState.Idle
        lastError = ""
        lastValidation = "Reset to default OBS preset"
        lastConnectionTest = "Not tested"
        return reset
    }

    fun buildObsUrl(config: ObsStreamConfig): String {
        val host = config.host.trim()
        return "srt://$host:${config.port}?mode=listener"
    }

    fun validateConfig(config: ObsStreamConfig): ValidationResult {
        val host = config.host.trim()
        val hostMissing = host.isEmpty()
        val invalidPort = config.port !in 1..65535

        val result = when {
            hostMissing -> ValidationResult(false, "host missing")
            invalidPort -> ValidationResult(false, "port invalid")
            !transportGateway.isAvailable() -> ValidationResult(false, "native mux path unavailable; sender unavailable")
            else -> ValidationResult(true, "ready")
        }

        sessionState = if (result.isValid) SessionState.Ready else SessionState.Faulted
        lastValidation = result.message
        if (!result.isValid) {
            lastError = result.message
        }
        return result
    }

    fun testEndpoint(config: ObsStreamConfig): String {
        val validation = validateConfig(config)
        lastConnectionTest = if (validation.isValid) {
            "endpoint configuration valid"
        } else {
            "transport not ready: ${validation.message}"
        }
        return lastConnectionTest
    }

    fun startStream(config: ObsStreamConfig): StreamResult {
        val validation = validateConfig(config)
        if (!validation.isValid) {
            return StreamResult(state = SessionState.Faulted, error = validation.message)
        }

        sessionState = SessionState.Starting
        val started = transportGateway.startStream(config)
        return if (started.isSuccess) {
            sessionState = SessionState.Streaming
            lastError = ""
            StreamResult(state = sessionState)
        } else {
            sessionState = SessionState.Faulted
            lastError = started.exceptionOrNull()?.message.orEmpty()
            StreamResult(state = sessionState, error = lastError)
        }
    }

    fun stopStream(): StreamResult {
        sessionState = SessionState.Stopping
        val stopped = transportGateway.stopStream()
        return if (stopped.isSuccess) {
            sessionState = SessionState.Idle
            StreamResult(state = sessionState)
        } else {
            sessionState = SessionState.Faulted
            lastError = stopped.exceptionOrNull()?.message.orEmpty()
            StreamResult(state = sessionState, error = lastError)
        }
    }

    fun currentState(): SessionState = sessionState

    fun lastError(): String = lastError

    fun lastValidation(): String = lastValidation

    fun lastConnectionTest(): String = lastConnectionTest

    fun nextProfile(current: String): String {
        return if (current == PROFILE_BALANCED) PROFILE_LOW_LATENCY else PROFILE_BALANCED
    }

    interface StreamTransportGateway {
        fun isAvailable(): Boolean
        fun startStream(config: ObsStreamConfig): Result<Unit>
        fun stopStream(): Result<Unit>
    }

    private object StubTransportGateway : StreamTransportGateway {
        override fun isAvailable(): Boolean = false

        override fun startStream(config: ObsStreamConfig): Result<Unit> {
            return Result.failure(
                IllegalStateException("MPEG-TS mux + SRT sender unavailable in this build"),
            )
        }

        override fun stopStream(): Result<Unit> = Result.success(Unit)
    }

    private fun Context.preferences(): SharedPreferences {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
