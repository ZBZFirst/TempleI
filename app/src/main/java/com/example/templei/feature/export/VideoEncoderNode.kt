package com.example.templei.feature.export

/**
 * Video path node for Screen 2 streaming orchestration.
 *
 * This node currently acts as a contract placeholder for the future MediaCodec H.264 path.
 * TODO: Replace with real MediaCodec AVC encoder and surface-based camera frame ingestion.
 */
object VideoEncoderNode {
    enum class NodeState {
        Idle,
        Configured,
        Running,
        Faulted,
    }

    data class EncoderConfig(
        val width: Int = 1280,
        val height: Int = 720,
        val fps: Int = 30,
        val bitrate: Int = 2_500_000,
    )

    private var nodeState: NodeState = NodeState.Idle
    private var lastError: String = ""

    fun configure(config: EncoderConfig): Result<Unit> {
        if (config.width <= 0 || config.height <= 0 || config.fps <= 0 || config.bitrate <= 0) {
            nodeState = NodeState.Faulted
            lastError = "video encoder config invalid"
            return Result.failure(IllegalArgumentException(lastError))
        }

        nodeState = NodeState.Configured
        lastError = ""
        return Result.success(Unit)
    }

    fun start(): Result<Unit> {
        if (nodeState != NodeState.Configured) {
            nodeState = NodeState.Faulted
            lastError = "video encoder not configured"
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
