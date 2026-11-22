package com.pocketgammu

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.pocketgammu.ui.PocketGammuApp

class MainActivity : ComponentActivity() {
    private val container get() = (application as PocketGammuApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                PocketGammuApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Launched again by USB_DEVICE_ATTACHED: refresh the device list.
        container.usbScanner.refresh()
    }
}
