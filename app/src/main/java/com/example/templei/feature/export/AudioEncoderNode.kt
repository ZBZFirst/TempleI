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

    data class EncodedAccessUnit(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int,
        val trackIndex: Int = 1,
    )

    private var nodeState: NodeState = NodeState.Idle
    private var lastError: String = ""
    private var outputListener: ((EncodedAccessUnit) -> Unit)? = null

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

    fun setOutputListener(listener: ((EncodedAccessUnit) -> Unit)?) {
        outputListener = listener
    }

    fun start(): Result<Unit> {
        if (nodeState != NodeState.Configured) {
            nodeState = NodeState.Faulted
            lastError = "audio encoder not configured"
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
            data = byteArrayOf(0x12, 0x10),
            presentationTimeUs = System.nanoTime() / 1_000,
            flags = 1,
        )
        outputListener?.invoke(sample)
    }
}
