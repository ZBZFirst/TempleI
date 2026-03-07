package com.example.templei

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import android.app.AlertDialog
import com.example.templei.feature.export.ExportFeature
import com.example.templei.ui.navigation.TopNavigation

/**
 * Screen 2 hosts OBS-over-LAN endpoint setup and stream controls.
 *
 * TODO: Keep wiring localized to this screen while transport internals are integrated incrementally.
 */
class Screen2Activity : ComponentActivity() {
    private lateinit var obsSetupSummaryText: TextView
    private lateinit var sessionStateText: TextView
    private lateinit var validationResultText: TextView
    private lateinit var connectionResultText: TextView
    private lateinit var lastErrorText: TextView

    private var currentConfig = ExportFeature.ObsStreamConfig()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_screen2)
        TopNavigation.bind(activity = this, currentDestination = Screen2Activity::class.java)

        bindViews()
        bindButtons()
        currentConfig = ExportFeature.loadConfig(this)
        renderStatus()
    }

    private fun bindViews() {
        obsSetupSummaryText = findViewById(R.id.obsSetupSummaryText)
        sessionStateText = findViewById(R.id.sessionStateText)
        validationResultText = findViewById(R.id.validationResultText)
        connectionResultText = findViewById(R.id.connectionResultText)
        lastErrorText = findViewById(R.id.lastErrorText)
    }

    private fun bindButtons() {
        findViewById<Button>(R.id.defineEndpointButton).setOnClickListener {
            promptForHost()
        }
        findViewById<Button>(R.id.defineTransportButton).setOnClickListener {
            promptForPort()
        }
        findViewById<Button>(R.id.defineMuxingButton).setOnClickListener {
            val result = ExportFeature.validateConfig(currentConfig)
            ExportFeature.testEndpoint(currentConfig)
            if (result.isValid) {
                ExportFeature.saveConfig(this, currentConfig)
            }
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
            val nextProfile = ExportFeature.nextProfile(currentConfig.profile)
            currentConfig = currentConfig.copy(profile = nextProfile)
            ExportFeature.saveConfig(this, currentConfig)
            renderStatus()
        }
        findViewById<Button>(R.id.setupContractsButton).setOnClickListener {
            val result = ExportFeature.startStream(currentConfig)
            if (result.state == ExportFeature.SessionState.Streaming) {
                ExportFeature.saveConfig(this, currentConfig)
            }
            renderStatus()
        }
        findViewById<Button>(R.id.setupImplementationMapButton).setOnClickListener {
            ExportFeature.stopStream()
            renderStatus()
        }
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
        val sessionState = ExportFeature.currentState().name.lowercase()
        val validationMessage = ExportFeature.lastValidation()
        val connectionMessage = ExportFeature.lastConnectionTest()
        val errorText = ExportFeature.lastError().ifBlank { getString(R.string.obs_no_error) }

        obsSetupSummaryText.text = getString(
            R.string.obs_setup_summary_value,
            obsUrl,
            currentConfig.profile,
        )
        sessionStateText.text = getString(R.string.obs_session_state_value, sessionState)
        validationResultText.text = getString(R.string.obs_validation_value, validationMessage)
        connectionResultText.text = getString(R.string.obs_connection_value, connectionMessage)
        lastErrorText.text = getString(R.string.obs_last_error_value, errorText)
    }
}
