package com.pocketgammu.core.gammu.engine

import com.pocketgammu.engine.Device
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A Series 40 v1 phone (3510i-like) on an FBUS cable that answers only the
 * "filesystem 1" requests (message type 0x6D). Root folder 1 lists GMSTemp first and
 * a stale ID, like real phones do — Gammu must skip both.
 */
class FakeFbusPhone(
    /** Largest file part the phone returns (real firmware may send less than the 1000 asked). */
    private val maxPart: Int = 1000,
    /** Whether the phone answers the checksum request (0x42). */
    private val answersCrc: Boolean = true,
) : Device {
    private class Node(val name: String?, val folder: Boolean, val children: List<Int> = emptyList(), val size: Int = 0)
    private val fs = mapOf(
        1 to Node("Permanent_memory", true, listOf(3, 9, 4, 5)),
        3 to Node("GMSTemp", true),
        9 to Node(null, false),                         // stale: phone answers "doesn't exist"
        4 to Node("Images", true, listOf(6)),
        5 to Node("Tones", true),
        6 to Node("logo.bmp", false, size = 2500),
    )
    /** Contents of file 6 (2500 bytes: two full 1000-byte parts and a short one). */
    val content = ByteArray(2500) { ((it * 7 + 3) and 0xFF).toByte() }

    /** N6510_FindFileCheckSum12, as the phone computes it. */
    private fun checksum(b: ByteArray): Int {
        var acc = 0xFFFF; var accx = 0
        for (v in b) {
            accx = (accx and 0xFFFF00FF.toInt()) or (acc and 0xFF00)
            acc = (acc and 0xFFFF00FF.toInt()) or ((v.toInt() and 0xFF) shl 8)
            repeat(8) {
                acc = acc shl 1
                if (acc and 0x10000 != 0) acc = acc xor 0x1021
                if (accx and 0x80000000.toInt() != 0) acc = acc xor 0x1021
                accx = accx shl 1
            }
        }
        return acc and 0xFFFF
    }
    val requests = mutableListOf<String>()
    private val rx = java.io.ByteArrayOutputStream()
    private val toHost = LinkedBlockingQueue<Byte>()
    private var seq = 0

    override fun close() {}
    override fun describe() = "fake 3510i on FBUS"
    override fun setSpeed(baud: Int) {}
    override fun setParity(parity: Boolean) {}
    override fun setDtrRts(dtr: Boolean, rts: Boolean) {}

    override fun read(buf: ByteArray, off: Int, len: Int, timeoutMs: Int): Int {
        val first = toHost.poll(timeoutMs.toLong().coerceAtLeast(1), TimeUnit.MILLISECONDS) ?: return 0
        buf[off] = first
        var n = 1
        while (n < len) buf[off + n++] = toHost.poll() ?: break
        return n
    }

    override fun write(buf: ByteArray, off: Int, len: Int) {
        rx.write(buf, off, len)
        parse()
    }

    private fun parse() {
        var b = rx.toByteArray()
        while (true) {
            val start = b.indexOf(0x1E.toByte())
            if (start < 0) { b = ByteArray(0); break }
            if (b.size - start < 6) { b = b.copyOfRange(start, b.size); break }
            val len = ((b[start + 4].toInt() and 0xFF) shl 8) or (b[start + 5].toInt() and 0xFF)
            val total = 6 + len + (len and 1) + 2
            if (b.size - start < total) { b = b.copyOfRange(start, b.size); break }
            val type = b[start + 3].toInt() and 0xFF
            val payload = b.copyOfRange(start + 6, start + 6 + len - 2)   // drop framesToGo + seq
            if (type == 0x6D) handle(payload)
            if (type == 0xD1) send(0xD2, byteArrayOf(0x01, 0x47, 0x00) + "V 05.50\n14-03-03\nRH-9\n(c) NMP.".toByteArray())
            b = b.copyOfRange(start + total, b.size)
        }
        rx.reset(); rx.write(b)
    }

    private fun handle(p: ByteArray) {
        val sub = p[3].toInt() and 0xFF
        val id = ((p[8].toInt() and 0xFF) shl 8) or (p[9].toInt() and 0xFF)
        requests += "%02X:%d".format(sub, id)
        val node = fs[id]
        when (sub) {
            0x14 -> {
                if (node?.name == null) { send(0x6D, byteArrayOf(0, 1, 0, 0x15, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)); return }
                val r = ByteArray(100)
                r[1] = 1; r[3] = 0x15
                val i = 90
                r[8] = 0; r[9] = i.toByte()
                node.name.forEachIndexed { k, c -> r[10 + 2 * k] = (c.code shr 8).toByte(); r[11 + 2 * k] = c.code.toByte() }
                r[i - 22] = 0x07; r[i - 21] = 0xD3.toByte(); r[i - 20] = 3; r[i - 19] = 14; r[i - 18] = 12   // 2003-03-14 12:00
                r[i - 5] = 0; r[i - 3] = if (node.folder) 2 else 1
                send(0x6D, r)
            }
            0x32 -> {
                val kids = node?.children ?: emptyList()
                val r = ByteArray(12 + kids.size * 4 + 2)
                r[1] = 1; r[3] = 0x33; r[9] = kids.size.toByte()
                kids.forEachIndexed { k, c -> r[12 + 4 * k] = (c shr 8).toByte(); r[13 + 4 * k] = c.toByte() }
                send(0x6D, r)
            }
            0x0E -> {                                           // file part
                val off = ((p[10].toInt() and 0xFF) shl 24) or ((p[11].toInt() and 0xFF) shl 16) or ((p[12].toInt() and 0xFF) shl 8) or (p[13].toInt() and 0xFF)
                val want = ((p[16].toInt() and 0xFF) shl 8) or (p[17].toInt() and 0xFF)
                val n = maxOf(0, minOf(want, maxPart, content.size - off))
                send(0x6D, byteArrayOf(0, 1, 0, 0x0F, 0, 0, (n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte()) + content.copyOfRange(off, off + n))
            }
            0x42 -> {                                           // checksum
                if (!answersCrc) return
                val c = checksum(content)
                send(0x6D, byteArrayOf(0, 1, 0, 0x43, 0, 0, (c shr 8).toByte(), c.toByte()))
            }
            0x2E -> {
                val size = node?.size ?: 0
                send(0x6D, byteArrayOf(0, 1, 0, 0x2F, 0, 0, (size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()))
            }
        }
    }

    /** Long replies are split across FBUS frames (120 bytes each), as phones do. */
    private fun send(type: Int, payload: ByteArray) {
        val chunks = payload.toList().chunked(120).map { it.toByteArray() }.ifEmpty { listOf(ByteArray(0)) }
        chunks.forEachIndexed { i, chunk ->
            val seqByte = (if (i == 0) 0x40 else 0) or (seq++ and 7)
            val data = chunk + byteArrayOf((chunks.size - i).toByte(), seqByte.toByte())
            val f = java.io.ByteArrayOutputStream().apply {
                write(0x1E); write(0x0C); write(0x00); write(type); write(data.size shr 8); write(data.size and 0xFF); write(data)
                if (data.size % 2 == 1) write(0)
            }.toByteArray()
            var c1 = 0; var c2 = 0
            f.forEachIndexed { k, v -> if (k % 2 == 0) c1 = c1 xor (v.toInt() and 0xFF) else c2 = c2 xor (v.toInt() and 0xFF) }
            (f + byteArrayOf(c1.toByte(), c2.toByte())).forEach { toHost.add(it) }
        }
    }
}
