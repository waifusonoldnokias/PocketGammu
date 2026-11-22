package com.pocketgammu.core.gammu.engine

import com.pocketgammu.core.nokia.ringtone.Rtttl
import com.pocketgammu.core.nokia.ringtone.SmartRingtoneCodec
import com.pocketgammu.engine.ConnectionType
import com.pocketgammu.engine.Device
import com.pocketgammu.engine.Fbus2
import com.pocketgammu.engine.LogSink
import com.pocketgammu.engine.N6510
import com.pocketgammu.engine.RingtoneIO
import com.pocketgammu.engine.Session
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** GammuDroid's engine against simulated phones (runs on the JVM, no USB). */
class EngineTest {
    private fun connect(phone: FakeFbusPhone, log: MutableList<String> = ArrayList()): Session =
        Session(phone, ConnectionType.FBUS, LogSink { synchronized(log) { log += it } }).apply { connect(null) }

    /** Automatic family: the phone reports RH-9, which Gammu's table maps to the N6510 module. */
    @Test fun autoDetectsA3510iAndListsItsFiles() {
        val phone = FakeFbusPhone()
        val s = connect(phone)
        try {
            assertTrue(s.phone().name().startsWith("N6510"), s.phone().name())
            assertEquals("Nokia 3510i (RH-9)", s.modelInfo.toString())
            val fs = (s.phone() as N6510).files()
            val root = fs.roots()
            assertEquals(1, root.size)
            val top = fs.list(root[0].id, BooleanArray(1))
            // GMSTemp (3) and the stale entry (9) are skipped.
            assertEquals(listOf("Images", "Tones"), top.map { it.name })
            val images = fs.list(top.first { it.name == "Images" }.id, BooleanArray(1))
            assertEquals(listOf("logo.bmp"), images.map { it.name })
            assertEquals(2500L, images[0].used)
        } finally {
            s.close()
        }
    }

    @Test fun downloadsAFileInParts() {
        val phone = FakeFbusPhone()
        val s = connect(phone)
        try {
            val progress = ArrayList<Long>()
            val f = (s.phone() as N6510).files().download("c:/6") { done, _ -> progress += done }
            assertContentEquals(phone.content, f.buffer.copyOf(f.used.toInt()))
            assertEquals(listOf(1000L, 2000L, 2500L), progress)
        } finally {
            s.close()
        }
    }

    /** Firmware that sends 512-byte parts and never answers the checksum request (3510i field report). */
    @Test fun downloadsFromFirmwareWithShortPartsAndNoChecksum() {
        val phone = FakeFbusPhone(maxPart = 512, answersCrc = false)
        val log = ArrayList<String>()
        val s = connect(phone, log)
        s.replyNum = 1
        try {
            val f = (s.phone() as N6510).files().download("c:/6", null)
            assertContentEquals(phone.content, f.buffer.copyOf(f.used.toInt()))
            assertTrue(log.any { "keeping the file" in it }, log.takeLast(5).joinToString("\n"))
            assertEquals(5, phone.requests.count { it.startsWith("0E:") })   // 512+512+512+512+452
        } finally {
            s.close()
        }
    }

    /** "fbusirda" (6110 family) wake-up must reach the dongle as whole bursts, as GammuDroid sends them. */
    @Test fun fbusIrdaWakeUpIsSentInBursts() {
        val writes = ArrayList<Pair<Int, Int>>()          // (speed, length)
        val dongle = object : Device {
            var current = 0
            override fun read(buf: ByteArray, off: Int, len: Int, timeoutMs: Int) = 0
            override fun write(buf: ByteArray, off: Int, len: Int) { writes += current to len }
            override fun setSpeed(baud: Int) { current = baud }
            override fun setParity(parity: Boolean) {}
            override fun setDtrRts(dtr: Boolean, rts: Boolean) {}
            override fun close() {}
            override fun describe() = "recording dongle"
        }
        Fbus2(Session(dongle, ConnectionType.FBUSIRDA, LogSink.NONE), ConnectionType.FBUSIRDA).initialise()
        assertEquals(9600 to 33, writes[0], "32 × 0x55 + 0xC1 in one write at 9600 bps")
        assertEquals(115200 to 250, writes[1], "250 × 0x55 in one write at 115200 bps")
    }

    /** GammuDroid's IrDA stack + Phonet against a simulated IrDA phone (discovery, IAS, TinyTP). */
    @Test fun irdaStackReachesPhonet() {
        val phone = FakeIrdaPhone()
        val log = ArrayList<String>()
        val stack = com.pocketgammu.engine.irda.IrdaStack(phone, LogSink { synchronized(log) { log += it } })
        try {
            stack.open("Nokia:PhoNet")
            val msg = "hello phonet".toByteArray()
            stack.write(msg, 0, msg.size)
            val got = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64)
            val until = System.currentTimeMillis() + 5000
            while (got.size() < msg.size && System.currentTimeMillis() < until) {
                val n = stack.read(buf, 0, buf.size, 100)
                if (n > 0) got.write(buf, 0, n)
            }
            assertContentEquals(msg, got.toByteArray(), log.takeLast(20).joinToString("\n"))
        } finally {
            stack.close()
        }
    }

    /** The app's ringtone (core:nokia) reaches the engine as a Smart Messaging package. */
    @Test fun ringtonesCrossFromTheAppToTheEngine() {
        val tone = Rtttl.parse("Test:d=4,o=5,b=125:c,e,g,8c6,p,2a")
        val ott = SmartRingtoneCodec.encode(tone).bytes
        val decoded = RingtoneIO.decodeSmartMessaging(ott, 0, ott.size)
        val notes = decoded.commands.count { it.note != null }
        assertEquals(6, notes)
    }
}
