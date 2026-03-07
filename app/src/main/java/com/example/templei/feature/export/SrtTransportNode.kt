package com.example.templei.feature.export

/**
 * SRT sender contract placeholder for Screen 2 stream path.
 *
 * TODO: Replace with native SRT sender implementation.
 */
object SrtTransportNode {
    private var connected = false

    fun isAvailable(): Boolean = false

    fun connect(endpoint: ObsEndpointSpec): Result<Unit> {
        if (!isAvailable()) {
            return Result.failure(IllegalStateException("sender unavailable"))
        }
        if (endpoint.host.isBlank()) {
            return Result.failure(IllegalArgumentException("host missing"))
        }
        connected = true
        return Result.success(Unit)
    }

    fun startSending(): Result<Unit> {
        if (!connected) {
            return Result.failure(IllegalStateException("transport not connected"))
        }
        return Result.success(Unit)
    }

    fun stopSending() {
        connected = false
    }

    fun isConnected(): Boolean = connected
}
