package com.example.templei.feature.export

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import kotlin.concurrent.thread

/**
 * Audio path node for Screen 2 streaming orchestration.
 *
 * This node encodes microphone PCM to AAC-LC access units.
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

    private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC

    private var nodeState: NodeState = NodeState.Idle
    private var lastError: String = ""
    private var outputListener: ((EncodedAccessUnit) -> Unit)? = null
    private var activeConfig: EncoderConfig = EncoderConfig()
    private var codec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile
    private var captureLoopActive = false

    fun configure(config: EncoderConfig): Result<Unit> {
        if (config.sampleRate <= 0 || config.channelCount <= 0 || config.bitrate <= 0) {
            nodeState = NodeState.Faulted
            lastError = "audio encoder config invalid"
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
            lastError = "audio encoder not configured"
            return Result.failure(IllegalStateException(lastError))
        }

        val channelMask = if (activeConfig.channelCount == 1) {
            AudioFormat.CHANNEL_IN_MONO
        } else {
            AudioFormat.CHANNEL_IN_STEREO
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            activeConfig.sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            nodeState = NodeState.Faulted
            lastError = "audio record min buffer invalid"
            return Result.failure(IllegalStateException(lastError))
        }

        return runCatching {
            val format = MediaFormat.createAudioFormat(MIME_TYPE, activeConfig.sampleRate, activeConfig.channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, activeConfig.bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuffer)
            }

            codec = MediaCodec.createEncoderByType(MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                activeConfig.sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2,
            ).also { recorder ->
                recorder.startRecording()
            }

            captureLoopActive = true
            captureThread = thread(name = "TempleI-AacCapture") {
                runAudioCaptureLoop(minBuffer)
            }
            nodeState = NodeState.Running
        }.onFailure {
            nodeState = NodeState.Faulted
            lastError = "audio encoder start failed: ${it.message.orEmpty()}"
            stop()
        }.map {}
    }

    fun stop() {
        captureLoopActive = false
        captureThread?.join(300)
        captureThread = null

        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null

        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null

        nodeState = NodeState.Idle
        lastError = ""
    }

    fun state(): NodeState = nodeState

    fun error(): String = lastError

    private fun runAudioCaptureLoop(bufferSize: Int) {
        val activeCodec = codec ?: return
        val recorder = audioRecord ?: return
        val pcmBuffer = ByteArray(bufferSize)

        while (captureLoopActive && nodeState == NodeState.Running) {
            val read = recorder.read(pcmBuffer, 0, pcmBuffer.size)
            if (read <= 0) {
                continue
            }

            val inputIndex = activeCodec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuffer = activeCodec.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(pcmBuffer, 0, read)
                    val ptsUs = System.nanoTime() / 1_000L
                    activeCodec.queueInputBuffer(inputIndex, 0, read, ptsUs, 0)
                }
            }

            drainOutput(activeCodec)
        }
    }

    private fun drainOutput(activeCodec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            when (val outputIndex = activeCodec.dequeueOutputBuffer(bufferInfo, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> {
                    if (outputIndex >= 0) {
                        val outputBuffer = activeCodec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val payload = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.get(payload)

                            outputListener?.invoke(
                                EncodedAccessUnit(
                                    data = payload,
                                    presentationTimeUs = bufferInfo.presentationTimeUs,
                                    flags = bufferInfo.flags,
                                ),
                            )
                        }
                        activeCodec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }
    }
}
