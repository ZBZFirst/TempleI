package com.example.templei.feature.export

import com.example.templei.feature.camera.CameraFeature

/**
 * Coordinates Screen 2 capture-path readiness checks before transport start.
 *
 * TODO: Attach real camera/microphone outputs to encoder nodes once encode path is implemented.
 */
object CaptureCoordinator {
    data class StartResult(
        val isReady: Boolean,
        val error: String? = null,
    )

    fun startCapturePathSession(config: ExportFeature.ObsStreamConfig): StartResult {
        if (config.host.isBlank()) {
            return StartResult(isReady = false, error = "host missing")
        }

        if (!CameraFeature.isPreviewRunning()) {
            return StartResult(isReady = false, error = "camera preview not running")
        }

        val videoEncoderConfig = when (config.profile) {
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

        val audioEncoderConfig = when (config.profile) {
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

        val videoConfigured = VideoEncoderNode.configure(videoEncoderConfig)
        if (videoConfigured.isFailure) {
            return StartResult(isReady = false, error = VideoEncoderNode.error())
        }

        val audioConfigured = AudioEncoderNode.configure(audioEncoderConfig)
        if (audioConfigured.isFailure) {
            return StartResult(isReady = false, error = AudioEncoderNode.error())
        }

        VideoEncoderNode.setOutputListener { accessUnit ->
            TsMuxerNode.ingestVideo(accessUnit)
        }
        AudioEncoderNode.setOutputListener { accessUnit ->
            TsMuxerNode.ingestAudio(accessUnit)
        }
        CameraFeature.setFrameOutputListener { frame ->
            VideoEncoderNode.queueFrame(frame)
        }

        val videoStarted = VideoEncoderNode.start()
        if (videoStarted.isFailure) {
            return StartResult(isReady = false, error = VideoEncoderNode.error())
        }

        val audioStarted = AudioEncoderNode.start()
        if (audioStarted.isFailure) {
            return StartResult(isReady = false, error = AudioEncoderNode.error())
        }

        return StartResult(isReady = true)
    }

    fun stopCapturePathSession() {
        CameraFeature.setFrameOutputListener(null)
        VideoEncoderNode.setOutputListener(null)
        AudioEncoderNode.setOutputListener(null)
        VideoEncoderNode.stop()
        AudioEncoderNode.stop()
    }
}
