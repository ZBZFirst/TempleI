package com.example.templei

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.templei.ui.navigation.TopNavigation

/**
 * Entry screen shell that routes to Screens 1-4.
 *
 * NOTE: This currently only does basic navigation wiring while each destination is under development.
 */
class MainActivity : ComponentActivity() {
    private val startupPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Permission-sensitive features still perform local checks before use.
            // This startup request reduces first-use friction for camera/mic workflow.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Shared top navigation wiring for every XML shell screen.
        TopNavigation.bind(activity = this)
        // Keep existing main menu grid buttons functional via shared nav binder as well.
        TopNavigation.bindMainMenuGrid(activity = this)

        requestStartupPermissionsIfNeeded()
    }

    private fun requestStartupPermissionsIfNeeded() {
        val requiredPermissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
        val missingPermissions = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            startupPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }
}
