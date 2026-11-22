package com.pocketgammu

import android.app.Application
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.pocketgammu.core.data.SettingsRepository
import com.pocketgammu.core.gammu.PhoneRepository
import com.pocketgammu.core.gammu.usb.UsbDeviceScanner
import com.pocketgammu.fonts.FontStore
import com.pocketgammu.sms.SckSmsSender

/**
 * Manual dependency container. Small enough not to need Hilt; every screen
 * gets its dependencies from here through its ViewModel factory.
 */
class AppContainer(context: Context) {
    val settings = SettingsRepository(context)
    val usbScanner = UsbDeviceScanner(context)
    val phone = PhoneRepository(usbScanner)
    val smsSender = SckSmsSender(context)
    val fonts = FontStore(context)
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

class PocketGammuApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.usbScanner.start()
    }
}
