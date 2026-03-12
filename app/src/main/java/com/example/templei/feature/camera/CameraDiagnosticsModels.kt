package com.example.templei.feature.camera

data class CameraRuntimeDiagnostics(
    val selectedLens: CameraFeature.LensOption,
    val bindMode: String,
    val isBound: Boolean,
    val isRecording: Boolean,
)

data class CameraUseCaseRuntimeDiagnostics(
    val previewAttached: Boolean,
    val imageCaptureAttached: Boolean,
    val videoCaptureAttached: Boolean,
    val imageAnalysisAttached: Boolean,
)

data class AnalyzerRuntimeDiagnostics(
    val unexpectedAnalysisFrameCount: Long,
    val analysisFrameWidth: Int,
    val analysisFrameHeight: Int,
    val firstFrameLogged: Boolean,
    val frameListenerAttached: Boolean,
)
