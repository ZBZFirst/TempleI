package com.example.templei.feature.export

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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

    // Placeholder AAC-LC ADTS silent-like frame to keep transport active until microphone path lands.
    private val syntheticAacFrame = byteArrayOf(
        0xFF.toByte(), 0xF1.toByte(), 0x50.toByte(), 0x40.toByte(), 0x01.toByte(), 0x7F.toByte(), 0xFC.toByte(),
    )

    private var nodeState: NodeState = NodeState.Idle
    private var lastError: String = ""
    private var outputListener: ((EncodedAccessUnit) -> Unit)? = null
    private var scheduler: ScheduledExecutorService? = null

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
        startSyntheticEmissionLoop()
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

    private fun startSyntheticEmissionLoop() {
        scheduler?.shutdownNow()
        val executor = Executors.newSingleThreadScheduledExecutor()
        scheduler = executor

        // Keep emitting placeholder AUs so mux/SRT counters keep moving during integration.
        executor.scheduleAtFixedRate(
            {
                if (nodeState != NodeState.Running) {
                    return@scheduleAtFixedRate
                }
                val sample = EncodedAccessUnit(
                    data = syntheticAacFrame,
                    presentationTimeUs = System.nanoTime() / 1_000,
                    flags = 1,
                )
                outputListener?.invoke(sample)
            },
            0,
            20,
            TimeUnit.MILLISECONDS,
        )
    }
}
