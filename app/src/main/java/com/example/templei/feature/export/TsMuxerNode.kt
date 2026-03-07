package com.example.templei.feature.export

import android.media.MediaCodec
import android.util.Log

/**
 * MPEG-TS mux node contract for Screen 2 stream path.
 *
 * This node now exposes runtime probing so Screen 2 can distinguish
 * "runtime pending" from a loaded mux integration.
 */
object TsMuxerNode {
    private const val TAG = "TempleI-TsMux"
    private const val MUX_NATIVE_LIBRARY = "templei_mux"

    private var started = false
    private var startupState: StartupState = StartupState.Idle
    private var outputFormatKnown = false
    private var firstKeyframeLatched = false
    private var startupSendGateOpen = false
    private var startupBurstPacketsRemaining = 0
    private var runtime: RuntimeBinding = RuntimeBinding.Uninitialized
    private var packetOutputListener: ((ByteArray) -> Unit)? = null
    private var videoAccessUnitsIngested: Long = 0
    private var audioAccessUnitsIngested: Long = 0
    private var packetsDrained: Long = 0
    private var bytesHandedToSrt: Long = 0
    private var lastIngestError: String = ""
    private var audioAccessUnitsSuppressed: Long = 0
    private val pendingVideoAccessUnits = ArrayDeque<VideoEncoderNode.EncodedAccessUnit>()
    private val pendingAudioAccessUnits = ArrayDeque<AudioEncoderNode.EncodedAccessUnit>()
    private const val STARTUP_BURST_PACKET_COUNT = 256

    enum class StartupState {
        Idle,
        CaptureWarmup,
        EncoderConfigured,
        OutputFormatKnown,
        FirstKeyframeLatched,
        PsiPrimed,
        StartupBurstSent,
        Streaming,
        ReconfigurePending,
        Fault,
    }

    /**
     * Probe for runtime availability once and cache the result.
     */
    fun isAvailable(): Boolean = resolveRuntime().isSuccess

    fun availabilityMessage(): String {
        val resolved = resolveRuntime()
        return if (resolved.isSuccess) {
            "native mux runtime ready"
        } else {
            resolved.exceptionOrNull()?.message ?: "native mux path unavailable"
        }
    }

    fun setPacketOutputListener(listener: ((ByteArray) -> Unit)?) {
        packetOutputListener = listener
    }

    fun prepare(): Result<Unit> {
        val resolved = resolveRuntime()
        if (resolved.isFailure) {
            return Result.failure(resolved.exceptionOrNull() ?: IllegalStateException("native mux path unavailable"))
        }
        val result = resolved.getOrThrow().prepare()
        if (result.isSuccess) {
            setStartupState(StartupState.CaptureWarmup)
            outputFormatKnown = false
            firstKeyframeLatched = false
            startupSendGateOpen = false
            startupBurstPacketsRemaining = 0
            videoAccessUnitsIngested = 0
            audioAccessUnitsIngested = 0
            packetsDrained = 0
            bytesHandedToSrt = 0
            lastIngestError = ""
            audioAccessUnitsSuppressed = 0
            // Preserve pending access units so capture bootstrap emitted before prepare/start still flushes.
        }
        return result
    }

    fun start(): Result<Unit> {
        val resolved = resolveRuntime()
        if (resolved.isFailure) {
            return Result.failure(resolved.exceptionOrNull() ?: IllegalStateException("native mux path unavailable"))
        }

        val startResult = resolved.getOrThrow().start()
        if (startResult.isSuccess) {
            started = true
            setStartupState(StartupState.EncoderConfigured)
            Log.i(TAG, "muxer-start container=MPEG-TS packetSize=188")
            flushPendingAccessUnits()
        }
        return startResult
    }

    fun ingestVideo(accessUnit: VideoEncoderNode.EncodedAccessUnit): Result<Unit> {
        if (!started) {
            // Capture path currently boots before transport start; queue until mux starts.
            pendingVideoAccessUnits += accessUnit
            val snapshot = StreamPipelineMetrics.snapshot()
            StreamPipelineMetrics.updateQueueDepths(
                cameraToEncoder = snapshot.cameraToEncoderQueueDepth,
                encoderToMux = pendingVideoAccessUnits.size + pendingAudioAccessUnits.size,
                muxToSrt = snapshot.muxToSrtQueueDepth,
            )
            return Result.success(Unit)
        }

        val resolved = resolveRuntime()
        if (resolved.isFailure) {
            return Result.failure(resolved.exceptionOrNull() ?: IllegalStateException("native mux path unavailable"))
        }

        val ingestResult = resolved.getOrThrow().ingestVideo(accessUnit)
        if (ingestResult.isSuccess) {
            videoAccessUnitsIngested += 1
            StreamPipelineMetrics.recordMuxVideoIngest()
        }
        if (ingestResult.isFailure) {
            setStartupState(StartupState.Fault)
            lastIngestError = ingestResult.exceptionOrNull()?.message.orEmpty()
            Log.e(TAG, "video ingest failed: $lastIngestError")
            return ingestResult
        }

        updateStartupGateFromVideo(accessUnit)
        drainPacketToOutput()
        return Result.success(Unit)
    }

    fun ingestAudio(accessUnit: AudioEncoderNode.EncodedAccessUnit): Result<Unit> {
        // PR3 bring-up policy: keep transport video-only until OBS ingest is stable.
        audioAccessUnitsSuppressed += 1
        if (audioAccessUnitsSuppressed <= 5 || (audioAccessUnitsSuppressed % 300L) == 0L) {
            Log.i(TAG, "audio ingest suppressed for video-only bring-up count=$audioAccessUnitsSuppressed")
        }
        return Result.success(Unit)
    }

    fun stop() {
        if (started) {
            resolveRuntime().onSuccess { it.stop() }
        }
        started = false
        pendingVideoAccessUnits.clear()
        pendingAudioAccessUnits.clear()
        val snapshot = StreamPipelineMetrics.snapshot()
        StreamPipelineMetrics.updateQueueDepths(
            cameraToEncoder = snapshot.cameraToEncoderQueueDepth,
            encoderToMux = 0,
            muxToSrt = snapshot.muxToSrtQueueDepth,
        )
        packetOutputListener = null
    }

    fun resetRuntimeState() {
        stop()
        runtime = RuntimeBinding.Uninitialized
        videoAccessUnitsIngested = 0
        audioAccessUnitsIngested = 0
        audioAccessUnitsSuppressed = 0
        packetsDrained = 0
        bytesHandedToSrt = 0
        lastIngestError = ""
        setStartupState(StartupState.Idle)
        outputFormatKnown = false
        firstKeyframeLatched = false
        startupSendGateOpen = false
        startupBurstPacketsRemaining = 0
        Log.i(TAG, "runtime reset")
    }

    fun markReconfigurePending() {
        setStartupState(StartupState.ReconfigurePending)
        outputFormatKnown = false
        firstKeyframeLatched = false
        startupSendGateOpen = false
        startupBurstPacketsRemaining = 0
    }

    fun isStarted(): Boolean = started

    /**
     * Test-only hook to provide an injectable runtime while native wiring is in progress.
     */
    internal fun installRuntimeForTesting(testRuntime: Runtime?) {
        runtime = when (testRuntime) {
            null -> RuntimeBinding.Uninitialized
            else -> RuntimeBinding.Loaded(testRuntime)
        }
        started = false
        pendingVideoAccessUnits.clear()
        pendingAudioAccessUnits.clear()
    }


    private fun flushPendingAccessUnits() {
        if (!started) {
            return
        }

        while (pendingVideoAccessUnits.isNotEmpty()) {
            val accessUnit = pendingVideoAccessUnits.removeFirst()
            val result = ingestVideo(accessUnit)
            if (result.isFailure) {
                break
            }
        }

        while (pendingAudioAccessUnits.isNotEmpty()) {
            val accessUnit = pendingAudioAccessUnits.removeFirst()
            val result = ingestAudio(accessUnit)
            if (result.isFailure) {
                break
            }
        }
    }

    private fun drainPacketToOutput() {
        if (!startupSendGateOpen) {
            return
        }

        val runtimeInstance = resolveRuntime().getOrNull() ?: return
        while (true) {
            val packet = runtimeInstance.drainPacket()
            if (packet.isEmpty()) {
                break
            }
            packetsDrained += 1
            bytesHandedToSrt += packet.size
            StreamPipelineMetrics.recordMuxPacketDrain()
            if (packetsDrained <= 5 || (packetsDrained % 300L) == 0L) {
                val syncByteOk = packet.firstOrNull() == 0x47.toByte()
                Log.i(TAG, "mux-packet index=$packetsDrained bytes=${packet.size} sync47=$syncByteOk")
            }
            packetOutputListener?.invoke(packet)

            if (startupBurstPacketsRemaining > 0) {
                startupBurstPacketsRemaining -= 1
                if (startupBurstPacketsRemaining == 0) {
                    setStartupState(StartupState.StartupBurstSent)
                    setStartupState(StartupState.Streaming)
                }
            }
        }
    }

    private fun updateStartupGateFromVideo(accessUnit: VideoEncoderNode.EncodedAccessUnit) {
        val hasCodecConfig = (accessUnit.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
        val hasKeyframe = (accessUnit.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

        if (hasCodecConfig && !outputFormatKnown) {
            outputFormatKnown = true
            setStartupState(StartupState.OutputFormatKnown)
        }

        if (hasKeyframe && !firstKeyframeLatched) {
            firstKeyframeLatched = true
            setStartupState(StartupState.FirstKeyframeLatched)
        }

        if (!startupSendGateOpen && outputFormatKnown && firstKeyframeLatched) {
            setStartupState(StartupState.PsiPrimed)
            startupSendGateOpen = true
            startupBurstPacketsRemaining = STARTUP_BURST_PACKET_COUNT
            Log.i(TAG, "startup-send-gate opened burstPackets=$STARTUP_BURST_PACKET_COUNT")
        }
    }

    private fun setStartupState(state: StartupState) {
        if (startupState != state) {
            startupState = state
            Log.i(TAG, "startup-state=$startupState")
        }
    }

    private fun resolveRuntime(): Result<Runtime> {
        return when (val current = runtime) {
            RuntimeBinding.Uninitialized -> {
                val loaded = loadNativeRuntime()
                runtime = loaded
                when (loaded) {
                    RuntimeBinding.Uninitialized -> Result.failure(IllegalStateException("native mux path unavailable"))
                    is RuntimeBinding.Loaded -> Result.success(loaded.runtime)
                    is RuntimeBinding.Unavailable -> Result.failure(IllegalStateException(loaded.reason))
                }
            }

            is RuntimeBinding.Loaded -> Result.success(current.runtime)
            is RuntimeBinding.Unavailable -> Result.failure(IllegalStateException(current.reason))
        }
    }

    private fun loadNativeRuntime(): RuntimeBinding {
        return runCatching {
            System.loadLibrary(MUX_NATIVE_LIBRARY)
            RuntimeBinding.Loaded(JniMuxRuntime)
        }.getOrElse {
            RuntimeBinding.Unavailable("native mux runtime pending (missing $MUX_NATIVE_LIBRARY)")
        }
    }


    data class RuntimeStats(
        val started: Boolean,
        val videoAccessUnitsIngested: Long,
        val audioAccessUnitsIngested: Long,
        val packetsDrained: Long,
        val bytesHandedToSrt: Long,
        val startupState: StartupState,
        val audioAccessUnitsSuppressed: Long,
    )

    fun lastIngestError(): String = lastIngestError

    fun runtimeStats(): RuntimeStats {
        return RuntimeStats(
            started = started,
            videoAccessUnitsIngested = videoAccessUnitsIngested,
            audioAccessUnitsIngested = audioAccessUnitsIngested,
            packetsDrained = packetsDrained,
            bytesHandedToSrt = bytesHandedToSrt,
            startupState = startupState,
            audioAccessUnitsSuppressed = audioAccessUnitsSuppressed,
        )
    }

    internal interface Runtime {
        fun prepare(): Result<Unit>
        fun start(): Result<Unit>
        fun stop()
        fun ingestVideo(accessUnit: VideoEncoderNode.EncodedAccessUnit): Result<Unit>
        fun ingestAudio(accessUnit: AudioEncoderNode.EncodedAccessUnit): Result<Unit>
        fun drainPacket(): ByteArray
    }

    private sealed interface RuntimeBinding {
        data object Uninitialized : RuntimeBinding
        data class Loaded(val runtime: Runtime) : RuntimeBinding
        data class Unavailable(val reason: String) : RuntimeBinding
    }

    /**
     * JNI-backed runtime adapter while native mux internals are integrated.
     */
    private object JniMuxRuntime : Runtime {
        override fun prepare(): Result<Unit> {
            return if (TsMuxNativeBridge.nativePrepare()) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(TsMuxNativeBridge.nativeLastError().ifBlank { "native mux prepare failed" }))
            }
        }

        override fun start(): Result<Unit> {
            return if (TsMuxNativeBridge.nativeStart()) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(TsMuxNativeBridge.nativeLastError().ifBlank { "native mux start failed" }))
            }
        }

        override fun stop() {
            TsMuxNativeBridge.nativeStop()
        }

        override fun ingestVideo(accessUnit: VideoEncoderNode.EncodedAccessUnit): Result<Unit> {
            return if (TsMuxNativeBridge.nativeIngestVideo(
                    accessUnit.data,
                    accessUnit.presentationTimeUs,
                    accessUnit.flags,
                    accessUnit.trackIndex,
                )
            ) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(TsMuxNativeBridge.nativeLastError().ifBlank { "native mux video ingest failed" }))
            }
        }

        override fun ingestAudio(accessUnit: AudioEncoderNode.EncodedAccessUnit): Result<Unit> {
            return if (TsMuxNativeBridge.nativeIngestAudio(
                    accessUnit.data,
                    accessUnit.presentationTimeUs,
                    accessUnit.flags,
                    accessUnit.trackIndex,
                )
            ) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(TsMuxNativeBridge.nativeLastError().ifBlank { "native mux audio ingest failed" }))
            }
        }

        override fun drainPacket(): ByteArray {
            return TsMuxNativeBridge.nativeDrainPacket()
        }
    }
}
