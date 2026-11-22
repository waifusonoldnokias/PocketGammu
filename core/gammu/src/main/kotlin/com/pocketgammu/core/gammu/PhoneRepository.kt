package com.pocketgammu.core.gammu

import com.pocketgammu.core.gammu.engine.EngineLog
import com.pocketgammu.core.gammu.engine.PhoneEngine
import com.pocketgammu.core.gammu.engine.ProgressListener
import com.pocketgammu.core.gammu.model.ConnectionState
import com.pocketgammu.core.gammu.model.ConnectionType
import com.pocketgammu.core.gammu.model.PhoneFamily
import com.pocketgammu.core.gammu.model.PhoneFile
import com.pocketgammu.core.gammu.model.PhoneInfo
import com.pocketgammu.core.gammu.usb.DetectedDevice
import com.pocketgammu.core.gammu.usb.UsbDeviceScanner
import com.pocketgammu.core.nokia.bitmap.MonoBitmap
import com.pocketgammu.core.nokia.logo.LogoKind
import com.pocketgammu.core.nokia.logo.NetworkCode
import com.pocketgammu.core.nokia.ringtone.Ringtone
import com.pocketgammu.engine.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The app's single entry point to the phone. Owns the connection state and runs every
 * engine call on one thread (Gammu's state machine is single-threaded).
 */
class PhoneRepository(private val scanner: UsbDeviceScanner) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val engineThread = Dispatchers.IO.limitedParallelism(1)

    val log = EngineLog()
    private val engine = PhoneEngine(log)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    val engineVersion: String get() = engine.version
    val isConnected: Boolean get() = _state.value is ConnectionState.Connected

    suspend fun connect(device: DetectedDevice, type: ConnectionType, family: PhoneFamily, powerCable: Boolean): Result<PhoneInfo> {
        if (!device.supported) return fail("Attach a PL2303 or serial cable, an STIr4200 or a Nokia USB cable first")
        _state.value = ConnectionState.Connecting(type)
        log.log("Connecting with \"${type.gammuId}\" via ${device.label} ${device.idLabel}, module ${family.module ?: "auto"}")
        if (!scanner.requestPermission(device.device)) return fail("USB permission denied")
        val usb = scanner.manager ?: return fail("USB host isn't available on this device")
        return withContext(engineThread) {
            runCatching { engine.connect(usb, device.device, type, family, powerCable) }
        }.onSuccess { _state.value = ConnectionState.Connected(it, type, family) }
            .onFailure { _state.value = ConnectionState.Failed(it.message ?: "Connect failed") }
    }

    /** Connects through a [Device] that is already open, such as a simulated phone in tests. */
    suspend fun connect(device: Device, type: ConnectionType, family: PhoneFamily): Result<PhoneInfo> {
        _state.value = ConnectionState.Connecting(type)
        log.log("Connecting with \"${type.gammuId}\" via ${device.describe()}, module ${family.module ?: "auto"}")
        return withContext(engineThread) {
            runCatching { engine.connect(device, type, family, powerCable = true) }
        }.onSuccess { _state.value = ConnectionState.Connected(it, type, family) }
            .onFailure { _state.value = ConnectionState.Failed(it.message ?: "Connect failed") }
    }

    suspend fun disconnect() {
        withContext(engineThread) { runCatching { engine.disconnect() } }
        log.log("Disconnected")
        _state.value = ConnectionState.Disconnected
    }

    fun clearError() {
        if (_state.value is ConnectionState.Failed) _state.value = ConnectionState.Disconnected
    }

    /** IMEI and network (GammuDroid's "Read network & IMEI"); updates the connected info. */
    suspend fun readDetails(): Result<PhoneInfo> = call { engine.readDetails() }.onSuccess { info ->
        (_state.value as? ConnectionState.Connected)?.let { _state.value = it.copy(info = info) }
    }

    suspend fun setLogo(kind: LogoKind, bitmap: MonoBitmap, network: NetworkCode?, location: Int = 1, text: String = "") =
        call { engine.setLogo(kind, bitmap, network, location, text) }

    suspend fun setRingtone(ringtone: Ringtone) = call { engine.setRingtone(ringtone) }

    suspend fun listFolder(folderId: String?): Result<List<PhoneFile>> = call { engine.listFolder(folderId) }

    suspend fun readFile(fileId: String, progress: (Float) -> Unit): Result<ByteArray> =
        call { engine.readFile(fileId, ProgressListener(progress)) }

    suspend fun writeFile(folderId: String, name: String, data: ByteArray, progress: (Float) -> Unit) =
        call { engine.writeFile(folderId, name, data, ProgressListener(progress)) }

    suspend fun delete(file: PhoneFile) =
        call { if (file.isFolder) engine.deleteFolder(file.id) else engine.deleteFile(file.id) }

    suspend fun createFolder(parentId: String, name: String) = call { engine.createFolder(parentId, name) }

    fun connectionLog(): String = log.text()

    fun clearLog() = log.clear()

    /** Like GammuDroid's runOnPhone: errors are reported, the session stays up. */
    private suspend fun <T> call(block: () -> T): Result<T> {
        if (!isConnected) return Result.failure(IllegalStateException("Connect to a phone first"))
        return withContext(engineThread) { runCatching(block) }
            .onFailure { log.log("Failed: ${it.message}") }
    }

    private fun <T> fail(message: String): Result<T> {
        log.log(message)
        _state.value = ConnectionState.Failed(message)
        return Result.failure(IllegalStateException(message))
    }
}
