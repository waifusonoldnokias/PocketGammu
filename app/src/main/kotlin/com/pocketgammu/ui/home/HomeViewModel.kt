package com.pocketgammu.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketgammu.AppContainer
import com.pocketgammu.core.gammu.model.ConnectionState
import com.pocketgammu.core.gammu.model.ConnectionType
import com.pocketgammu.core.gammu.model.PhoneFamily
import com.pocketgammu.core.gammu.usb.DetectedDevice
import com.pocketgammu.core.model.SessionDefaults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val devices: List<DetectedDevice> = emptyList(),
    val selectedDevice: DetectedDevice? = null,
    /** Connection types for the selected device (GammuDroid's filtered list). */
    val choices: List<ConnectionType> = emptyList(),
    val connectionType: ConnectionType? = null,
    val family: PhoneFamily = PhoneFamily.AUTO,
    val powerCable: Boolean = true,
    val readingDetails: Boolean = false,
) {
    val connected: Boolean get() = connection is ConnectionState.Connected
    val connecting: Boolean get() = connection is ConnectionState.Connecting
    val canConnect: Boolean get() = selectedDevice?.supported == true && connectionType != null && !connecting
    /** DTR/RTS power only matters for serial cables. */
    val showPower: Boolean get() = selectedDevice?.let { !it.kind.infrared && it.connectionChoices.none { c -> c == ConnectionType.DKU2 } } ?: false
}

private data class Choices(
    val deviceName: String?,
    val type: ConnectionType?,
    val family: PhoneFamily?,
    val power: Boolean?,
    val reading: Boolean,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val choices = MutableStateFlow(Choices(null, null, null, null, false))

    val state: StateFlow<HomeUiState> = combine(
        container.phone.state,
        container.usbScanner.devices,
        container.settings.sessionDefaults,
        choices,
    ) { connection, devices, defaults: SessionDefaults, c ->
        // Keep the user's pick across rescans; otherwise the first supported device.
        val device = devices.firstOrNull { it.device.deviceName == c.deviceName }
            ?: devices.firstOrNull { it.supported }
            ?: devices.firstOrNull()
        val options = device?.connectionChoices.orEmpty()
        val saved = ConnectionType.fromId(defaults.connectionTypeId)
        HomeUiState(
            connection = connection,
            devices = devices,
            selectedDevice = device,
            choices = options,
            connectionType = c.type?.takeIf { it in options } ?: saved?.takeIf { it in options } ?: options.firstOrNull(),
            family = c.family ?: PhoneFamily.fromId(defaults.phoneFamilyId) ?: PhoneFamily.AUTO,
            powerCable = c.power ?: defaults.powerCable,
            readingDetails = c.reading,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun selectDevice(device: DetectedDevice) {
        choices.value = choices.value.copy(deviceName = device.device.deviceName)
    }

    fun selectType(type: ConnectionType) { choices.value = choices.value.copy(type = type) }
    fun selectFamily(family: PhoneFamily) { choices.value = choices.value.copy(family = family) }
    fun setPower(on: Boolean) { choices.value = choices.value.copy(power = on) }
    fun refreshDevices() = container.usbScanner.refresh()
    fun dismissError() = container.phone.clearError()

    fun connect(onResult: (Boolean, String) -> Unit) {
        val s = state.value
        val device = s.selectedDevice
        val type = s.connectionType
        if (device == null || !device.supported || type == null) {
            onResult(false, "Attach a PL2303 or serial cable, an STIr4200 or a Nokia USB cable first")
            return
        }
        viewModelScope.launch {
            container.settings.setConnection(type.gammuId, s.family.id, s.powerCable)
            container.phone.connect(device, type, s.family, s.powerCable)
                .onSuccess { onResult(true, "Connected to ${it.displayName}") }
                .onFailure { onResult(false, it.message ?: "Connect failed") }
        }
    }

    fun disconnect() {
        viewModelScope.launch { container.phone.disconnect() }
    }

    /** GammuDroid's "Read network & IMEI"; a registered network code is offered to the operator-logo sender. */
    fun readDetails(onResult: (Boolean, String) -> Unit) {
        choices.value = choices.value.copy(reading = true)
        viewModelScope.launch {
            container.phone.readDetails()
                .onSuccess { info ->
                    info.networkCode?.let { container.settings.setNetworkCode(it) }
                    onResult(true, "Phone details read")
                }
                .onFailure { onResult(false, it.message ?: "Couldn't read the phone details") }
            choices.value = choices.value.copy(reading = false)
        }
    }

    fun connectionLog(): String = container.phone.connectionLog()
    fun clearLog() = container.phone.clearLog()
}
