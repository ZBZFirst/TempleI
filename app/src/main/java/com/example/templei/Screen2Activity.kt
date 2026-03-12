package com.example.templei

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import java.io.File
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.text.InputType
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import com.example.templei.feature.export.CaptureCoordinator
import com.example.templei.feature.export.ExportFeature
import com.example.templei.feature.export.StreamSessionService
import com.example.templei.ui.navigation.TopNavigation

/**
 * Screen 2 hosts OBS-over-LAN endpoint setup and stream controls.
 *
 * TODO: Keep wiring localized to this screen while transport internals are integrated incrementally.
 */
class Screen2Activity : ComponentActivity() {
    private companion object {
        private const val UI_LOG_TAG = "TempleI-UI"
    }
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
    private lateinit var editHostButton: Button
    private lateinit var editPortButton: Button
    private lateinit var validateEndpointButton: Button
    private lateinit var resetPresetButton: Button
    private lateinit var copyDiagnosticsButton: Button

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_screen2_host)
        TopNavigation.bind(activity = this, currentDestination = Screen2Activity::class.java)

        bindViews()
        bindButtons()
        currentConfig = ExportFeature.loadConfig(this)
        renderStatus()
    }

    override fun onStart() {
        super.onStart()
        bindStreamService()
    }

    override fun onStop() {
        super.onStop()
        unbindStreamService()
    }

    private fun bindViews() {
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
        editHostButton = findViewById(R.id.defineEndpointButton)
        editPortButton = findViewById(R.id.defineTransportButton)
        validateEndpointButton = findViewById(R.id.defineMuxingButton)
        resetPresetButton = findViewById(R.id.defineRecoveryButton)
        copyDiagnosticsButton = findViewById(R.id.copyDiagnosticsButton)
    }

    private fun bindButtons() {
        editHostButton.setOnClickListener {
            promptForHost()
        }
        editPortButton.setOnClickListener {
            promptForPort()
        }
        validateEndpointButton.setOnClickListener {
            if (currentConfig.host.isBlank()) {
                promptForHost()
                return@setOnClickListener
            }

            val result = ExportFeature.validateConfig(currentConfig)
            val endpointMessage = ExportFeature.testEndpoint(currentConfig)
            if (result.isValid) {
                ExportFeature.saveConfig(this, currentConfig)
            }
            if (endpointMessage.startsWith("preflight failed:")) {
                showBlockingEndpointError(endpointMessage)
            }
            renderStatus()
        }
        resetPresetButton.setOnClickListener {
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

            Log.i(UI_LOG_TAG, "milestone=start button pressed")
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
            Log.i(UI_LOG_TAG, "milestone=stop button pressed")
            streamSessionBinder?.stopSession() ?: ExportFeature.stopStream()
            isStartInFlight = false
            appendStartupPhase(getString(R.string.obs_startup_phase_stopped))
            renderStatus()
        }
        copyDiagnosticsButton.setOnClickListener {
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

    private fun bindStreamService() {
        if (isServiceBound) {
            return
        }

        val intent = Intent(this, StreamSessionService::class.java)
        startService(intent)
        bindService(intent, streamServiceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindStreamService() {
        if (!isServiceBound) {
            return
        }

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
        val message = getString(R.string.obs_input_dialog_message, obsUrl)
        AlertDialog.Builder(this)
            .setTitle(R.string.obs_input_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.obs_ok_action, null)
            .show()
    }

    private fun renderStatus() {
        val obsUrl = ExportFeature.buildObsUrl(currentConfig)
        val endpointSnapshot = ExportFeature.endpointValidationSnapshot(currentConfig)
        val sessionState = ExportFeature.currentState().name.lowercase()
        val validationMessage = ExportFeature.lastValidation()
        val connectionMessage = ExportFeature.lastConnectionTest()
        val errorText = ExportFeature.lastError().ifBlank { getString(R.string.obs_no_error) }
        val interopText = ExportFeature.interoperabilityStatus(currentConfig)
        val runtimeHealth = ExportFeature.runtimeHealthSnapshot()
        val currentState = ExportFeature.currentState()

        obsSetupSummaryText.text = getString(
            R.string.obs_setup_summary_value,
            obsUrl,
            ExportFeature.streamModeLabel(currentConfig.streamMode),
        )
        toggleStreamPathButton.text = when (currentConfig.streamMode) {
            CaptureCoordinator.StreamPathMode.ConnectionOnly -> "Toggle to Audio or Both"
            CaptureCoordinator.StreamPathMode.VideoOnly -> "Toggle to Audio or Both"
            CaptureCoordinator.StreamPathMode.AudioOnly -> "Toggle to Both or Video"
            CaptureCoordinator.StreamPathMode.FullAv -> "Toggle to Video or Audio"
        }
        sessionStateText.text = getString(R.string.obs_session_state_value, sessionState)
        startStreamButton.isEnabled = !isStartInFlight &&
            currentState != ExportFeature.SessionState.Starting &&
            currentState != ExportFeature.SessionState.Streaming

        val configEditable = currentState != ExportFeature.SessionState.Starting &&
            currentState != ExportFeature.SessionState.Streaming &&
            currentState != ExportFeature.SessionState.Stopping
        editHostButton.isEnabled = configEditable
        editPortButton.isEnabled = configEditable
        validateEndpointButton.isEnabled = configEditable
        resetPresetButton.isEnabled = configEditable
        toggleStreamPathButton.isEnabled = configEditable
        validationResultText.text = getString(R.string.obs_validation_value, validationMessage)
        connectionResultText.text = getString(R.string.obs_connection_value, connectionMessage)
        lastErrorText.text = getString(R.string.obs_last_error_value, errorText)
        interopStatusText.text = getString(R.string.obs_interop_value, interopText)
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

}
