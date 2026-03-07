package com.example.templei.feature.export

/**
 * Audio path node for Screen 2 streaming orchestration.
 *
 * This node currently acts as a contract placeholder for the future AAC-LC encoder path.
 * TODO: Replace with real microphone capture + AAC-LC encode path with stable timestamp output.
 */
object AudioEncoderNode {
    enum class NodeState {
        Idle,
        Configured,
        Running,
        Faulted,
    }

    data class EncoderConfig(
        val sampleRate: Int = 48_000,
        val channelCount: Int = 1,
        val bitrate: Int = 96_000,
    )

    private var nodeState: NodeState = NodeState.Idle
    private var lastError: String = ""

    fun configure(config: EncoderConfig): Result<Unit> {
        if (config.sampleRate <= 0 || config.channelCount <= 0 || config.bitrate <= 0) {
            nodeState = NodeState.Faulted
            lastError = "audio encoder config invalid"
            return Result.failure(IllegalArgumentException(lastError))
        }

        nodeState = NodeState.Configured
        lastError = ""
        return Result.success(Unit)
    }

    fun start(): Result<Unit> {
        if (nodeState != NodeState.Configured) {
            nodeState = NodeState.Faulted
            lastError = "audio encoder not configured"
            return Result.failure(IllegalStateException(lastError))
        }

        nodeState = NodeState.Running
        return Result.success(Unit)
    }

    fun stop() {
        nodeState = NodeState.Idle
        lastError = ""
    }

    fun state(): NodeState = nodeState

    fun error(): String = lastError
}
