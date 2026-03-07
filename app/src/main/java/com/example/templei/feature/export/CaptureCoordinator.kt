package com.example.templei.feature.export

import com.example.templei.feature.camera.CameraFeature

/**
 * Coordinates Screen 2 capture-path readiness checks before transport start.
 *
 * TODO: Attach real camera output surfaces/streams to `VideoEncoderNode` once encoder path is implemented.
 */
object CaptureCoordinator {
    data class StartResult(
        val isReady: Boolean,
        val error: String? = null,
    )

    fun startVideoPathSession(config: ExportFeature.ObsStreamConfig): StartResult {
        if (config.host.isBlank()) {
            return StartResult(isReady = false, error = "host missing")
        }

        if (!CameraFeature.isPreviewRunning()) {
            return StartResult(isReady = false, error = "camera preview not running")
        }

        val encoderConfig = when (config.profile) {
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

        val configured = VideoEncoderNode.configure(encoderConfig)
        if (configured.isFailure) {
            return StartResult(isReady = false, error = VideoEncoderNode.error())
        }

        val started = VideoEncoderNode.start()
        if (started.isFailure) {
            return StartResult(isReady = false, error = VideoEncoderNode.error())
        }

        return StartResult(isReady = true)
    }

    fun stopVideoPathSession() {
        VideoEncoderNode.stop()
    }
}
