package com.example.templei

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import com.example.templei.ui.navigation.TopNavigation

/**
 * Shell host for Screen 3 XML.
 *
 * TODO: Wire dynamic grid actions to a screen-specific view model.
 */
class Screen3Activity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Shell-only binding to XML until feature logic is implemented.
        setContentView(R.layout.activity_screen3)
        TopNavigation.bind(activity = this, currentDestination = Screen3Activity::class.java)
    }
}
