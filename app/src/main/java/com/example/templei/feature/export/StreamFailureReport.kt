package com.example.templei.feature.export

import com.example.templei.feature.camera.AnalyzerRuntimeDiagnostics
import com.example.templei.feature.camera.CameraFeature
import com.example.templei.feature.camera.CameraRuntimeDiagnostics
import com.example.templei.feature.camera.CameraUseCaseRuntimeDiagnostics

/** Structured diagnostics report for capture pipeline/session failures. */
data class StreamFailureReport(
    val reason: String,
    val camera: CameraRuntimeDiagnostics,
    val useCases: CameraUseCaseRuntimeDiagnostics,
    val analyzerState: AnalyzerRuntimeDiagnostics,
    val videoEncoder: VideoEncoderNode.EncoderDiagnostics,
    val audioEncoder: AudioEncoderNode.EncoderDiagnostics,
    val timestamps: Timestamps,
) {
    data class Timestamps(
        val createdAtMs: Long,
        val uptimeMs: Long,
    )

    companion object {
        fun capture(reason: String): StreamFailureReport {
            return StreamFailureReport(
                reason = reason,
                camera = CameraFeature.cameraDiagnostics(),
                useCases = CameraFeature.useCaseDiagnostics(),
                analyzerState = CameraFeature.analyzerDiagnostics(),
                videoEncoder = VideoEncoderNode.diagnostics(),
                audioEncoder = AudioEncoderNode.diagnostics(),
                timestamps = Timestamps(
                    createdAtMs = System.currentTimeMillis(),
                    uptimeMs = android.os.SystemClock.uptimeMillis(),
                ),
            )
        }
    }
}
