package com.example.templei.feature.export

/**
 * MPEG-TS mux contract placeholder for Screen 2 stream path.
 *
 * TODO: Replace with native TS mux implementation and encoded packet ingestion.
 */
object TsMuxerNode {
    private var started = false

    fun isAvailable(): Boolean = false

    fun prepare(): Result<Unit> {
        if (!isAvailable()) {
            return Result.failure(IllegalStateException("native mux path unavailable"))
        }
        return Result.success(Unit)
    }

    fun start(): Result<Unit> {
        if (!isAvailable()) {
            return Result.failure(IllegalStateException("native mux path unavailable"))
        }
        started = true
        return Result.success(Unit)
    }

    fun stop() {
        started = false
    }

    fun isStarted(): Boolean = started
}
