package com.example.templei.ui.navigation

import android.app.Activity
import android.content.Intent
import android.widget.Button
import com.example.templei.R
import com.example.templei.Screen1Activity
import com.example.templei.Screen2Activity
import com.example.templei.Screen3Activity
import com.example.templei.Screen4Activity

/**
 * Shared top-nav wiring for XML screens.
 *
 * Define nav button visuals in `view_top_navigation.xml`; define click behavior once here.
 */
object TopNavigation {
    private val destinations = listOf(
        R.id.navButtonScreen1 to Screen1Activity::class.java,
        R.id.navButtonScreen2 to Screen2Activity::class.java,
        R.id.navButtonScreen3 to Screen3Activity::class.java,
        R.id.navButtonScreen4 to Screen4Activity::class.java
    )

    fun bind(activity: Activity, currentDestination: Class<out Activity>? = null) {
        destinations.forEach { (buttonId, targetDestination) ->
            activity.findViewById<Button?>(buttonId)?.apply {
                isEnabled = currentDestination != targetDestination
                setOnClickListener {
                    if (currentDestination != targetDestination) {
                        activity.startActivity(Intent(activity, targetDestination))
                    }
                }
            }
        }
    }

    fun bindMainMenuGrid(activity: Activity) {
        val mainMenuButtons = listOf(
            R.id.screen1Button to Screen1Activity::class.java,
            R.id.screen2Button to Screen2Activity::class.java,
            R.id.screen3Button to Screen3Activity::class.java,
            R.id.screen4Button to Screen4Activity::class.java
        )

        mainMenuButtons.forEach { (buttonId, targetDestination) ->
            activity.findViewById<Button?>(buttonId)?.setOnClickListener {
                activity.startActivity(Intent(activity, targetDestination))
            }
        }
    }

}
