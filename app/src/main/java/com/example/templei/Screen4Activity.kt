package com.example.templei

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import com.example.templei.ui.navigation.TopNavigation

/**
 * Shell host for Screen 4 XML.
 *
 * TODO: Integrate form/table controls with real persistence.
 */
class Screen4Activity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Shell-only binding to XML until feature logic is implemented.
        setContentView(R.layout.activity_screen4)
        TopNavigation.bind(activity = this, currentDestination = Screen4Activity::class.java)
    }
}
