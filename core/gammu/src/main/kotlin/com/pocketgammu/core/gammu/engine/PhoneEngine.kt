package com.pocketgammu.core.gammu.engine

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.pocketgammu.core.gammu.model.ConnectionType
import com.pocketgammu.core.gammu.model.PhoneFamily
import com.pocketgammu.core.gammu.model.PhoneFile
import com.pocketgammu.core.gammu.model.PhoneInfo
import com.pocketgammu.core.nokia.bitmap.MonoBitmap
import com.pocketgammu.core.nokia.logo.LogoKind
import com.pocketgammu.core.nokia.logo.NetworkCode
import com.pocketgammu.core.nokia.ringtone.Ringtone
import com.pocketgammu.core.nokia.ringtone.SmartRingtoneCodec
import com.pocketgammu.engine.Device
import com.pocketgammu.engine.GsmBitmap
import com.pocketgammu.engine.GsmError
import com.pocketgammu.engine.GsmException
import com.pocketgammu.engine.LogSink
import com.pocketgammu.engine.N6510
import com.pocketgammu.engine.RingtoneIO
import com.pocketgammu.engine.S40Files
import com.pocketgammu.engine.Session
import com.pocketgammu.engine.irda.IrdaStack
import com.pocketgammu.engine.irda.RawIrDevice
import com.pocketgammu.engine.usb.UsbDrivers
import com.pocketgammu.engine.PhoneFile as EngineFile

fun interface ProgressListener {
    /** 0.0 – 1.0 */
    fun onProgress(fraction: Float)
}

/**
 * The phone engine: GammuDroid's Java port of Gammu (Session + FBUS/Phonet + N6110/N7110/N6510
 * + Series 40 filesystem) over its userspace USB and IrDA drivers. Blocking calls;
 * [com.pocketgammu.core.gammu.PhoneRepository] runs them on one thread.
 */
class PhoneEngine(private val log: LogSink) {
    private var session: Session? = null
    private var connectedType: ConnectionType? = null

    val version: String get() = VERSION

    /** GammuDroid's ConnectPage.connect: open the driver for [type], start Gammu, identify the phone. */
    fun connect(usb: UsbManager, device: UsbDevice, type: ConnectionType, family: PhoneFamily, powerCable: Boolean): PhoneInfo {
        disconnect()
        var dev: Device? = null
        try {
            dev = when {
                type.engine.infrared -> {
                    val port = UsbDrivers.openIr(usb, device, log)
                    if (type == ConnectionType.IRDA_PHONET) {
                        IrdaStack(port, log).also { dev = it; it.open("Nokia:PhoNet") }
                    } else {
                        RawIrDevice(port)
                    }
                }
                type == ConnectionType.DKU2 -> UsbDrivers.openNokia(usb, device)
                else -> UsbDrivers.openSerial(usb, device)
            }
            return start(dev, type, family, powerCable)
        } catch (e: Exception) {
            dev?.close()
            log.log("Connect failed: ${e.message}")
            throw e
        }
    }

    /**
     * Starts Gammu on a [Device] that is already open. [connect] uses it after opening the
     * USB driver; tests use it to talk to a simulated phone.
     */
    fun connect(dev: Device, type: ConnectionType, family: PhoneFamily, powerCable: Boolean): PhoneInfo {
        disconnect()
        return start(dev, type, family, powerCable)
    }

    private fun start(dev: Device, type: ConnectionType, family: PhoneFamily, powerCable: Boolean): PhoneInfo {
        val s = Session(dev, type.engine, log)
        s.noPowerCable = !powerCable
        s.connect(family.module)
        session = s
        connectedType = type
        return describe(s, full = false)
    }

    /** "Read network & IMEI". */
    fun readDetails(): PhoneInfo = describe(require(), full = true)

    fun disconnect() {
        val s = session ?: return
        session = null
        connectedType = null
        runCatching { s.close() }
    }

    fun setLogo(kind: LogoKind, bitmap: MonoBitmap, network: NetworkCode?, location: Int, text: String = "") {
        val type = when (kind) {
            LogoKind.OPERATOR -> GsmBitmap.Type.OPERATOR_LOGO
            LogoKind.CALLER_GROUP -> GsmBitmap.Type.CALLER_GROUP_LOGO
            LogoKind.STARTUP -> GsmBitmap.Type.STARTUP_LOGO
            LogoKind.PICTURE -> GsmBitmap.Type.PICTURE_IMAGE
        }
        val b = GsmBitmap(type, bitmap.width, bitmap.height)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) b.setPoint(x, y, bitmap[x, y])
        b.location = when (kind) {
            LogoKind.CALLER_GROUP -> location.coerceIn(1, 5)
            LogoKind.PICTURE -> location.coerceAtLeast(1)
            else -> 1
        }
        if (kind == LogoKind.PICTURE) b.text = text
        if (kind == LogoKind.CALLER_GROUP) {
            b.defaultName = true
            b.defaultRingtone = true
            b.bitmapEnabled = true
        }
        if (kind == LogoKind.OPERATOR) {
            b.networkCode = network?.toString()
                ?: throw GsmException(GsmError.INVALIDDATA, "enter the network code as MCC and MNC, e.g. 515 02")
        }
        require().phone().setBitmap(b)
    }

    /** Sends the ringtone; location 255 = the phone plays it and offers to save it. */
    fun setRingtone(ringtone: Ringtone, location: Int = 255) {
        val ott = SmartRingtoneCodec.encode(ringtone).bytes
        val tone = RingtoneIO.decodeSmartMessaging(ott, 0, ott.size)
        require().phone().setRingtone(tone, location)
    }

    fun listFolder(folderId: String?): List<PhoneFile> {
        val fs = files()
        val raw = if (folderId == null) fs.roots() else fs.list(folderId, BooleanArray(1))
        return raw.map { it.toModel() }
            .sortedWith(compareByDescending<PhoneFile> { it.isFolder }.thenBy { it.name.lowercase() })
    }

    fun readFile(fileId: String, progress: ProgressListener): ByteArray {
        val f = files().download(fileId) { done, total -> if (total > 0) progress.onProgress(done.toFloat() / total) }
        progress.onProgress(1f)
        val data = f.buffer ?: ByteArray(0)
        return if (data.size.toLong() == f.used) data else data.copyOf(f.used.toInt())
    }

    fun writeFile(folderId: String, name: String, data: ByteArray, progress: ProgressListener) {
        files().upload(folderId, name, data, EngineFile.dateOf(System.currentTimeMillis())) { done, total ->
            if (total > 0) progress.onProgress(done.toFloat() / total)
        }
        progress.onProgress(1f)
    }

    fun deleteFile(fileId: String) = files().deleteFile(fileId)
    fun deleteFolder(folderId: String) = files().deleteFolder(folderId)
    fun createFolder(parentId: String, name: String) { files().addFolder(parentId, name) }

    private fun require(): Session = session ?: throw GsmException(GsmError.NOTCONNECTED)

    private fun files(): S40Files {
        val phone = require().phone()
        return (phone as? N6510)?.files()
            ?: throw GsmException(GsmError.NOTSUPPORTED, "File management needs a Series 40 phone (Gammu module N6510)")
    }

    /** GammuDroid's ConnectPage.describe. */
    private fun describe(s: Session, full: Boolean): PhoneInfo {
        var imei: String? = null
        var network: String? = null
        var code: String? = null
        if (full) {
            imei = try { s.phone().imei } catch (e: GsmException) { "not available" }
            try {
                val n = s.phone().networkInfo
                network = n.toString()
                if (n.registered()) code = n.networkCode
            } catch (e: GsmException) {
                network = e.message
            }
        }
        return PhoneInfo(
            model = s.modelInfo?.toString() ?: s.model,
            firmware = s.version,
            firmwareDate = s.verDate,
            module = s.phone().name(),
            connection = "${s.connectionType().gammuName} · ${connectedType?.label ?: s.connectionType().label}",
            cable = s.device().describe().replace('\n', ' '),
            imei = imei,
            network = network,
            networkCode = code,
        )
    }

    private fun EngineFile.toModel() = PhoneFile(
        id = id,
        name = name,
        isFolder = folder,
        size = if (folder) 0 else used,
        modifiedEpochMs = modified?.takeIf { it.size >= 6 && it[0] > 1980 }?.let { m ->
            java.util.Calendar.getInstance().apply { set(m[0], m[1] - 1, m[2], m[3], m[4], m[5]); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
        },
        protected = protect || readOnly,
    )

    companion object {
        const val VERSION = "GammuDroid engine 1.0 (Java port of Gammu 1.45)"
    }
}
