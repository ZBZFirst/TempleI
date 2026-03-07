package com.example.templei.feature.export

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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

    // Placeholder H.264 annex-b samples to keep transport active while real camera encoder lands.
    private val syntheticSpsPpsIdr = byteArrayOf(
        0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1f, 0x96.toByte(), 0x54, 0x05, 0x01, 0xed.toByte(), 0x00, 0xf0.toByte(), 0x88.toByte(), 0x45,
        0x00, 0x00, 0x00, 0x01, 0x68, 0xce.toByte(), 0x38, 0x80.toByte(),
        0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte(), 0x84.toByte(), 0x00, 0x0a, 0xf2.toByte(), 0x62, 0x80.toByte(),
    )

    private var nodeState: NodeState = NodeState.Idle
    private var lastError: String = ""
    private var outputListener: ((EncodedAccessUnit) -> Unit)? = null
    private var activeConfig: EncoderConfig = EncoderConfig()
    private var scheduler: ScheduledExecutorService? = null

    fun configure(config: EncoderConfig): Result<Unit> {
        if (config.width <= 0 || config.height <= 0 || config.fps <= 0 || config.bitrate <= 0) {
            nodeState = NodeState.Faulted
            lastError = "video encoder config invalid"
            return Result.failure(IllegalArgumentException(lastError))
        }

        activeConfig = config
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

        // Intentionally keep video placeholder silent until real MediaCodec output is wired.
        // Sending synthetic/invalid H.264 data causes continuous OBS decoder error flooding.
        nodeState = NodeState.Running
        return Result.success(Unit)
    }

    fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
        nodeState = NodeState.Idle
        lastError = ""
    }

    fun state(): NodeState = nodeState

    fun error(): String = lastError
}
