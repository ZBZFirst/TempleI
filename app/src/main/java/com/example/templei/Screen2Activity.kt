package com.example.templei

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import com.example.templei.ui.navigation.TopNavigation

/**
 * Screen 2 hosts the OBS-over-LAN streaming planning workspace.
 *
 * The current implementation is intentionally UI-first and keeps Screen 1 capture behavior untouched.
 * TODO: Wire these controls to a StreamService/CaptureCoordinator in a follow-up iteration.
 */
class Screen2Activity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_screen2)
        TopNavigation.bind(activity = this, currentDestination = Screen2Activity::class.java)
    }
}
