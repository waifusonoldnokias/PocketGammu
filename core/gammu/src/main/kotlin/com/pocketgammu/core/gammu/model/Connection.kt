package com.pocketgammu.core.gammu.model

import com.pocketgammu.engine.usb.UsbDrivers
import com.pocketgammu.engine.ConnectionType as EngineType

/** Gammu `connection =` values the engine speaks (GammuDroid's set). */
enum class ConnectionType(val engine: EngineType, val label: String, val hint: String) {
    FBUS(EngineType.FBUS, "FBUS cable", "DAU-9P or FBUS TTL cable · 3310, 3330, 3410…"),
    DKU5(EngineType.DKU5, "DKU-5 / CA-42 cable", "Switches the phone with AT*NOKIAFBUS"),
    FBUS_PL2303(EngineType.FBUSPL2303, "FBUS over PL2303", "CA-42 clone cables for DCT-4 phones"),
    DLR3(EngineType.DLR3, "DLR-3P cable", "Switches the phone with AT*NOKIAFBUS"),
    IRDA_PHONET(EngineType.IRDAPHONET, "IrDA Phonet", "Full IrDA stack · 6210, 6310i, 6510, 8210, 8310, Series 40"),
    FBUS_IRDA(EngineType.FBUSIRDA, "Infrared FBUS", "Raw infrared · 6110, 6130, 6150, 6190 (needs 115200 bps)"),
    DKU2(EngineType.DKU2, "Nokia USB", "DKU-2, CA-53 or the phone's own USB port"),
    ;

    /** The name Gammu uses in gammurc, e.g. "irdaphonet". */
    val gammuId: String get() = engine.gammuName

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.gammuId == id }

        /** Connection types that make sense for a cable or dongle (GammuDroid's ConnectPage). */
        fun choicesFor(kind: UsbDrivers.Kind): List<ConnectionType> = when {
            kind == UsbDrivers.Kind.NOKIA_USB -> listOf(DKU2)
            kind.infrared -> listOf(IRDA_PHONET, FBUS_IRDA)
            kind == UsbDrivers.Kind.UNSUPPORTED -> emptyList()
            else -> listOf(FBUS, DKU5, FBUS_PL2303, DLR3)
        }
    }
}

/** Which Gammu phone module drives the phone. [module] null = ask the phone. */
enum class PhoneFamily(val id: String, val module: String?, val label: String, val examples: String) {
    AUTO("auto", null, "Automatic", "Ask the phone which model it is"),
    N6110("n6110", "N6110", "N6110 · DCT-3", "3210, 3310, 3330, 3410, 5110, 6110, 6150, 8210, 8850"),
    N7110("n7110", "N7110", "N7110 · DCT-3", "6210, 6250, 7110, 7190"),
    N6510("n6510", "N6510", "N6510 · DCT-4 / Series 40", "3510i, 6310i, 6510, 6100, 3200, 6020, 6230, 8310"),
    ;

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** What the phone said about itself (GammuDroid's status card). */
data class PhoneInfo(
    /** e.g. "Nokia 3510i (RH-9)", or the raw model code when Gammu doesn't know it. */
    val model: String,
    val firmware: String,
    val firmwareDate: String,
    /** Gammu module, e.g. "N6510". */
    val module: String,
    val connection: String,
    /** Cable or dongle description from the driver. */
    val cable: String,
    val imei: String? = null,
    /** e.g. "515 02 (Globe)", or a reason it couldn't be read. */
    val network: String? = null,
    /** "MCC MNC" when registered; offered for operator logos. */
    val networkCode: String? = null,
) {
    val displayName: String get() = model.substringBefore(" (").ifBlank { "Phone" }
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val type: ConnectionType) : ConnectionState
    data class Connected(val info: PhoneInfo, val type: ConnectionType, val family: PhoneFamily) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

/** File or folder on a Series 40 phone. [id] is Gammu's opaque file ID (e.g. "c:\\1", "c:/23", "b:/a/b"). */
data class PhoneFile(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val size: Long,
    val modifiedEpochMs: Long?,
    val protected: Boolean = false,
)
