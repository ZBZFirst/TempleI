package com.example.templei

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import com.example.templei.ui.navigation.TopNavigation

/**
 * Shell host for Screen 2 XML.
 *
 * TODO: Hook up button handlers and section state.
 */
class Screen2Activity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Shell-only binding to XML until feature logic is implemented.
        setContentView(R.layout.activity_screen2)
        TopNavigation.bind(activity = this, currentDestination = Screen2Activity::class.java)
    }
}
