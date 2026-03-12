package com.example.templei.feature.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared camera pipeline for preview, image capture, and video recording.
 *
 * TODO: Move callback-driven APIs to a dedicated ViewModel state flow when Screen 1 exits shell stage.
 */
object CameraFeature {
    private const val TAG = "TempleI-CameraFeature"
    private enum class BindMode {
        None,
        Preview,
        Stream,
    }

    enum class LensOption {
        BACK,
        FRONT,
    }

    data class FramePacket(
        val width: Int,
        val height: Int,
        val timestampNs: Long,
        val i420Data: ByteArray,
    )

    private const val IMAGE_RELATIVE_PATH = "Pictures/TempleI"
    private const val VIDEO_RELATIVE_PATH = "Movies/TempleI"
    private const val STREAM_ANALYSIS_EXPECTED_WIDTH = 1280
    private const val STREAM_ANALYSIS_EXPECTED_HEIGHT = 720

    private var selectedLensOption: LensOption = LensOption.BACK
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var activeRecording: Recording? = null
    private var isBound = false
    private var isRecording = false
    private var frameOutputListener: ((FramePacket) -> Unit)? = null
    private var bindMode: BindMode = BindMode.None

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var unexpectedAnalysisFrameCount = 0L
    private var analysisFrameWidth = 0
    private var analysisFrameHeight = 0
    private var analysisFirstFrameLogged = false
    private val mainHandler = Handler(Looper.getMainLooper())

    fun selectedLens(): LensOption = selectedLensOption

    fun selectLens(option: LensOption) {
        selectedLensOption = option
    }

    fun setFrameOutputListener(listener: ((FramePacket) -> Unit)?) {
        frameOutputListener = listener
        Log.i(
            TAG,
            "tsMs=${System.currentTimeMillis()} milestone=frame-listener listener=${if (listener == null) "detached" else "attached"} bindMode=$bindMode",
        )
    }

    fun hasCamera(context: Context, option: LensOption = selectedLensOption): Boolean {
        val provider = cameraProvider ?: ProcessCameraProvider.getInstance(context).get().also {
            cameraProvider = it
        }
        return provider.hasCamera(option.toSelector())
    }

    fun startPreview(
        context: Context,
        previewView: PreviewView,
        onStarted: () -> Unit,
        onUnavailable: () -> Unit,
    ) {
        val provider = cameraProvider ?: ProcessCameraProvider.getInstance(context).get().also {
            cameraProvider = it
        }

        if (!provider.hasCamera(selectedLensOption.toSelector())) {
            isBound = false
            bindMode = BindMode.None
            unexpectedAnalysisFrameCount = 0
            analysisFrameWidth = 0
            analysisFrameHeight = 0
            analysisFirstFrameLogged = false
            onUnavailable()
            return
        }

        if (isBound && bindMode == BindMode.Preview) {
            previewUseCase?.setSurfaceProvider(previewView.surfaceProvider)
            Log.i(
                TAG,
                "tsMs=${System.currentTimeMillis()} milestone=bind skipped mode=preview reason=already-bound composition=preview+imageCapture",
            )
            onStarted()
            return
        }

        if (isBound && bindMode != BindMode.Preview) {
            Log.i(
                TAG,
                "tsMs=${System.currentTimeMillis()} milestone=rebind required targetMode=preview previousMode=$bindMode",
            )
        }

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val imageCaptureUseCase = ImageCapture.Builder().build()

        provider.unbindAll()
        Log.i(
            TAG,
            "tsMs=${System.currentTimeMillis()} milestone=unbind all reason=bind-preview",
        )
        unexpectedAnalysisFrameCount = 0
        analysisFrameWidth = 0
        analysisFrameHeight = 0
        analysisFirstFrameLogged = false
        provider.bindToLifecycle(
            CameraSessionLifecycleOwner,
            selectedLensOption.toSelector(),
            preview,
            imageCaptureUseCase,
        )

        previewUseCase = preview
        imageCapture = imageCaptureUseCase
        videoCapture = null
        imageAnalysis = null
        isBound = true
        bindMode = BindMode.Preview
        Log.i(
            TAG,
            "tsMs=${System.currentTimeMillis()} milestone=bind success mode=preview composition=preview+imageCapture expected=${STREAM_ANALYSIS_EXPECTED_WIDTH}x${STREAM_ANALYSIS_EXPECTED_HEIGHT}",
        )
        onStarted()
    }


    fun ensureCapturePipeline(context: Context, onUnavailable: () -> Unit = {}): Boolean {
        return runOnMainThreadBlocking {
            val provider = runCatching {
                cameraProvider ?: ProcessCameraProvider.getInstance(context).get().also {
                    cameraProvider = it
                }
            }.getOrElse { error ->
                Log.e(TAG, "capture pipeline init failed: ${error.message.orEmpty()}", error)
                isBound = false
                analysisFirstFrameLogged = false
                onUnavailable()
                return@runOnMainThreadBlocking false
            }

            if (!provider.hasCamera(selectedLensOption.toSelector())) {
                isBound = false
                bindMode = BindMode.None
                analysisFirstFrameLogged = false
                onUnavailable()
                return@runOnMainThreadBlocking false
            }

            if (isBound && bindMode == BindMode.Stream) {
                Log.i(
                    TAG,
                    "tsMs=${System.currentTimeMillis()} milestone=bind skipped mode=stream reason=already-bound composition=imageAnalysis",
                )
                return@runOnMainThreadBlocking true
            }

            if (isBound && bindMode != BindMode.Stream) {
                Log.i(
                    TAG,
                    "tsMs=${System.currentTimeMillis()} milestone=rebind required targetMode=stream previousMode=$bindMode",
                )
            }

            val imageAnalysisUseCase = ImageAnalysis.Builder()
                // Keep analyzer frames aligned with current encoder profile expectation (1280x720).
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            provider.unbindAll()
            Log.i(
                TAG,
                "tsMs=${System.currentTimeMillis()} milestone=unbind all reason=bind-stream",
            )
            unexpectedAnalysisFrameCount = 0
            analysisFrameWidth = 0
            analysisFrameHeight = 0
            analysisFirstFrameLogged = false
            provider.bindToLifecycle(
                CameraSessionLifecycleOwner,
                selectedLensOption.toSelector(),
                imageAnalysisUseCase,
            )
            imageAnalysisUseCase.setAnalyzer(analysisExecutor) { imageProxy ->
                handleAnalysisFrame(imageProxy)
            }
            Log.i(
                TAG,
                "tsMs=${System.currentTimeMillis()} milestone=analysis-analyzer-registered mode=stream",
            )

            previewUseCase = null
            imageCapture = null
            videoCapture = null
            imageAnalysis = imageAnalysisUseCase
            isBound = true
            bindMode = BindMode.Stream
            Log.i(
                TAG,
                "tsMs=${System.currentTimeMillis()} milestone=bind success mode=stream composition=imageAnalysis expected=${STREAM_ANALYSIS_EXPECTED_WIDTH}x${STREAM_ANALYSIS_EXPECTED_HEIGHT}",
            )
            true
        }
    }

    fun stopStreamCapturePipeline() {
        runOnMainThreadBlocking {
            if (!isBound || bindMode != BindMode.Stream) {
                return@runOnMainThreadBlocking
            }
            cameraProvider?.unbindAll()
            previewUseCase = null
            imageCapture = null
            videoCapture = null
            imageAnalysis = null
            isBound = false
            bindMode = BindMode.None
            unexpectedAnalysisFrameCount = 0
            analysisFrameWidth = 0
            analysisFrameHeight = 0
            analysisFirstFrameLogged = false
            Log.i(
                TAG,
                "tsMs=${System.currentTimeMillis()} milestone=unbind all reason=stop-stream-pipeline",
            )
        }
    }

    private fun <T> runOnMainThreadBlocking(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return action()
        }

        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<T>()
        val errorRef = AtomicReference<Throwable>()
        mainHandler.post {
            try {
                resultRef.set(action())
            } catch (throwable: Throwable) {
                errorRef.set(throwable)
            } finally {
                latch.countDown()
            }
        }
        val completed = latch.await(10, TimeUnit.SECONDS)
        if (!completed) {
            throw IllegalStateException("main-thread camera operation timed out")
        }
        errorRef.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return resultRef.get() as T
    }

    fun stopPreview() {
        stopRecording()
        cameraProvider?.unbindAll()
        previewUseCase = null
        imageCapture = null
        videoCapture = null
        imageAnalysis = null
        isBound = false
        bindMode = BindMode.None
        unexpectedAnalysisFrameCount = 0
        analysisFrameWidth = 0
        analysisFrameHeight = 0
        analysisFirstFrameLogged = false
        Log.i(
            TAG,
            "tsMs=${System.currentTimeMillis()} milestone=unbind all reason=stop-preview",
        )
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
        withAudio: Boolean,
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

        val pendingRecording = captureUseCase.output
            .prepareRecording(context, mediaStoreOutput)

        // Keep recording session alive across Activity switches while the app is in foreground.
        val persistentRecording = pendingRecording.asPersistentRecording()

        val configuredRecording = if (withAudio) {
            persistentRecording.withAudioEnabled()
        } else {
            persistentRecording
        }

        activeRecording = configuredRecording
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

    fun cameraDiagnostics(): CameraRuntimeDiagnostics {
        return CameraRuntimeDiagnostics(
            selectedLens = selectedLensOption,
            bindMode = bindMode.name,
            isBound = isBound,
            isRecording = isRecording,
        )
    }

    fun useCaseDiagnostics(): CameraUseCaseRuntimeDiagnostics {
        return CameraUseCaseRuntimeDiagnostics(
            previewAttached = previewUseCase != null,
            imageCaptureAttached = imageCapture != null,
            videoCaptureAttached = videoCapture != null,
            imageAnalysisAttached = imageAnalysis != null,
        )
    }

    fun analyzerDiagnostics(): AnalyzerRuntimeDiagnostics {
        return AnalyzerRuntimeDiagnostics(
            unexpectedAnalysisFrameCount = unexpectedAnalysisFrameCount,
            analysisFrameWidth = analysisFrameWidth,
            analysisFrameHeight = analysisFrameHeight,
            firstFrameLogged = analysisFirstFrameLogged,
            frameListenerAttached = frameOutputListener != null,
        )
    }

    private fun handleAnalysisFrame(imageProxy: ImageProxy) {
        val timestampNs = imageProxy.imageInfo.timestamp
        try {
            if (imageProxy.format != ImageFormat.YUV_420_888) {
                Log.w(TAG, "tsMs=${System.currentTimeMillis()} milestone=video frame dropped reason=analysis format=${imageProxy.format}")
                return
            }

            Log.d(TAG, "tsMs=${System.currentTimeMillis()} milestone=analysis frame received size=${imageProxy.width}x${imageProxy.height} ts=$timestampNs")
            if (!analysisFirstFrameLogged) {
                analysisFirstFrameLogged = true
                Log.i(
                    TAG,
                    "tsMs=${System.currentTimeMillis()} milestone=analysis-first-frame size=${imageProxy.width}x${imageProxy.height} ts=$timestampNs",
                )
            }

            if (analysisFrameWidth != imageProxy.width || analysisFrameHeight != imageProxy.height) {
                analysisFrameWidth = imageProxy.width
                analysisFrameHeight = imageProxy.height
                unexpectedAnalysisFrameCount += 1
                Log.w(
                    TAG,
                    "analysis frame size observed=${imageProxy.width}x${imageProxy.height} changeCount=$unexpectedAnalysisFrameCount",
                )
            }

            val listener = frameOutputListener ?: run {
                Log.w(TAG, "tsMs=${System.currentTimeMillis()} milestone=video frame dropped reason=frame listener missing")
                return
            }
            val i420 = imageProxy.toI420ByteArray() ?: run {
                Log.w(TAG, "tsMs=${System.currentTimeMillis()} milestone=video frame dropped reason=i420 conversion failed")
                return
            }
            listener(
                FramePacket(
                    width = imageProxy.width,
                    height = imageProxy.height,
                    timestampNs = imageProxy.imageInfo.timestamp,
                    i420Data = i420,
                ),
            )
            Log.d(
                TAG,
                "tsMs=${System.currentTimeMillis()} milestone=analysis-frame-delivered size=${imageProxy.width}x${imageProxy.height} ts=$timestampNs",
            )
        } catch (throwable: Throwable) {
            Log.e(TAG, "analysis frame handling failed: ${throwable.message.orEmpty()}", throwable)
        } finally {
            imageProxy.close()
            Log.d(TAG, "tsMs=${System.currentTimeMillis()} milestone=analysis frame closed ts=$timestampNs")
        }
    }

    private fun ImageProxy.toI420ByteArray(): ByteArray? {
        val yPlane = planes.getOrNull(0) ?: return null
        val uPlane = planes.getOrNull(1) ?: return null
        val vPlane = planes.getOrNull(2) ?: return null

        val width = width
        val height = height
        val ySize = width * height
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val chromaSize = chromaWidth * chromaHeight
        val output = ByteArray(ySize + chromaSize * 2)

        copyPlane(
            source = yPlane.buffer,
            sourceRowStride = yPlane.rowStride,
            sourcePixelStride = yPlane.pixelStride,
            width = width,
            height = height,
            destination = output,
            destinationOffset = 0,
            destinationPixelStride = 1,
            destinationRowStride = width,
        )

        copyPlane(
            source = uPlane.buffer,
            sourceRowStride = uPlane.rowStride,
            sourcePixelStride = uPlane.pixelStride,
            width = chromaWidth,
            height = chromaHeight,
            destination = output,
            destinationOffset = ySize,
            destinationPixelStride = 1,
            destinationRowStride = chromaWidth,
        )

        copyPlane(
            source = vPlane.buffer,
            sourceRowStride = vPlane.rowStride,
            sourcePixelStride = vPlane.pixelStride,
            width = chromaWidth,
            height = chromaHeight,
            destination = output,
            destinationOffset = ySize + chromaSize,
            destinationPixelStride = 1,
            destinationRowStride = chromaWidth,
        )

        return output
    }

    private fun copyPlane(
        source: ByteBuffer,
        sourceRowStride: Int,
        sourcePixelStride: Int,
        width: Int,
        height: Int,
        destination: ByteArray,
        destinationOffset: Int,
        destinationPixelStride: Int,
        destinationRowStride: Int,
    ) {
        val sourceBuffer = source.duplicate()
        for (row in 0 until height) {
            val sourceRowStart = row * sourceRowStride
            val destinationRowStart = destinationOffset + row * destinationRowStride
            for (col in 0 until width) {
                val sourceIndex = sourceRowStart + col * sourcePixelStride
                val destinationIndex = destinationRowStart + col * destinationPixelStride
                destination[destinationIndex] = sourceBuffer.get(sourceIndex)
            }
        }
    }

    /**
     * Lifecycle owner kept in RESUMED so camera can continue across Activity navigation.
     *
     * TODO: Move this to a foreground service session if capture must survive app backgrounding.
     */
    private object CameraSessionLifecycleOwner : LifecycleOwner {
        private val lifecycleRegistry = LifecycleRegistry(this).apply {
            currentState = Lifecycle.State.RESUMED
        }

        override val lifecycle: Lifecycle
            get() = lifecycleRegistry
    }

    private fun LensOption.toSelector(): CameraSelector {
        return when (this) {
            LensOption.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
            LensOption.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
        }
    }
}
