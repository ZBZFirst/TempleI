package com.example.templei

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import com.example.templei.ui.navigation.TopNavigation

/**
 * Shell host for Screen 1 XML.
 *
 * TODO: Wire actual interactions and data once Screen 1 behavior is finalized.
 */
class Screen1Activity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Shell-only binding to XML until feature logic is implemented.
        setContentView(R.layout.activity_screen1)
        TopNavigation.bind(activity = this, currentDestination = Screen1Activity::class.java)
    }
}
