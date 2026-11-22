package com.pocketgammu.core.gammu.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.pocketgammu.core.gammu.model.ConnectionType
import com.pocketgammu.engine.usb.UsbDrivers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class DetectedDevice(
    val device: UsbDevice,
    /** Which engine driver handles it (GammuDroid's UsbDrivers.detect). */
    val kind: UsbDrivers.Kind,
    val vendorId: Int,
    val productId: Int,
    /** The device's own product string, if it has one. */
    val productName: String?,
    val hasPermission: Boolean,
) {
    val idLabel: String get() = "%04X:%04X".format(vendorId, productId)
    val supported: Boolean get() = kind != UsbDrivers.Kind.UNSUPPORTED
    val label: String get() = if (supported) kind.label else productName ?: "USB device"
    val connectionChoices: List<ConnectionType> get() = ConnectionType.choicesFor(kind)
}

/**
 * Watches USB attach/detach and exposes the list of connected cables/dongles.
 * Call [start] once (from the Application).
 */
class UsbDeviceScanner(context: Context) {
    private val app = context.applicationContext
    private val usb: UsbManager? = app.getSystemService(UsbManager::class.java)
    private val _devices = MutableStateFlow<List<DetectedDevice>>(emptyList())
    val devices: StateFlow<List<DetectedDevice>> = _devices.asStateFlow()

    private val hotplug = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = refresh()
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(app, hotplug, filter, ContextCompat.RECEIVER_EXPORTED)
        refresh()
    }

    fun refresh() {
        val manager = usb ?: return
        _devices.value = manager.deviceList.values.map { d ->
            DetectedDevice(
                device = d,
                kind = runCatching { UsbDrivers.detect(d) }.getOrDefault(UsbDrivers.Kind.UNSUPPORTED),
                vendorId = d.vendorId,
                productId = d.productId,
                productName = runCatching { d.productName }.getOrNull(),
                hasPermission = manager.hasPermission(d),
            )
        }.sortedByDescending { it.supported }
    }

    /** Asks the user for access to [device]; returns true when granted. */
    suspend fun requestPermission(device: UsbDevice): Boolean {
        val manager = usb ?: return false
        if (manager.hasPermission(device)) return true
        val action = "${app.packageName}.USB_PERMISSION"
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.action != action) return
                    runCatching { app.unregisterReceiver(this) }
                    val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    refresh()
                    if (cont.isActive) cont.resume(granted)
                }
            }
            ContextCompat.registerReceiver(app, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            cont.invokeOnCancellation { runCatching { app.unregisterReceiver(receiver) } }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(app, 0, Intent(action).setPackage(app.packageName), flags)
            manager.requestPermission(device, pi)
        }
    }

    internal val manager: UsbManager? get() = usb
}
