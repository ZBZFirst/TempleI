package com.example.templei.feature.export

import android.content.Context
import android.content.SharedPreferences

/**
 * Screen 2 export/stream state holder for OBS-over-LAN ingest setup.
 *
 * This module intentionally separates UI wiring from transport internals.
 * TODO: Replace contract nodes with native MPEG-TS mux + SRT sender integration.
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

    private val transportGateway: StreamTransportGateway = DefaultTransportGateway

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
        return config.toEndpointSpec().toSrtUrl()
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
        val validation = validateConfig(config)
        lastConnectionTest = when {
            !validation.isValid -> "transport not ready: ${validation.message}"
            !transportGateway.isAvailable() -> "native mux path unavailable; sender unavailable"
            else -> "endpoint configuration valid"
        }
        return lastConnectionTest
    }

    fun startStream(config: ObsStreamConfig): StreamResult {
        val validation = validateConfig(config)
        if (!validation.isValid) {
            return StreamResult(state = SessionState.Faulted, error = validation.message)
        }

        if (!transportGateway.isAvailable()) {
            sessionState = SessionState.Faulted
            lastError = "native mux path unavailable; sender unavailable"
            return StreamResult(state = sessionState, error = lastError)
        }

        sessionState = SessionState.Starting
        val started = transportGateway.startStream(config.toEndpointSpec())
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


    fun markFault(message: String): StreamResult {
        sessionState = SessionState.Faulted
        lastError = message
        return StreamResult(state = sessionState, error = lastError)
    }

    fun currentState(): SessionState = sessionState

    fun lastError(): String = lastError

    fun lastValidation(): String = lastValidation

    fun lastConnectionTest(): String = lastConnectionTest


    fun interoperabilityStatus(config: ObsStreamConfig): String {
        val host = config.host.trim()
        if (host.isEmpty()) {
            return "set OBS host and port, then copy Input into OBS Media Source"
        }

        if (config.port !in 1..65535) {
            return "set a valid port (1-65535) for OBS listener"
        }

        if (!TsMuxerNode.isAvailable() || !SrtTransportNode.isAvailable()) {
            return "UI/config ready; waiting for native mpegts+srt runtime"
        }

        return "ready for OBS listener ingest"
    }

    fun nextProfile(current: String): String {
        return if (current == PROFILE_BALANCED) PROFILE_LOW_LATENCY else PROFILE_BALANCED
    }

    interface StreamTransportGateway {
        fun isAvailable(): Boolean
        fun startStream(endpoint: ObsEndpointSpec): Result<Unit>
        fun stopStream(): Result<Unit>
    }

    private object DefaultTransportGateway : StreamTransportGateway {
        override fun isAvailable(): Boolean {
            return TsMuxerNode.isAvailable() && SrtTransportNode.isAvailable()
        }

        override fun startStream(endpoint: ObsEndpointSpec): Result<Unit> {
            val muxPrepared = TsMuxerNode.prepare()
            if (muxPrepared.isFailure) {
                return Result.failure(IllegalStateException("native mux path unavailable"))
            }

            val connected = SrtTransportNode.connect(endpoint)
            if (connected.isFailure) {
                return Result.failure(IllegalStateException("sender unavailable"))
            }

            val muxStarted = TsMuxerNode.start()
            if (muxStarted.isFailure) {
                return Result.failure(IllegalStateException("native mux path unavailable"))
            }

            val sendingStarted = SrtTransportNode.startSending()
            if (sendingStarted.isFailure) {
                return Result.failure(IllegalStateException("sender unavailable"))
            }

            return Result.success(Unit)
        }

        override fun stopStream(): Result<Unit> {
            SrtTransportNode.stopSending()
            TsMuxerNode.stop()
            return Result.success(Unit)
        }
    }

    private fun Context.preferences(): SharedPreferences {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
