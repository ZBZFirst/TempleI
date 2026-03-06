package com.example.templei.feature.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner

/**
 * Simple app-level camera controller used by multiple screens.
 *
 * It owns the active selector and bind/unbind lifecycle so screens can share one camera policy.
 */
object CameraFeature {
    enum class LensOption {
        BACK,
        FRONT,
    }

    private var selectedLensOption: LensOption = LensOption.BACK
    private var cameraProvider: ProcessCameraProvider? = null
    private var isBound = false

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
        context: Context,
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
            preview.surfaceProvider = previewView.surfaceProvider
        }

        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, selectedLensOption.toSelector(), previewUseCase)
        isBound = true
        onStarted()
    }

    fun stopPreview() {
        cameraProvider?.unbindAll()
        isBound = false
    }

    fun isPreviewRunning(): Boolean = isBound

    private fun LensOption.toSelector(): CameraSelector {
        return when (this) {
            LensOption.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
            LensOption.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
        }
    }
}
