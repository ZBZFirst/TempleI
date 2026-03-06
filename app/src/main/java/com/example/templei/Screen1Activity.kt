package com.example.templei

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.templei.feature.camera.CameraFeature
import com.example.templei.ui.navigation.TopNavigation

/**
 * Screen 1 hosts the camera preview shell and controls for selecting/starting/stopping feed.
 */
class Screen1Activity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCameraPreview()
            } else {
                updateStatus(getString(R.string.camera_status_permission_required))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_screen1)
        TopNavigation.bind(activity = this, currentDestination = Screen1Activity::class.java)

        previewView = findViewById(R.id.cameraPreviewView)
        statusText = findViewById(R.id.cameraStatusText)
        startButton = findViewById(R.id.startCameraButton)
        stopButton = findViewById(R.id.stopCameraButton)

        findViewById<Button>(R.id.selectCameraButton).setOnClickListener { showCameraSelector() }
        startButton.setOnClickListener { ensurePermissionAndStartPreview() }
        stopButton.setOnClickListener {
            CameraFeature.stopPreview()
            updateStatus(getString(R.string.camera_status_stopped))
            syncButtonState()
        }

        syncButtonState()
    }

    override fun onStop() {
        super.onStop()
        CameraFeature.stopPreview()
        syncButtonState()
    }

    private fun showCameraSelector() {
        val currentSelection = CameraFeature.selectedLens()
        val lensOptions = arrayOf(
            CameraFeature.LensOption.BACK,
            CameraFeature.LensOption.FRONT,
        )
        val labels = lensOptions.map {
            when (it) {
                CameraFeature.LensOption.BACK -> getString(R.string.camera_selector_back)
                CameraFeature.LensOption.FRONT -> getString(R.string.camera_selector_front)
            }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.camera_selector_dialog_title)
            .setSingleChoiceItems(labels, lensOptions.indexOf(currentSelection)) { dialog, which ->
                CameraFeature.selectLens(lensOptions[which])
                dialog.dismiss()
                val label = labels[which]
                if (CameraFeature.isPreviewRunning()) {
                    ensurePermissionAndStartPreview()
                } else {
                    updateStatus(getString(R.string.camera_status_selected, label))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun ensurePermissionAndStartPreview() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraPreview()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCameraPreview() {
        CameraFeature.startPreview(
            context = this,
            lifecycleOwner = this,
            previewView = previewView,
            onStarted = {
                val labelRes = when (CameraFeature.selectedLens()) {
                    CameraFeature.LensOption.BACK -> R.string.camera_selector_back
                    CameraFeature.LensOption.FRONT -> R.string.camera_selector_front
                }
                updateStatus(getString(R.string.camera_status_running, getString(labelRes)))
                syncButtonState()
            },
            onUnavailable = {
                updateStatus(getString(R.string.camera_status_unavailable))
                syncButtonState()
            },
        )
    }

    private fun updateStatus(value: String) {
        statusText.text = value
    }

    private fun syncButtonState() {
        val running = CameraFeature.isPreviewRunning()
        startButton.isEnabled = !running
        stopButton.isEnabled = running
    }
}
