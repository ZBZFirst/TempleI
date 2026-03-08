package com.example.templei.feature.export

import android.content.Context
import android.util.Log
import com.example.templei.feature.camera.CameraFeature
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates Screen 2 capture-path readiness checks before transport start.
 *
 * TODO: Attach real camera/microphone outputs to encoder nodes once encode path is implemented.
 */
object CaptureCoordinator {
    private const val TAG = "TempleI-CaptureCoord"
    private const val CAMERA_TO_ENCODER_QUEUE_CAPACITY = 4
    private const val ENCODER_TO_MUX_QUEUE_CAPACITY = 32
    private const val LOG_FIRST_EVENTS = 5L
    private const val LOG_EVERY_N_EVENTS = 120L

    enum class StreamPathMode {
        FullAv,
        VideoOnly,
        AudioOnly,
    }

    data class StartResult(
        val isReady: Boolean,
        val error: String? = null,
    )

    private sealed interface MuxIngressItem {
        data class Video(val accessUnit: VideoEncoderNode.EncodedAccessUnit) : MuxIngressItem
        data class Audio(val accessUnit: AudioEncoderNode.EncodedAccessUnit) : MuxIngressItem
    }

    private val cameraQueueLock = Any()
    private val cameraToEncoderQueue = ArrayDeque<CameraFeature.FramePacket>()

    private val encoderQueueLock = Any()
    private val encoderToMuxQueue = ArrayDeque<MuxIngressItem>()



    // PR A instrumentation: boundary counters for ingress debugging.
    private val cameraEnqueueCount = AtomicLong(0)
    private val cameraDequeueCount = AtomicLong(0)
    private val cameraDropCount = AtomicLong(0)
    private val videoIngressCallCount = AtomicLong(0)
    private val audioIngressCallCount = AtomicLong(0)

    @Volatile
    private var relayLoopActive = false
    private var cameraRelayThread: Thread? = null
    private var encoderRelayThread: Thread? = null


    data class RuntimeStats(
        val cameraFramesEnqueued: Long,
        val cameraFramesDequeued: Long,
        val cameraFramesDropped: Long,
        val videoIngressCalls: Long,
        val audioIngressCalls: Long,
        val cameraQueueDepth: Int,
        val encoderQueueDepth: Int,
    )

    fun runtimeStats(): RuntimeStats {
        val cameraDepth: Int = synchronized(cameraQueueLock) { cameraToEncoderQueue.size }
        val encoderDepth: Int = synchronized(encoderQueueLock) { encoderToMuxQueue.size }
        return RuntimeStats(
            cameraFramesEnqueued = cameraEnqueueCount.get(),
            cameraFramesDequeued = cameraDequeueCount.get(),
            cameraFramesDropped = cameraDropCount.get(),
            videoIngressCalls = videoIngressCallCount.get(),
            audioIngressCalls = audioIngressCallCount.get(),
            cameraQueueDepth = cameraDepth,
            encoderQueueDepth = encoderDepth,
        )
    }

    fun startCapturePathSession(context: Context, config: ExportFeature.ObsStreamConfig): StartResult {
        val streamMode: StreamPathMode = config.streamMode
        if (config.host.isBlank()) {
            return StartResult(isReady = false, error = "host missing")
        }

        val captureReady = CameraFeature.ensureCapturePipeline(context) {
            Log.e(TAG, "camera capture pipeline unavailable on selected lens")
        }
        if (!captureReady) {
            return StartResult(isReady = false, error = "camera capture pipeline not running")
        }

        startRelayWorkers()

        val videoEncoderConfig: VideoEncoderNode.EncoderConfig = when (config.profile) {
            "Low Latency" -> VideoEncoderNode.EncoderConfig(
                width = 1280,
                height = 720,
                fps = 30,
                bitrate = 1_800_000,
            )

            else -> VideoEncoderNode.EncoderConfig(
                width = 1280,
                height = 720,
                fps = 30,
                bitrate = 2_500_000,
            )
        }

        val audioEncoderConfig: AudioEncoderNode.EncoderConfig = when (config.profile) {
            "Low Latency" -> AudioEncoderNode.EncoderConfig(
                sampleRate = 48_000,
                channelCount = 1,
                bitrate = 64_000,
            )

            else -> AudioEncoderNode.EncoderConfig(
                sampleRate = 48_000,
                channelCount = 1,
                bitrate = 96_000,
            )
        }

        val videoConfigured: Result<Unit> = VideoEncoderNode.configure(videoEncoderConfig)
        if (videoConfigured.isFailure) {
            stopRelayWorkers()
            return StartResult(isReady = false, error = VideoEncoderNode.error())
        }

        val audioConfigured: Result<Unit> = AudioEncoderNode.configure(audioEncoderConfig)
        if (audioConfigured.isFailure) {
            stopRelayWorkers()
            return StartResult(isReady = false, error = AudioEncoderNode.error())
        }

        if (streamMode != StreamPathMode.AudioOnly) {
            VideoEncoderNode.setOutputListener { accessUnit ->
                enqueueMuxIngress(MuxIngressItem.Video(accessUnit))
            }
            CameraFeature.setFrameOutputListener { frame ->
                StreamPipelineMetrics.recordCameraArrival()
                enqueueCameraFrame(frame)
            }
            val videoStarted: Result<Unit> = VideoEncoderNode.start()
            if (videoStarted.isFailure) {
                stopRelayWorkers()
                return StartResult(isReady = false, error = VideoEncoderNode.error())
            }
        } else {
            VideoEncoderNode.setOutputListener(null)
            CameraFeature.setFrameOutputListener(null)
        }

        if (streamMode != StreamPathMode.VideoOnly) {
            AudioEncoderNode.setOutputListener { accessUnit ->
                enqueueMuxIngress(MuxIngressItem.Audio(accessUnit))
            }
            val audioStarted: Result<Unit> = AudioEncoderNode.start()
            if (audioStarted.isFailure) {
                stopRelayWorkers()
                return StartResult(isReady = false, error = AudioEncoderNode.error())
            }
        } else {
            AudioEncoderNode.setOutputListener(null)
        }

        return StartResult(isReady = true)
    }

    fun stopCapturePathSession() {
        CameraFeature.setFrameOutputListener(null)
        VideoEncoderNode.setOutputListener(null)
        AudioEncoderNode.setOutputListener(null)
        stopRelayWorkers()
        VideoEncoderNode.stop()
        AudioEncoderNode.stop()
    }

    private fun enqueueCameraFrame(frame: CameraFeature.FramePacket) {
        synchronized(cameraQueueLock) {
            if (cameraToEncoderQueue.size >= CAMERA_TO_ENCODER_QUEUE_CAPACITY) {
                cameraToEncoderQueue.removeFirstOrNull()
                val dropCount = cameraDropCount.incrementAndGet()
                StreamPipelineMetrics.recordQueueDrop(cameraToEncoder = 1)
                if (shouldLogBoundaryEvent(dropCount)) {
                    logIngressSummary("camera-drop")
                }
            }
            cameraToEncoderQueue.addLast(frame)
            val enqueueCount = cameraEnqueueCount.incrementAndGet()
            if (shouldLogBoundaryEvent(enqueueCount)) {
                logIngressSummary("camera-enqueue")
            }
            updateQueueDepthMetrics()
        }
    }

    private fun enqueueMuxIngress(item: MuxIngressItem) {
        synchronized(encoderQueueLock) {
            if (encoderToMuxQueue.size >= ENCODER_TO_MUX_QUEUE_CAPACITY) {
                encoderToMuxQueue.removeFirstOrNull()
                StreamPipelineMetrics.recordQueueDrop(encoderToMux = 1)
            }
            encoderToMuxQueue.addLast(item)
            updateQueueDepthMetrics()
        }
    }

    private fun startRelayWorkers() {
        stopRelayWorkers()
        relayLoopActive = true

        cameraRelayThread = Thread {
            while (relayLoopActive) {
                val frame: CameraFeature.FramePacket? = synchronized(cameraQueueLock) {
                    cameraToEncoderQueue.removeFirstOrNull().also { updateQueueDepthMetrics() }
                }
                if (frame != null) {
                    val dequeueCount = cameraDequeueCount.incrementAndGet()
                    if (shouldLogBoundaryEvent(dequeueCount)) {
                        logIngressSummary("camera-dequeue")
                    }
                    VideoEncoderNode.queueFrame(frame)
                } else {
                    Thread.sleep(2L)
                }
            }
        }.apply {
            name = "TempleI-CameraRelay"
            start()
        }

        encoderRelayThread = Thread {
            while (relayLoopActive) {
                val item: MuxIngressItem? = synchronized(encoderQueueLock) {
                    encoderToMuxQueue.removeFirstOrNull().also { updateQueueDepthMetrics() }
                }
                when (item) {
                    is MuxIngressItem.Video -> {
                        StreamPipelineMetrics.recordMuxVideoIngest()
                        val ingressCount = videoIngressCallCount.incrementAndGet()
                        if (shouldLogBoundaryEvent(ingressCount)) {
                            Log.i(TAG, "backend-video-ingress calls=$ingressCount bytes=${item.accessUnit.data.size}")
                        }
                        val ingestResult: Result<Unit> = NativeStreamBackends.pushVideoAccessUnit(item.accessUnit)
                        if (ingestResult.isFailure) {
                            Log.e(TAG, "video->backend ingest failed: ${ingestResult.exceptionOrNull()?.message.orEmpty()}")
                        }
                    }

                    is MuxIngressItem.Audio -> {
                        StreamPipelineMetrics.recordMuxAudioIngest()
                        val ingressCount = audioIngressCallCount.incrementAndGet()
                        if (shouldLogBoundaryEvent(ingressCount)) {
                            Log.i(TAG, "backend-audio-ingress calls=$ingressCount bytes=${item.accessUnit.data.size}")
                        }
                        val ingestResult: Result<Unit> = NativeStreamBackends.pushAudioAccessUnit(item.accessUnit)
                        if (ingestResult.isFailure) {
                            Log.e(TAG, "audio->backend ingest failed: ${ingestResult.exceptionOrNull()?.message.orEmpty()}")
                        }
                    }

                    null -> Thread.sleep(2L)
                }
            }
        }.apply {
            name = "TempleI-EncoderRelay"
            start()
        }
    }

    private fun stopRelayWorkers() {
        relayLoopActive = false
        cameraRelayThread?.join(200L)
        encoderRelayThread?.join(200L)
        cameraRelayThread = null
        encoderRelayThread = null

        synchronized(cameraQueueLock) {
            cameraToEncoderQueue.clear()
        }
        cameraEnqueueCount.set(0)
        cameraDequeueCount.set(0)
        cameraDropCount.set(0)
        videoIngressCallCount.set(0)
        audioIngressCallCount.set(0)
        synchronized(encoderQueueLock) {
            encoderToMuxQueue.clear()
        }
        updateQueueDepthMetrics()
    }


    private fun logIngressSummary(trigger: String) {
        val stats = runtimeStats()
        Log.i(
            TAG,
            "ingress-summary trigger=$trigger cameraFramesEnqueued=${stats.cameraFramesEnqueued} " +
                "cameraFramesDequeued=${stats.cameraFramesDequeued} cameraFramesDropped=${stats.cameraFramesDropped} " +
                "videoIngressCalls=${stats.videoIngressCalls} audioIngressCalls=${stats.audioIngressCalls} " +
                "cameraDepth=${stats.cameraQueueDepth} encoderDepth=${stats.encoderQueueDepth}",
        )
    }

    private fun shouldLogBoundaryEvent(count: Long): Boolean {
        return count <= LOG_FIRST_EVENTS || count % LOG_EVERY_N_EVENTS == 0L
    }

    private fun updateQueueDepthMetrics() {
        val cameraDepth: Int = synchronized(cameraQueueLock) { cameraToEncoderQueue.size }
        val encoderDepth: Int = synchronized(encoderQueueLock) { encoderToMuxQueue.size }
        val muxDepth: Int = StreamPipelineMetrics.snapshot().muxToSrtQueueDepth
        StreamPipelineMetrics.updateQueueDepths(
            cameraToEncoder = cameraDepth,
            encoderToMux = encoderDepth,
            muxToSrt = muxDepth,
        )
    }
}
