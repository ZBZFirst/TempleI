package com.example.templei

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
    private lateinit var pictureButton: Button
    private lateinit var recordButton: Button

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCameraPreview()
            } else {
                updateStatus(getString(R.string.camera_status_permission_required))
            }
        }

    private val requestCameraAndMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val cameraGranted = grants[Manifest.permission.CAMERA] == true
            val micGranted = grants[Manifest.permission.RECORD_AUDIO] == true

            if (!cameraGranted) {
                updateStatus(getString(R.string.camera_status_permission_required))
                return@registerForActivityResult
            }

            if (!micGranted) {
                updateStatus(getString(R.string.camera_status_microphone_required))
                return@registerForActivityResult
            }

            ensurePermissionAndStartPreview()
            startRecordingWithAudio()
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
        pictureButton = findViewById(R.id.takePictureButton)
        recordButton = findViewById(R.id.recordVideoButton)

        findViewById<Button>(R.id.selectCameraButton).setOnClickListener { showCameraSelector() }
        startButton.setOnClickListener { ensurePermissionAndStartPreview() }
        stopButton.setOnClickListener {
            CameraFeature.stopPreview()
            updateStatus(getString(R.string.camera_status_stopped))
            syncButtonState()
        }
        pictureButton.setOnClickListener {
            ensurePermissionAndStartPreview()
            CameraFeature.takePicture(
                context = this,
                onSaved = { uri ->
                    updateStatus(getString(R.string.camera_picture_saved, uri))
                },
                onError = {
                    updateStatus(getString(R.string.camera_capture_error))
                },
            )
        }
        recordButton.setOnClickListener {
            ensureCameraAndMicAndRecord()
        }

        syncButtonState()
    }

    override fun onStart() {
        super.onStart()
        // Reattach UI preview surface while keeping camera session alive across screens.
        if (CameraFeature.isPreviewRunning()) {
            startCameraPreview()
        }
        syncButtonState()
    }

    private fun ensureCameraAndMicAndRecord() {
        if (CameraFeature.isVideoRecording()) {
            CameraFeature.stopRecording()
            updateStatus(getString(R.string.camera_recording_stopping))
            syncButtonState()
            return
        }

        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (!cameraGranted || !micGranted) {
            requestCameraAndMicPermission.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                ),
            )
            return
        }

        ensurePermissionAndStartPreview()
        startRecordingWithAudio()
    }

    private fun startRecordingWithAudio() {
        CameraFeature.startRecording(
            context = this,
            withAudio = true,
            onStarted = {
                updateStatus(getString(R.string.camera_recording_started))
                syncButtonState()
            },
            onSaved = { uri ->
                updateStatus(getString(R.string.camera_video_saved, uri))
                syncButtonState()
            },
            onError = {
                updateStatus(getString(R.string.camera_capture_error))
                syncButtonState()
            },
        )
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
                    startCameraPreview()
                } else {
                    updateStatus(getString(R.string.camera_status_selected, label))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun ensurePermissionAndStartPreview() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (!CameraFeature.isPreviewRunning()) {
                startCameraPreview()
            }
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCameraPreview() {
        CameraFeature.startPreview(
            context = this,
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
        val recording = CameraFeature.isVideoRecording()

        startButton.isEnabled = !running
        stopButton.isEnabled = running && !recording
        pictureButton.isEnabled = running && !recording

        recordButton.isEnabled = running
        recordButton.text = if (recording) {
            getString(R.string.camera_stop_record_button)
        } else {
            getString(R.string.camera_record_button)
        }
    }
}
