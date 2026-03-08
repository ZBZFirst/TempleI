package com.example.templei.feature.export

/**
 * Shared diagnostics holder for camera -> encoder -> mux -> srt stage timing and counters.
 */
object StreamPipelineMetrics {
    private val lock = Any()

    enum class BackpressureOrigin {
        None,
        CameraToEncoder,
        EncoderToMux,
        MuxToSrt,
    }

    data class Snapshot(
        val cameraArrivalCount: Long,
        val encoderQueueInCount: Long,
        val encoderOutputCount: Long,
        val muxVideoIngestCount: Long,
        val muxAudioIngestCount: Long,
        val muxDrainPacketCount: Long,
        val srtSendAttemptCount: Long,
        val srtSendSuccessCount: Long,
        val srtSendFailureCount: Long,
        val cameraToEncoderLastLatencyUs: Long,
        val encoderToMuxLastLatencyUs: Long,
        val muxToSrtLastLatencyUs: Long,
        val cameraToEncoderMaxLatencyUs: Long,
        val encoderToMuxMaxLatencyUs: Long,
        val muxToSrtMaxLatencyUs: Long,
        val cameraToEncoderQueueDepth: Int,
        val encoderToMuxQueueDepth: Int,
        val muxToSrtQueueDepth: Int,
        val cameraToEncoderDropCount: Long,
        val encoderToMuxDropCount: Long,
        val muxToSrtDropCount: Long,
    )

    data class DiagnosticSnapshot(
        val capturedAtMs: Long,
        val origin: BackpressureOrigin,
        val reason: String,
        val frameBudgetUs: Long,
        val data: Snapshot,
    ) {
        fun compactSummary(): String {
            return "origin=${origin.name} reason=$reason budgetUs=$frameBudgetUs " +
                "count(cam=${data.cameraArrivalCount},encIn=${data.encoderQueueInCount},encOut=${data.encoderOutputCount}," +
                "vIn=${data.muxVideoIngestCount},aIn=${data.muxAudioIngestCount}) " +
                "latUs(c2e=${data.cameraToEncoderLastLatencyUs},e2m=${data.encoderToMuxLastLatencyUs},m2s=${data.muxToSrtLastLatencyUs}) " +
                "q(c2e=${data.cameraToEncoderQueueDepth},e2m=${data.encoderToMuxQueueDepth},m2s=${data.muxToSrtQueueDepth}) " +
                "drop(c2e=${data.cameraToEncoderDropCount},e2m=${data.encoderToMuxDropCount},m2s=${data.muxToSrtDropCount})"
        }
    }

    private var cameraArrivalCount: Long = 0
    private var encoderQueueInCount: Long = 0
    private var encoderOutputCount: Long = 0
    private var muxVideoIngestCount: Long = 0
    private var muxAudioIngestCount: Long = 0
    private var muxDrainPacketCount: Long = 0
    private var srtSendAttemptCount: Long = 0
    private var srtSendSuccessCount: Long = 0
    private var srtSendFailureCount: Long = 0

    private var lastCameraArrivalNs: Long = 0
    private var lastEncoderQueueInNs: Long = 0
    private var lastEncoderOutputNs: Long = 0
    private var lastMuxDrainNs: Long = 0

    private var cameraToEncoderLastLatencyUs: Long = 0
    private var encoderToMuxLastLatencyUs: Long = 0
    private var muxToSrtLastLatencyUs: Long = 0

    private var cameraToEncoderMaxLatencyUs: Long = 0
    private var encoderToMuxMaxLatencyUs: Long = 0
    private var muxToSrtMaxLatencyUs: Long = 0

    private var cameraToEncoderQueueDepth: Int = 0
    private var encoderToMuxQueueDepth: Int = 0
    private var muxToSrtQueueDepth: Int = 0

    private var cameraToEncoderDropCount: Long = 0
    private var encoderToMuxDropCount: Long = 0
    private var muxToSrtDropCount: Long = 0

    fun reset() {
        synchronized(lock) {
            cameraArrivalCount = 0
            encoderQueueInCount = 0
            encoderOutputCount = 0
            muxVideoIngestCount = 0
            muxAudioIngestCount = 0
            muxDrainPacketCount = 0
            srtSendAttemptCount = 0
            srtSendSuccessCount = 0
            srtSendFailureCount = 0

            lastCameraArrivalNs = 0
            lastEncoderQueueInNs = 0
            lastEncoderOutputNs = 0
            lastMuxDrainNs = 0

            cameraToEncoderLastLatencyUs = 0
            encoderToMuxLastLatencyUs = 0
            muxToSrtLastLatencyUs = 0

            cameraToEncoderMaxLatencyUs = 0
            encoderToMuxMaxLatencyUs = 0
            muxToSrtMaxLatencyUs = 0

            cameraToEncoderQueueDepth = 0
            encoderToMuxQueueDepth = 0
            muxToSrtQueueDepth = 0

            cameraToEncoderDropCount = 0
            encoderToMuxDropCount = 0
            muxToSrtDropCount = 0
        }
    }

    fun recordCameraArrival(nowNs: Long = System.nanoTime()) {
        synchronized(lock) {
            cameraArrivalCount += 1
            lastCameraArrivalNs = nowNs
        }
    }

    fun recordEncoderQueueIn(nowNs: Long = System.nanoTime()) {
        synchronized(lock) {
            encoderQueueInCount += 1
            lastEncoderQueueInNs = nowNs
            val latencyUs = elapsedUs(startNs = lastCameraArrivalNs, endNs = nowNs)
            cameraToEncoderLastLatencyUs = latencyUs
            if (latencyUs > cameraToEncoderMaxLatencyUs) {
                cameraToEncoderMaxLatencyUs = latencyUs
            }
        }
    }

    fun recordEncoderOutput(nowNs: Long = System.nanoTime()) {
        synchronized(lock) {
            encoderOutputCount += 1
            lastEncoderOutputNs = nowNs
            val latencyUs = elapsedUs(startNs = lastEncoderQueueInNs, endNs = nowNs)
            encoderToMuxLastLatencyUs = latencyUs
            if (latencyUs > encoderToMuxMaxLatencyUs) {
                encoderToMuxMaxLatencyUs = latencyUs
            }
        }
    }

    fun recordMuxVideoIngest() {
        synchronized(lock) {
            muxVideoIngestCount += 1
        }
    }

    fun recordMuxAudioIngest() {
        synchronized(lock) {
            muxAudioIngestCount += 1
        }
    }

    fun recordMuxPacketDrain(nowNs: Long = System.nanoTime()) {
        synchronized(lock) {
            muxDrainPacketCount += 1
            lastMuxDrainNs = nowNs
        }
    }

    fun recordSrtSendAttempt(success: Boolean, nowNs: Long = System.nanoTime()) {
        synchronized(lock) {
            srtSendAttemptCount += 1
            if (success) {
                srtSendSuccessCount += 1
            } else {
                srtSendFailureCount += 1
            }
            val latencyUs = elapsedUs(startNs = lastMuxDrainNs, endNs = nowNs)
            muxToSrtLastLatencyUs = latencyUs
            if (latencyUs > muxToSrtMaxLatencyUs) {
                muxToSrtMaxLatencyUs = latencyUs
            }
        }
    }

    fun updateQueueDepths(
        cameraToEncoder: Int,
        encoderToMux: Int,
        muxToSrt: Int,
    ) {
        synchronized(lock) {
            cameraToEncoderQueueDepth = cameraToEncoder
            encoderToMuxQueueDepth = encoderToMux
            muxToSrtQueueDepth = muxToSrt
        }
    }

    fun recordQueueDrop(
        cameraToEncoder: Long = 0,
        encoderToMux: Long = 0,
        muxToSrt: Long = 0,
    ) {
        synchronized(lock) {
            cameraToEncoderDropCount += cameraToEncoder
            encoderToMuxDropCount += encoderToMux
            muxToSrtDropCount += muxToSrt
        }
    }

    fun snapshot(): Snapshot {
        synchronized(lock) {
            return Snapshot(
                cameraArrivalCount = cameraArrivalCount,
                encoderQueueInCount = encoderQueueInCount,
                encoderOutputCount = encoderOutputCount,
                muxVideoIngestCount = muxVideoIngestCount,
                muxAudioIngestCount = muxAudioIngestCount,
                muxDrainPacketCount = muxDrainPacketCount,
                srtSendAttemptCount = srtSendAttemptCount,
                srtSendSuccessCount = srtSendSuccessCount,
                srtSendFailureCount = srtSendFailureCount,
                cameraToEncoderLastLatencyUs = cameraToEncoderLastLatencyUs,
                encoderToMuxLastLatencyUs = encoderToMuxLastLatencyUs,
                muxToSrtLastLatencyUs = muxToSrtLastLatencyUs,
                cameraToEncoderMaxLatencyUs = cameraToEncoderMaxLatencyUs,
                encoderToMuxMaxLatencyUs = encoderToMuxMaxLatencyUs,
                muxToSrtMaxLatencyUs = muxToSrtMaxLatencyUs,
                cameraToEncoderQueueDepth = cameraToEncoderQueueDepth,
                encoderToMuxQueueDepth = encoderToMuxQueueDepth,
                muxToSrtQueueDepth = muxToSrtQueueDepth,
                cameraToEncoderDropCount = cameraToEncoderDropCount,
                encoderToMuxDropCount = encoderToMuxDropCount,
                muxToSrtDropCount = muxToSrtDropCount,
            )
        }
    }

    fun captureDiagnosticSnapshot(
        frameBudgetUs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): DiagnosticSnapshot {
        val data = snapshot()
        val originAndReason = determineOrigin(data = data, frameBudgetUs = frameBudgetUs)
        return DiagnosticSnapshot(
            capturedAtMs = nowMs,
            origin = originAndReason.first,
            reason = originAndReason.second,
            frameBudgetUs = frameBudgetUs,
            data = data,
        )
    }

    private fun determineOrigin(
        data: Snapshot,
        frameBudgetUs: Long,
    ): Pair<BackpressureOrigin, String> {
        if (data.cameraToEncoderDropCount > 0L) {
            return BackpressureOrigin.CameraToEncoder to "camera queue drops"
        }
        if (data.cameraToEncoderLastLatencyUs > frameBudgetUs) {
            return BackpressureOrigin.CameraToEncoder to "camera->encoder latency over budget"
        }

        if (data.encoderToMuxDropCount > 0L) {
            return BackpressureOrigin.EncoderToMux to "encoder queue drops"
        }
        if (data.encoderToMuxLastLatencyUs > frameBudgetUs) {
            return BackpressureOrigin.EncoderToMux to "encoder->mux latency over budget"
        }

        if (data.muxToSrtDropCount > 0L) {
            return BackpressureOrigin.MuxToSrt to "mux queue drops"
        }
        if (data.muxToSrtLastLatencyUs > frameBudgetUs) {
            return BackpressureOrigin.MuxToSrt to "mux->srt latency over budget"
        }

        return BackpressureOrigin.None to "within budget"
    }

    private fun elapsedUs(startNs: Long, endNs: Long): Long {
        if (startNs <= 0L || endNs <= startNs) {
            return 0L
        }
        return (endNs - startNs) / 1_000L
    }
}
