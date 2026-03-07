package com.example.templei.feature.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.example.templei.feature.camera.CameraFeature
import java.nio.ByteBuffer

/**
 * Video path node for Screen 2 streaming orchestration.
 *
 * This node encodes camera I420 frames into H.264 Annex-B access units.
 */
object VideoEncoderNode {
    private const val TAG = "TempleI-VideoEnc"
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

    private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val I_FRAME_INTERVAL_SECONDS = 2

    private var nodeState: NodeState = NodeState.Idle
    private var lastError: String = ""
    private var outputListener: ((EncodedAccessUnit) -> Unit)? = null
    private var activeConfig: EncoderConfig = EncoderConfig()
    private var codec: MediaCodec? = null
    private var framesEncoded: Long = 0
    private var firstOutputLogs = 0
    private var firstIdrSeen = false
    private var spsSeen = false
    private var ppsSeen = false

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

        val format = MediaFormat.createVideoFormat(MIME_TYPE, activeConfig.width, activeConfig.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, activeConfig.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, activeConfig.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
        }

        return runCatching {
            codec = MediaCodec.createEncoderByType(MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            nodeState = NodeState.Running
            drainOutput()
        }.onFailure {
            nodeState = NodeState.Faulted
            lastError = "video encoder start failed: ${it.message.orEmpty()}"
            stop()
        }.map {}
    }

    fun queueFrame(frame: CameraFeature.FramePacket) {
        if (nodeState != NodeState.Running) {
            return
        }

        val configuredWidth = activeConfig.width
        val configuredHeight = activeConfig.height
        if (frame.width != configuredWidth || frame.height != configuredHeight) {
            // Ignore frames with unexpected dimensions while camera + encoder profile alignment is in progress.
            return
        }

        val activeCodec = codec ?: return
        runCatching {
            val inputIndex = activeCodec.dequeueInputBuffer(0)
            if (inputIndex >= 0) {
                val inputBuffer = activeCodec.getInputBuffer(inputIndex) ?: return@runCatching
                inputBuffer.clear()
                inputBuffer.put(frame.i420Data)
                val presentationTimeUs = frame.timestampNs / 1_000L
                activeCodec.queueInputBuffer(inputIndex, 0, frame.i420Data.size, presentationTimeUs, 0)
            }
            drainOutput()
        }.onFailure {
            nodeState = NodeState.Faulted
            lastError = "video encode failed: ${it.message.orEmpty()}"
        }
    }

    fun stop() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        framesEncoded = 0
        firstOutputLogs = 0
        firstIdrSeen = false
        spsSeen = false
        ppsSeen = false
        nodeState = NodeState.Idle
        lastError = ""
    }

    fun state(): NodeState = nodeState

    fun error(): String = lastError

    private fun drainOutput() {
        val activeCodec = codec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            when (val outputIndex = activeCodec.dequeueOutputBuffer(bufferInfo, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> emitCodecConfig(activeCodec.outputFormat)
                else -> {
                    if (outputIndex >= 0) {
                        val outputBuffer = activeCodec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val accessUnit = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.get(accessUnit)

                            outputListener?.invoke(
                                EncodedAccessUnit(
                                    data = accessUnit,
                                    presentationTimeUs = bufferInfo.presentationTimeUs,
                                    flags = bufferInfo.flags,
                                ),
                            )
                            framesEncoded += 1
                            val keyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            val annexB = isAnnexB(accessUnit)
                            val containsSps = containsNalType(accessUnit, 7)
                            val containsPps = containsNalType(accessUnit, 8)
                            val containsIdr = containsNalType(accessUnit, 5)
                            if (containsSps) spsSeen = true
                            if (containsPps) ppsSeen = true
                            if (containsIdr && !firstIdrSeen) {
                                firstIdrSeen = true
                                Log.i(TAG, "first-idr spsBefore=$spsSeen ppsBefore=$ppsSeen")
                            }
                            if (firstOutputLogs < 5) {
                                Log.i(
                                    TAG,
                                    "h264-buffer[${firstOutputLogs + 1}] size=${bufferInfo.size} flags=${bufferInfo.flags} " +
                                        "key=$keyFrame annexB=$annexB first16=${toHex(accessUnit, 16)}",
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

    private fun emitCodecConfig(format: MediaFormat) {
        val csd0 = format.getByteBuffer("csd-0")
        val csd1 = format.getByteBuffer("csd-1")
        val configPayload = mergeAnnexB(csd0, csd1)
        if (configPayload.isEmpty()) {
            return
        }
        outputListener?.invoke(
            EncodedAccessUnit(
                data = configPayload,
                presentationTimeUs = 0,
                flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
            ),
        )
    }

    private fun mergeAnnexB(vararg buffers: ByteBuffer?): ByteArray {
        val chunks = buffers.mapNotNull { buf ->
            val src = buf ?: return@mapNotNull null
            val dup = src.duplicate()
            if (!dup.hasRemaining()) {
                return@mapNotNull null
            }
            val data = ByteArray(dup.remaining())
            dup.get(data)
            if (startsWithStartCode(data)) data else byteArrayOf(0x00, 0x00, 0x00, 0x01) + data
        }
        if (chunks.isEmpty()) {
            return ByteArray(0)
        }
        val total = chunks.sumOf { it.size }
        val merged = ByteArray(total)
        var offset = 0
        chunks.forEach {
            System.arraycopy(it, 0, merged, offset, it.size)
            offset += it.size
        }
        return merged
    }

    private fun startsWithStartCode(data: ByteArray): Boolean {
        if (data.size < 4) return false
        return data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()
    }

    private fun isAnnexB(data: ByteArray): Boolean {
        if (data.size < 4) return false
        return (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) || startsWithStartCode(data)
    }

    private fun containsNalType(data: ByteArray, nalType: Int): Boolean {
        var i = 0
        while (i + 4 < data.size) {
            val startCodeLen = if (
                i + 3 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
            ) {
                3
            } else if (
                i + 4 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) {
                4
            } else {
                i += 1
                continue
            }
            val nalIndex = i + startCodeLen
            if (nalIndex < data.size) {
                val type = data[nalIndex].toInt() and 0x1F
                if (type == nalType) return true
            }
            i = nalIndex + 1
        }
        return false
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
