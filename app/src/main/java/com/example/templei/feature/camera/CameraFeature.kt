package com.example.templei.feature.camera

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/**
 * Shared camera pipeline for preview, image capture, and video recording.
 *
 * TODO: Move callback-driven APIs to a dedicated ViewModel state flow when Screen 1 exits shell stage.
 */
object CameraFeature {
    enum class LensOption {
        BACK,
        FRONT,
    }

    private const val IMAGE_RELATIVE_PATH = "Pictures/TempleI"
    private const val VIDEO_RELATIVE_PATH = "Movies/TempleI"

    private var selectedLensOption: LensOption = LensOption.BACK
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var isBound = false
    private var isRecording = false

    fun selectedLens(): LensOption = selectedLensOption

    fun selectLens(option: LensOption) {
        selectedLensOption = option
    }

    fun hasCamera(context: Context, option: LensOption = selectedLensOption): Boolean {
        val provider = cameraProvider ?: ProcessCameraProvider.getInstance(context).get().also {
            cameraProvider = it
        }
        return provider.hasCamera(option.toSelector())
    }

    fun startPreview(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onStarted: () -> Unit,
        onUnavailable: () -> Unit,
    ) {
        val provider = cameraProvider ?: ProcessCameraProvider.getInstance(context).get().also {
            cameraProvider = it
        }

        if (!provider.hasCamera(selectedLensOption.toSelector())) {
            isBound = false
            onUnavailable()
            return
        }

        val previewUseCase = Preview.Builder().build().also { preview ->
            preview.setSurfaceProvider(previewView.surfaceProvider)
        }

        val imageCaptureUseCase = ImageCapture.Builder().build()
        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build()
        val videoCaptureUseCase = VideoCapture.withOutput(recorder)

        provider.unbindAll()
        provider.bindToLifecycle(
            lifecycleOwner,
            selectedLensOption.toSelector(),
            previewUseCase,
            imageCaptureUseCase,
            videoCaptureUseCase,
        )

        imageCapture = imageCaptureUseCase
        videoCapture = videoCaptureUseCase
        isBound = true
        onStarted()
    }

    fun stopPreview() {
        stopRecording()
        cameraProvider?.unbindAll()
        isBound = false
    }

    fun takePicture(
        context: Context,
        onSaved: (String) -> Unit,
        onError: () -> Unit,
    ) {
        val captureUseCase = imageCapture ?: run {
            onError()
            return
        }

        val displayName = "TempleI_IMG_${System.currentTimeMillis()}"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, IMAGE_RELATIVE_PATH)
        }

        val options = ImageCapture.OutputFileOptions
            .Builder(
                context.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values,
            )
            .build()

        captureUseCase.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onSaved(output.savedUri?.toString().orEmpty())
                }

                override fun onError(exception: ImageCaptureException) {
                    onError()
                }
            },
        )
    }

    fun startRecording(
        context: Context,
        onStarted: () -> Unit,
        onSaved: (String) -> Unit,
        onError: () -> Unit,
    ) {
        if (isRecording) {
            return
        }

        val captureUseCase = videoCapture ?: run {
            onError()
            return
        }

        val displayName = "TempleI_VID_${System.currentTimeMillis()}"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, VIDEO_RELATIVE_PATH)
        }

        val mediaStoreOutput = MediaStoreOutputOptions
            .Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()

        activeRecording = captureUseCase.output
            .prepareRecording(context, mediaStoreOutput)
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        onStarted()
                    }

                    is VideoRecordEvent.Finalize -> {
                        val uri = event.outputResults.outputUri
                        isRecording = false
                        activeRecording = null
                        if (!event.hasError()) {
                            onSaved(uri.toString())
                        } else {
                            onError()
                        }
                    }

                    else -> Unit
                }
            }
    }

    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
        isRecording = false
    }

    fun isPreviewRunning(): Boolean = isBound

    fun isVideoRecording(): Boolean = isRecording

    private fun LensOption.toSelector(): CameraSelector {
        return when (this) {
            LensOption.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
            LensOption.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
        }
    }
}
