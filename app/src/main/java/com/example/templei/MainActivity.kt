package com.example.templei

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import com.example.templei.ui.navigation.TopNavigation

/**
 * Entry screen shell that routes to Screens 1-4.
 *
 * NOTE: This currently only does basic navigation wiring while each destination is under development.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Shared top navigation wiring for every XML shell screen.
        TopNavigation.bind(activity = this)
        // Keep existing main menu grid buttons functional via shared nav binder as well.
        TopNavigation.bindMainMenuGrid(activity = this)
    }
}
