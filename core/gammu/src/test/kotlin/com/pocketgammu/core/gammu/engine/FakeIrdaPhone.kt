package com.pocketgammu.core.gammu.engine

import com.pocketgammu.engine.irda.IrPort

import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A dongle with a simulated IrDA phone (secondary station) behind it. Written from the
 * IrDA specs / Linux secondary code, independently of the primary implementation.
 *
 * It answers discovery in slot [answerSlot], accepts SNRM, serves IAS for
 * Nokia:PhoNet (LSAP 0x14) and IrDA:IrCOMM (0x15), and echoes TinyTP data back.
 */
class FakeIrdaPhone(
    private val answerSlot: Int = 2,
    private val phoneBaudBits: Int = 0x3F,
    private val phoneDataSizeBits: Int = 0x07,      // 256-byte frames
    private val initialCredit: Int = 6,
    /** Drop this many responses to I-frames (simulates IR noise). */
    var dropResponses: Int = 0,
    val offersPhonet: Boolean = true,
) : IrPort {
    override fun maxSpeed() = 115200
    override fun describe() = "fake IrDA dongle"

    val phoneAddress = 0x1234ABCD
    val speeds = mutableListOf<Int>()
    val receivedPayload = ByteArrayOutputStream()
    var ircommControlSeen = false
    var discoveryFrames = 0
    var badFcs = 0

    private val toPrimary = LinkedBlockingQueue<Byte>()
    private val unwrap = Sir.Unwrapper()
    private var primaryAddress = 0
    private var caddr = -1
    private var vr = 0
    private var vs = 0
    private var ttpCredit = 0            // credit the PC gave us
    private var creditOwed = 0
    private val outLmp = ArrayDeque<ByteArray>()
    private var ircomm = false

    override fun close() {}
    override fun setSpeed(baud: Int) { speeds += baud }

    /** Simulates a dongle that can't actually switch above 9600 bps: the phone hears nothing. */
    var deafAbove9600 = false

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (deafAbove9600 && (speeds.lastOrNull() ?: 9600) > 9600) return
        unwrap.feed(b, off, len) { frame -> handle(frame) }
        badFcs = unwrap.badFrames
    }

    override fun read(b: ByteArray, off: Int, len: Int, timeoutMs: Int): Int {
        val first = toPrimary.poll(timeoutMs.toLong().coerceAtLeast(1), TimeUnit.MILLISECONDS) ?: return 0
        b[off] = first
        var n = 1
        while (n < len) { b[off + n++] = toPrimary.poll() ?: break }
        return n
    }

    private fun reply(frame: ByteArray) = Sir.wrap(frame).forEach { toPrimary.add(it) }

    private fun le32(out: ByteArrayOutputStream, v: Int) { repeat(4) { out.write((v ushr (8 * it)) and 0xFF) } }
    private fun rd32(b: ByteArray, o: Int) = (0..3).fold(0) { acc, k -> acc or ((b[o + k].toInt() and 0xFF) shl (8 * k)) }

    private fun handle(f: ByteArray) {
        if (f.size < 2) return
        val addr = f[0].toInt() and 0xFF
        val c = f[1].toInt() and 0xFF
        if (addr == 0xFF && (c and 0xEF) == 0x2F) {                 // XID command
            discoveryFrames++
            primaryAddress = rd32(f, 3)
            val slot = f[12].toInt() and 0xFF
            if (slot == answerSlot) {
                reply(ByteArrayOutputStream().apply {
                    write(0xFE); write(0xBF); write(0x01)
                    le32(this, phoneAddress); le32(this, primaryAddress)
                    write(0x01); write(slot); write(0x00)
                    write(0x81); write(0x04)                           // hints with extension byte
                    write(0x00)                                         // ASCII
                    write("Nokia 6230".toByteArray())
                }.toByteArray())
            }
            return
        }
        if (addr == 0xFF && (c and 0xEF) == 0x83) {                 // SNRM
            if (rd32(f, 6) != phoneAddress) return
            caddr = f[10].toInt() and 0xFE
            vr = 0; vs = 0
            reply(ByteArrayOutputStream().apply {
                write(caddr); write(0x73)
                le32(this, phoneAddress); le32(this, primaryAddress)
                write(byteArrayOf(0x01, 0x01, phoneBaudBits.toByte(), 0x82.toByte(), 0x01, 0x01,
                    0x83.toByte(), 0x01, phoneDataSizeBits.toByte(), 0x84.toByte(), 0x01, 0x7F,
                    0x85.toByte(), 0x01, 0x80.toByte(), 0x86.toByte(), 0x01, 0x07, 0x08, 0x01, 0x01))
            }.toByteArray())
            return
        }
        if (caddr < 0 || (addr and 0xFE) != caddr || (addr and 1) == 0) return
        when {
            c and 0x01 == 0 -> {                                      // I-frame
                val ns = (c ushr 1) and 7
                if (dropResponses > 0) { dropResponses--; return }   // "lost" — primary must retransmit
                if (ns == vr) { vr = (vr + 1) and 7; onLmp(f.copyOfRange(2, f.size)) }
                respond()
            }
            (c and 0xEF) == 0x43 -> { reply(byteArrayOf(caddr.toByte(), 0x73)); caddr = -1 }   // DISC → UA
            c and 0x03 == 0x01 -> respond()                           // RR poll
        }
    }

    private fun respond() {
        val next = outLmp.removeFirstOrNull()
        if (next != null) {
            val control = (vr shl 5) or 0x10 or (vs shl 1)
            vs = (vs + 1) and 7
            reply(byteArrayOf(caddr.toByte(), control.toByte()) + next)
        } else {
            reply(byteArrayOf(caddr.toByte(), ((vr shl 5) or 0x10 or 0x01).toByte()))
        }
    }

    private fun onLmp(p: ByteArray) {
        val control = p[0].toInt() and 0x80 != 0
        val dlsap = p[0].toInt() and 0x7F
        val slsap = p[1].toInt() and 0x7F
        if (control) {
            when (p[2].toInt() and 0xFF) {
                0x01 -> {                                             // connect
                    val cnf = byteArrayOf((slsap or 0x80).toByte(), dlsap.toByte(), 0x81.toByte(), 0x00)
                    if (dlsap == 0x14 || dlsap == 0x15) {
                        ircomm = dlsap == 0x15
                        ttpCredit = p[4].toInt() and 0x7F
                        outLmp.addLast(cnf + byteArrayOf(initialCredit.toByte()))
                    } else outLmp.addLast(cnf)
                }
            }
            return
        }
        if (dlsap == 0x00) {                                         // IAS query
            val clsLen = p[3].toInt()
            val cls = String(p, 4, clsLen)
            val lsap = when (cls) { "Nokia:PhoNet" -> if (offersPhonet) 0x14 else null; "IrDA:IrCOMM" -> 0x15; else -> null }
            val head = byteArrayOf(slsap.toByte(), 0x00, 0x84.toByte())
            outLmp.addLast(
                if (lsap == null) head + byteArrayOf(0x01)
                else head + byteArrayOf(0x00, 0x00, 0x01, 0x00, 0x05, 0x01, 0x00, 0x00, 0x00, lsap.toByte()),
            )
            return
        }
        if (dlsap == 0x14 || dlsap == 0x15) {                        // TinyTP data
            ttpCredit += p[2].toInt() and 0x7F
            var body = p.copyOfRange(3, p.size)
            if (body.isEmpty()) return
            creditOwed++
            if (ircomm) {
                val clen = body[0].toInt() and 0xFF
                if (clen > 0) ircommControlSeen = true
                body = body.copyOfRange(1 + clen, body.size)
                if (body.isEmpty()) { outLmp.addLast(byteArrayOf(slsap.toByte(), dlsap.toByte(), creditOwed.toByte())); creditOwed = 0; return }
            }
            receivedPayload.write(body)
            check(ttpCredit > 0) { "PC sent without credit" }
            ttpCredit--
            val echoHead = if (ircomm) byteArrayOf(slsap.toByte(), dlsap.toByte(), creditOwed.toByte(), 0x00)
            else byteArrayOf(slsap.toByte(), dlsap.toByte(), creditOwed.toByte())
            creditOwed = 0
            outLmp.addLast(echoHead + body)
        }
    }
}
