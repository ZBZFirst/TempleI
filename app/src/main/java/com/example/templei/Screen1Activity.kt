package com.example.templei

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.templei.feature.camera.CameraFeature
import com.example.templei.feature.export.CaptureCoordinator
import com.example.templei.feature.export.ExportFeature
import com.example.templei.feature.export.StreamSessionService
import com.example.templei.ui.navigation.TopNavigation
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Screen1Activity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var pictureButton: Button
    private lateinit var recordButton: Button

    private lateinit var obsSetupSummaryText: TextView
    private lateinit var sessionStateText: TextView
    private lateinit var validationResultText: TextView
    private lateinit var connectionResultText: TextView
    private lateinit var lastErrorText: TextView
    private lateinit var interopStatusText: TextView
    private lateinit var runtimeHealthText: TextView
    private lateinit var lastEffectiveUrlText: TextView
    private lateinit var startupProgressText: TextView
    private lateinit var toggleStreamPathButton: Button
    private lateinit var startStreamButton: Button

    private var currentConfig = ExportFeature.ObsStreamConfig()
    private var isStartInFlight = false
    private val startupPhaseLines = mutableListOf<String>()
    private var streamSessionBinder: StreamSessionService.LocalBinder? = null
    private var isServiceBound = false

    private val streamServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            streamSessionBinder = service as? StreamSessionService.LocalBinder
            isServiceBound = streamSessionBinder != null
            renderStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            streamSessionBinder = null
            isServiceBound = false
            renderStatus()
        }
    }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCameraPreview() else updateStatus(getString(R.string.camera_status_permission_required))
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

        bindCameraViews()
        bindObsViews()
        bindCameraButtons()
        bindObsButtons()

        currentConfig = ExportFeature.loadConfig(this)
        renderStatus()
        syncButtonState()
    }

    override fun onStart() {
        super.onStart()
        if (CameraFeature.isPreviewRunning()) startCameraPreview()
        bindStreamService()
        syncButtonState()
    }

    override fun onStop() {
        super.onStop()
        unbindStreamService()
    }

    private fun bindCameraViews() {
        previewView = findViewById(R.id.cameraPreviewView)
        statusText = findViewById(R.id.cameraStatusText)
        startButton = findViewById(R.id.startCameraButton)
        stopButton = findViewById(R.id.stopCameraButton)
        pictureButton = findViewById(R.id.takePictureButton)
        recordButton = findViewById(R.id.recordVideoButton)
    }

    private fun bindObsViews() {
        obsSetupSummaryText = findViewById(R.id.obsSetupSummaryText)
        sessionStateText = findViewById(R.id.sessionStateText)
        validationResultText = findViewById(R.id.validationResultText)
        connectionResultText = findViewById(R.id.connectionResultText)
        lastErrorText = findViewById(R.id.lastErrorText)
        interopStatusText = findViewById(R.id.interopStatusText)
        runtimeHealthText = findViewById(R.id.runtimeHealthText)
        lastEffectiveUrlText = findViewById(R.id.lastEffectiveUrlText)
        startupProgressText = findViewById(R.id.startupProgressText)
        toggleStreamPathButton = findViewById(R.id.setupFailureDomainsButton)
        startStreamButton = findViewById(R.id.setupContractsButton)
    }

    private fun bindCameraButtons() {
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
                onSaved = { uri -> updateStatus(getString(R.string.camera_picture_saved, uri)) },
                onError = { updateStatus(getString(R.string.camera_capture_error)) },
            )
        }
        recordButton.setOnClickListener { ensureCameraAndMicAndRecord() }
    }

    private fun bindObsButtons() {
        findViewById<Button>(R.id.defineEndpointButton).setOnClickListener { promptForHost() }
        findViewById<Button>(R.id.defineTransportButton).setOnClickListener { promptForPort() }
        findViewById<Button>(R.id.defineMuxingButton).setOnClickListener {
            if (currentConfig.host.isBlank()) {
                promptForHost(); return@setOnClickListener
            }
            val result = ExportFeature.validateConfig(currentConfig)
            val endpointMessage = ExportFeature.testEndpoint(currentConfig)
            if (result.isValid) ExportFeature.saveConfig(this, currentConfig)
            if (endpointMessage.startsWith("preflight failed:")) showBlockingEndpointError(endpointMessage)
            renderStatus()
        }
        findViewById<Button>(R.id.defineRecoveryButton).setOnClickListener {
            currentConfig = ExportFeature.resetConfig(this)
            renderStatus()
        }
        findViewById<Button>(R.id.setupStateMachineButton).setOnClickListener {
            renderStatus()
            showObsInputDialog()
        }
        findViewById<Button>(R.id.setupFailureDomainsButton).setOnClickListener {
            val nextMode = ExportFeature.nextStreamMode(currentConfig.streamMode)
            currentConfig = currentConfig.copy(streamMode = nextMode)
            ExportFeature.saveConfig(this, currentConfig)
            renderStatus()
        }
        findViewById<Button>(R.id.setupContractsButton).setOnClickListener {
            if (currentConfig.host.isBlank()) {
                promptForHost()
                return@setOnClickListener
            }

            startupPhaseLines.clear()
            appendStartupPhase(getString(R.string.obs_startup_phase_start_requested))
            isStartInFlight = true
            appendStartupPhase(getString(R.string.obs_startup_phase_starting))
            renderStatus()

            val binder = streamSessionBinder
            val result = if (binder != null) {
                binder.startSession(currentConfig)
            } else {
                ExportFeature.markFault(getString(R.string.obs_service_unavailable))
            }

            isStartInFlight = false
            appendStartupPhase(getString(R.string.obs_startup_phase_result, result.state.name.lowercase()))

            if (result.state == ExportFeature.SessionState.Streaming) {
                ExportFeature.saveConfig(this, currentConfig)
            } else if (result.state == ExportFeature.SessionState.Faulted) {
                val faultMessage = result.error ?: getString(R.string.obs_endpoint_malformed_generic)
                appendStartupPhase(faultMessage)
                if (faultMessage.startsWith("preflight failed:")) {
                    showBlockingEndpointError(faultMessage)
                } else {
                    showStartFailureError(faultMessage)
                }
            }
            renderStatus()
        }
        findViewById<Button>(R.id.setupImplementationMapButton).setOnClickListener {
            streamSessionBinder?.stopSession() ?: ExportFeature.stopStream()
            isStartInFlight = false
            appendStartupPhase(getString(R.string.obs_startup_phase_stopped))
            renderStatus()
        }
        findViewById<Button>(R.id.copyDiagnosticsButton).setOnClickListener {
            val snapshot = ExportFeature.createDiagnosticsSnapshot(currentConfig)
            val diagnosticsDir = File(filesDir, "diagnostics").apply { mkdirs() }
            val outputFile = File(diagnosticsDir, "startup-${snapshot.runId}.log")
            outputFile.writeText(snapshot.content)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("TempleI diagnostics", snapshot.content))
            appendStartupPhase(getString(R.string.obs_diagnostics_snapshot_copied, snapshot.runId, outputFile.absolutePath))
            renderStatus()
        }
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
            requestCameraAndMicPermission.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            return
        }

        ensurePermissionAndStartPreview()
        startRecordingWithAudio()
    }

    private fun startRecordingWithAudio() {
        CameraFeature.startRecording(
            context = this,
            withAudio = true,
            onStarted = { updateStatus(getString(R.string.camera_recording_started)); syncButtonState() },
            onSaved = { uri -> updateStatus(getString(R.string.camera_video_saved, uri)); syncButtonState() },
            onError = { updateStatus(getString(R.string.camera_capture_error)); syncButtonState() },
        )
    }

    private fun showCameraSelector() {
        val currentSelection = CameraFeature.selectedLens()
        val lensOptions = arrayOf(CameraFeature.LensOption.BACK, CameraFeature.LensOption.FRONT)
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
                if (CameraFeature.isPreviewRunning()) startCameraPreview() else updateStatus(getString(R.string.camera_status_selected, labels[which]))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun ensurePermissionAndStartPreview() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (!CameraFeature.isPreviewRunning()) startCameraPreview()
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

    private fun bindStreamService() {
        if (isServiceBound) return
        val intent = Intent(this, StreamSessionService::class.java)
        startService(intent)
        bindService(intent, streamServiceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindStreamService() {
        if (!isServiceBound) return
        unbindService(streamServiceConnection)
        streamSessionBinder = null
        isServiceBound = false
    }

    private fun promptForHost() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(currentConfig.host)
            hint = getString(R.string.obs_host_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.obs_edit_host_title)
            .setView(input)
            .setPositiveButton(R.string.obs_save_action) { _, _ ->
                currentConfig = currentConfig.copy(host = input.text.toString().trim())
                ExportFeature.saveConfig(this, currentConfig)
                renderStatus()
            }
            .setNegativeButton(R.string.obs_cancel_action, null)
            .show()
    }

    private fun promptForPort() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentConfig.port.toString())
            hint = getString(R.string.obs_port_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.obs_edit_port_title)
            .setView(input)
            .setPositiveButton(R.string.obs_save_action) { _, _ ->
                val parsedPort = input.text.toString().toIntOrNull() ?: currentConfig.port
                currentConfig = currentConfig.copy(port = parsedPort)
                ExportFeature.saveConfig(this, currentConfig)
                renderStatus()
            }
            .setNegativeButton(R.string.obs_cancel_action, null)
            .show()
    }

    private fun showObsInputDialog() {
        val obsUrl = ExportFeature.buildObsUrl(currentConfig)
        AlertDialog.Builder(this)
            .setTitle(R.string.obs_input_dialog_title)
            .setMessage(getString(R.string.obs_input_dialog_message, obsUrl))
            .setPositiveButton(R.string.obs_ok_action, null)
            .show()
    }

    private fun renderStatus() {
        val obsUrl = ExportFeature.buildObsUrl(currentConfig)
        val endpointSnapshot = ExportFeature.endpointValidationSnapshot(currentConfig)
        val sessionState = ExportFeature.currentState().name.lowercase()
        val runtimeHealth = ExportFeature.runtimeHealthSnapshot()
        val currentState = ExportFeature.currentState()

        obsSetupSummaryText.text = getString(
            R.string.obs_setup_summary_value,
            obsUrl,
            ExportFeature.streamModeLabel(currentConfig.streamMode),
        )
        toggleStreamPathButton.text = when (currentConfig.streamMode) {
            CaptureCoordinator.StreamPathMode.ConnectionOnly -> "Toggle to Video or Audio or Both"
            CaptureCoordinator.StreamPathMode.VideoOnly -> "Toggle to Audio or Both or Connection Only"
            CaptureCoordinator.StreamPathMode.AudioOnly -> "Toggle to Both or Connection Only or Video"
            CaptureCoordinator.StreamPathMode.FullAv -> "Toggle to Connection Only or Video or Audio"
        }
        sessionStateText.text = getString(R.string.obs_session_state_value, sessionState)
        startStreamButton.isEnabled = !isStartInFlight &&
            currentState != ExportFeature.SessionState.Starting &&
            currentState != ExportFeature.SessionState.Streaming
        validationResultText.text = getString(R.string.obs_validation_value, ExportFeature.lastValidation())
        connectionResultText.text = getString(R.string.obs_connection_value, ExportFeature.lastConnectionTest())
        lastErrorText.text = getString(R.string.obs_last_error_value, ExportFeature.lastError().ifBlank { getString(R.string.obs_no_error) })
        interopStatusText.text = getString(R.string.obs_interop_value, ExportFeature.interoperabilityStatus(currentConfig))
        runtimeHealthText.text = getString(
            R.string.obs_runtime_health_value,
            runtimeHealth.runtimeMode,
            runtimeHealth.connectionState,
            runtimeHealth.packetsWritten,
            runtimeHealth.lastNativeError,
        )
        lastEffectiveUrlText.text = getString(
            R.string.obs_last_effective_url_value,
            ExportFeature.lastEffectiveTransportUrl(),
            endpointSnapshot.transportCallerUrl,
        )
        startupProgressText.text = getString(
            R.string.obs_startup_progress_value,
            startupPhaseLines.joinToString("\n").ifBlank { getString(R.string.obs_startup_progress_idle) },
        )
    }

    private fun appendStartupPhase(phase: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        startupPhaseLines += "[$timestamp] $phase"
    }

    private fun showBlockingEndpointError(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.obs_endpoint_malformed_title)
            .setMessage(message)
            .setPositiveButton(R.string.obs_ok_action, null)
            .show()
    }

    private fun showStartFailureError(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.obs_start_failure_title)
            .setMessage(message)
            .setPositiveButton(R.string.obs_ok_action, null)
            .show()
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
        recordButton.text = if (recording) getString(R.string.camera_stop_record_button) else getString(R.string.camera_record_button)
    }
}
