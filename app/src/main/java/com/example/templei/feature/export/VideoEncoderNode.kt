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

    data class EncodedAccessUnit(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int,
        val trackIndex: Int = 0,
    )

    private var nodeState: NodeState = NodeState.Idle
    private var lastError: String = ""
    private var outputListener: ((EncodedAccessUnit) -> Unit)? = null

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

    fun setOutputListener(listener: ((EncodedAccessUnit) -> Unit)?) {
        outputListener = listener
    }

    fun start(): Result<Unit> {
        if (nodeState != NodeState.Configured) {
            nodeState = NodeState.Faulted
            lastError = "video encoder not configured"
            return Result.failure(IllegalStateException(lastError))
        }

        nodeState = NodeState.Running
        emitCodecBootstrapSample()
        return Result.success(Unit)
    }

    fun stop() {
        nodeState = NodeState.Idle
        lastError = ""
    }

    fun state(): NodeState = nodeState

    fun error(): String = lastError

    private fun emitCodecBootstrapSample() {
        val sample = EncodedAccessUnit(
            data = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67.toByte(), 0x42.toByte(), 0x00, 0x1f),
            presentationTimeUs = System.nanoTime() / 1_000,
            flags = 1,
        )
        outputListener?.invoke(sample)
    }
}
