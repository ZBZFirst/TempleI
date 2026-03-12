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
    private const val I_FRAME_INTERVAL_SECONDS = 1
    private const val LOG_FIRST_AU_EVENTS = 5L
    private const val LOG_EVERY_N_AU_EVENTS = 120L

    private var nodeState: NodeState = NodeState.Idle
    private var lastError: String = ""
    private var outputListener: ((EncodedAccessUnit) -> Unit)? = null
    private var activeConfig: EncoderConfig = EncoderConfig()
    private var codec: MediaCodec? = null
    private var codecConfigAnnexB: ByteArray = ByteArray(0)
    private var framesEncoded: Long = 0
    private var framesQueuedIn: Long = 0
    private var framesDroppedNoInputBuffer: Long = 0
    private var encodedAccessUnitCount: Long = 0
    private var keyFrameCount: Long = 0
    private var lastVideoPresentationTimeUs: Long = -1
    private var firstOutputLogs = 0
    private var firstIdrSeen = false
    private var spsSeen = false
    private var ppsSeen = false
    private var noInputBufferLogs = 0

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
            Log.i(TAG, "tsMs=${System.currentTimeMillis()} milestone=encoders started component=video")
            Log.i(TAG, "tsMs=${System.currentTimeMillis()} milestone=codec selected mime=$MIME_TYPE width=${activeConfig.width} height=${activeConfig.height} fps=${activeConfig.fps} bitrate=${activeConfig.bitrate}")
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
            Log.w(TAG, "tsMs=${System.currentTimeMillis()} milestone=video frame dropped reason=encoder not running state=$nodeState")
            return
        }

        val configuredWidth = activeConfig.width
        val configuredHeight = activeConfig.height
        if (frame.width != configuredWidth || frame.height != configuredHeight) {
            val reconfigure = restartForResolution(frame.width, frame.height)
            if (reconfigure.isFailure) {
                Log.w(TAG, "tsMs=${System.currentTimeMillis()} milestone=video frame dropped reason=dimension mismatch frame=${frame.width}x${frame.height} encoder=${configuredWidth}x${configuredHeight}")
                return
            }
        }

        val activeCodec = codec ?: return
        runCatching {
            val inputIndex = activeCodec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuffer = activeCodec.getInputBuffer(inputIndex) ?: return@runCatching
                inputBuffer.clear()
                inputBuffer.put(frame.i420Data)
                val presentationTimeUs = frame.timestampNs / 1_000L
                activeCodec.queueInputBuffer(inputIndex, 0, frame.i420Data.size, presentationTimeUs, 0)
                Log.d(TAG, "tsMs=${System.currentTimeMillis()} milestone=video input accepted ptsUs=$presentationTimeUs")
                framesQueuedIn += 1
                StreamPipelineMetrics.recordEncoderQueueIn()
            } else {
                framesDroppedNoInputBuffer += 1
                if (noInputBufferLogs < 5 || framesDroppedNoInputBuffer % 120L == 0L) {
                    Log.w(TAG, "tsMs=${System.currentTimeMillis()} milestone=video frame dropped reason=input buffer unavailable index=$inputIndex droppedNoInput=$framesDroppedNoInputBuffer")
                    noInputBufferLogs += 1
                }
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
        codecConfigAnnexB = ByteArray(0)
        framesEncoded = 0
        framesQueuedIn = 0
        framesDroppedNoInputBuffer = 0
        encodedAccessUnitCount = 0
        keyFrameCount = 0
        lastVideoPresentationTimeUs = -1
        firstOutputLogs = 0
        firstIdrSeen = false
        spsSeen = false
        ppsSeen = false
        noInputBufferLogs = 0
        nodeState = NodeState.Idle
        lastError = ""
    }

    fun state(): NodeState = nodeState

    fun error(): String = lastError

    private fun restartForResolution(frameWidth: Int, frameHeight: Int): Result<Unit> {
        if (frameWidth <= 0 || frameHeight <= 0) {
            return Result.failure(IllegalArgumentException("invalid frame resolution"))
        }

        if (frameWidth == activeConfig.width && frameHeight == activeConfig.height) {
            return Result.success(Unit)
        }

        Log.i(TAG, "encoder-reconfigure old=${activeConfig.width}x${activeConfig.height} new=${frameWidth}x${frameHeight}")
        // PR E: FFmpeg backend path now owns downstream reconfigure handling.
        stop()
        val updatedConfig = activeConfig.copy(width = frameWidth, height = frameHeight)
        val configured = configure(updatedConfig)
        if (configured.isFailure) {
            return configured
        }
        return start()
    }

    private fun drainOutput() {
        val activeCodec = codec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            when (val outputIndex = activeCodec.dequeueOutputBuffer(bufferInfo, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> emitCodecConfig(activeCodec.outputFormat)
                else -> {
                    if (outputIndex >= 0) {
                        Log.d(
                            TAG,
                            "tsMs=${System.currentTimeMillis()} milestone=video output buffer dequeued index=$outputIndex size=${bufferInfo.size} flags=${bufferInfo.flags} ptsUs=${bufferInfo.presentationTimeUs}",
                        )
                        val outputBuffer = activeCodec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val accessUnit = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.get(accessUnit)

                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                Log.i(TAG, "tsMs=${System.currentTimeMillis()} milestone=video codec config received")
                                activeCodec.releaseOutputBuffer(outputIndex, false)
                                continue
                            }

                            val normalizedAccessUnit = normalizeToAnnexB(accessUnit)
                            val keyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            val accessUnitWithConfig = if (
                                keyFrame &&
                                codecConfigAnnexB.isNotEmpty() &&
                                (!containsNalType(normalizedAccessUnit, 7) || !containsNalType(normalizedAccessUnit, 8))
                            ) {
                                codecConfigAnnexB + normalizedAccessUnit
                            } else {
                                normalizedAccessUnit
                            }

                            Log.i(
                                TAG,
                                "tsMs=${System.currentTimeMillis()} milestone=video access unit accepted size=${accessUnitWithConfig.size} ptsUs=${bufferInfo.presentationTimeUs}",
                            )
                            outputListener?.invoke(
                                EncodedAccessUnit(
                                    data = accessUnitWithConfig,
                                    presentationTimeUs = bufferInfo.presentationTimeUs,
                                    flags = bufferInfo.flags,
                                ),
                            )
                            StreamPipelineMetrics.recordEncoderOutput()
                            framesEncoded += 1
                            encodedAccessUnitCount += 1
                            lastVideoPresentationTimeUs = bufferInfo.presentationTimeUs
                            if (keyFrame) {
                                keyFrameCount += 1
                            }
                            val annexB = isAnnexB(accessUnitWithConfig)
                            val containsSps = containsNalType(accessUnitWithConfig, 7)
                            val containsPps = containsNalType(accessUnitWithConfig, 8)
                            val containsIdr = containsNalType(accessUnitWithConfig, 5)
                            if (containsSps) spsSeen = true
                            if (containsPps) ppsSeen = true
                            if (containsIdr && !firstIdrSeen) {
                                firstIdrSeen = true
                                Log.i(TAG, "tsMs=${System.currentTimeMillis()} milestone=first video keyframe received size=${accessUnitWithConfig.size} ptsUs=${bufferInfo.presentationTimeUs}")
                            }
                            if (shouldLogEncodedAuEvent(encodedAccessUnitCount)) {
                                Log.d(
                                    TAG,
                                    "tsMs=${System.currentTimeMillis()} video-au-summary frame=$encodedAccessUnitCount keyframes=$keyFrameCount lastPtsUs=$lastVideoPresentationTimeUs",
                                )
                            }
                            if (firstOutputLogs < 5) {
                                Log.d(
                                    TAG,
                                    "tsMs=${System.currentTimeMillis()} milestone=encoded frame size frame=${encodedAccessUnitCount} bytes=${accessUnitWithConfig.size} flags=${bufferInfo.flags} " +
                                        "key=$keyFrame annexB=$annexB first16=${toHex(accessUnitWithConfig, 16)}",
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
        Log.i(TAG, "tsMs=${System.currentTimeMillis()} milestone=video codec config received mime=${format.getString(MediaFormat.KEY_MIME)} hasCsd0=${csd0 != null} hasCsd1=${csd1 != null}")
        val configPayload = mergeAnnexB(csd0, csd1)
        if (configPayload.isEmpty()) {
            return
        }
        codecConfigAnnexB = configPayload
        Log.i(TAG, "codec-config-annexb bytes=${configPayload.size}")
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

    private fun normalizeToAnnexB(data: ByteArray): ByteArray {
        if (isAnnexB(data)) {
            return data
        }

        var offset = 0
        val chunks = ArrayList<ByteArray>()
        while (offset + 4 <= data.size) {
            val nalLen =
                ((data[offset].toInt() and 0xFF) shl 24) or
                    ((data[offset + 1].toInt() and 0xFF) shl 16) or
                    ((data[offset + 2].toInt() and 0xFF) shl 8) or
                    (data[offset + 3].toInt() and 0xFF)
            offset += 4
            if (nalLen <= 0 || offset + nalLen > data.size) {
                return data
            }
            val nal = ByteArray(nalLen + 4)
            nal[0] = 0x00
            nal[1] = 0x00
            nal[2] = 0x00
            nal[3] = 0x01
            System.arraycopy(data, offset, nal, 4, nalLen)
            chunks += nal
            offset += nalLen
        }

        if (chunks.isEmpty() || offset != data.size) {
            return data
        }

        val total = chunks.sumOf { it.size }
        val merged = ByteArray(total)
        var writeOffset = 0
        chunks.forEach { chunk ->
            System.arraycopy(chunk, 0, merged, writeOffset, chunk.size)
            writeOffset += chunk.size
        }
        return merged
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
        val framesQueuedIn: Long,
        val framesDroppedNoInputBuffer: Long,
        val encodedAccessUnitCount: Long,
        val keyFrameCount: Long,
        val lastVideoPresentationTimeUs: Long,
        val state: NodeState,
        val lastError: String,
    )

    fun runtimeStats(): RuntimeStats = RuntimeStats(
        framesEncoded = framesEncoded,
        framesQueuedIn = framesQueuedIn,
        framesDroppedNoInputBuffer = framesDroppedNoInputBuffer,
        encodedAccessUnitCount = encodedAccessUnitCount,
        keyFrameCount = keyFrameCount,
        lastVideoPresentationTimeUs = lastVideoPresentationTimeUs,
        state = nodeState,
        lastError = lastError,
    )

    private fun shouldLogEncodedAuEvent(count: Long): Boolean {
        return count <= LOG_FIRST_AU_EVENTS || count % LOG_EVERY_N_AU_EVENTS == 0L
    }

    private fun toHex(bytes: ByteArray, maxLen: Int): String {
        val end = bytes.size.coerceAtMost(maxLen)
        if (end == 0) return ""
        return bytes.copyOf(end).joinToString(separator = "") { "%02X".format(it) }
    }
}
