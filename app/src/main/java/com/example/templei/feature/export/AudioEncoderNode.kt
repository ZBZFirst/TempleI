package com.example.templei.feature.export

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import kotlin.concurrent.thread

/**
 * Audio path node for Screen 2 streaming orchestration.
 *
 * This node encodes microphone PCM to AAC-LC access units.
 */
object AudioEncoderNode {
    private const val TAG = "TempleI-AudioEnc"
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
    private var audioConfig = AudioConfig()
    private var captureThread: Thread? = null
    @Volatile
    private var captureLoopActive = false
    private var framesEncoded: Long = 0
    private var firstOutputLogs = 0
    private var firstAdtsLogs = 0

    private data class AudioConfig(
        val profile: Int = MediaCodecInfo.CodecProfileLevel.AACObjectLC,
        val sampleRateIndex: Int = 3,
        val channelConfig: Int = 1,
    )

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
        audioConfig = AudioConfig()
        framesEncoded = 0
        firstOutputLogs = 0
        firstAdtsLogs = 0

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
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    audioConfig = extractAudioConfig(activeCodec.outputFormat)
                }

                else -> {
                    if (outputIndex >= 0) {
                        val outputBuffer = activeCodec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val payload = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.get(payload)

                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                val adtsPayload = addAdtsHeader(payload, audioConfig)
                                if (firstOutputLogs < 5) {
                                    Log.i(
                                        TAG,
                                        "aac-buffer[${firstOutputLogs + 1}] size=${bufferInfo.size} flags=${bufferInfo.flags} " +
                                            "codecConfig=false first16=${toHex(payload, 16)}",
                                    )
                                    firstOutputLogs += 1
                                }
                                outputListener?.invoke(
                                    EncodedAccessUnit(
                                        data = adtsPayload,
                                        presentationTimeUs = bufferInfo.presentationTimeUs,
                                        flags = bufferInfo.flags,
                                    ),
                                )
                                framesEncoded += 1
                            } else if (firstOutputLogs < 5) {
                                Log.i(
                                    TAG,
                                    "aac-buffer[${firstOutputLogs + 1}] size=${bufferInfo.size} flags=${bufferInfo.flags} " +
                                        "codecConfig=true first16=${toHex(payload, 16)}",
                                )
                                firstOutputLogs += 1
                            }
                        }
                        activeCodec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }
    }

    private fun extractAudioConfig(format: MediaFormat): AudioConfig {
        val csd0 = format.getByteBuffer("csd-0") ?: return audioConfig
        val dup = csd0.duplicate()
        if (dup.remaining() < 2) {
            return audioConfig
        }

        val first = dup.get().toInt() and 0xFF
        val second = dup.get().toInt() and 0xFF
        val objectType = (first shr 3) and 0x1F
        val sampleRateIndex = ((first and 0x07) shl 1) or ((second shr 7) and 0x01)
        val channelConfig = (second shr 3) and 0x0F
        return AudioConfig(
            profile = if (objectType <= 0) MediaCodecInfo.CodecProfileLevel.AACObjectLC else objectType,
            sampleRateIndex = sampleRateIndex,
            channelConfig = channelConfig,
        ).also {
            Log.i(
                TAG,
                "aac-csd profile=${it.profile} sampleRateIndex=${it.sampleRateIndex} channelConfig=${it.channelConfig}",
            )
        }
    }

    private fun addAdtsHeader(payload: ByteArray, config: AudioConfig): ByteArray {
        val frameLength = payload.size + 7
        val profile = (config.profile - 1).coerceAtLeast(0)
        val freqIdx = config.sampleRateIndex.coerceIn(0, 12)
        val chanCfg = config.channelConfig.coerceIn(1, 7)

        val header = ByteArray(7)
        header[0] = 0xFF.toByte()
        header[1] = 0xF1.toByte()
        header[2] = (((profile and 0x03) shl 6) or ((freqIdx and 0x0F) shl 2) or ((chanCfg shr 2) and 0x01)).toByte()
        header[3] = ((((chanCfg and 0x03) shl 6) or ((frameLength shr 11) and 0x03))).toByte()
        header[4] = ((frameLength shr 3) and 0xFF).toByte()
        header[5] = ((((frameLength and 0x07) shl 5) or 0x1F)).toByte()
        header[6] = 0xFC.toByte()

        if (firstAdtsLogs < 5) {
            Log.i(
                TAG,
                "adts[${firstAdtsLogs + 1}] profile=${profile + 1} freqIdx=$freqIdx chanCfg=$chanCfg frameLen=$frameLength " +
                    "header=${toHex(header, 7)} payload16=${toHex(payload, 16)}",
            )
            firstAdtsLogs += 1
        }

        return header + payload
    }

    data class RuntimeStats(
        val framesEncoded: Long,
    )

    fun runtimeStats(): RuntimeStats = RuntimeStats(framesEncoded = framesEncoded)

    private fun toHex(bytes: ByteArray, maxLen: Int): String {
        val end = bytes.size.coerceAtMost(maxLen)
        if (end == 0) return ""
        return bytes.copyOf(end).joinToString(separator = "") { "%02X".format(it) }
    }
}
