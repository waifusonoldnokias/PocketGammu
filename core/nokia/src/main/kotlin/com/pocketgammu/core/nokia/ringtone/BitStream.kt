package com.pocketgammu.core.nokia.ringtone

/** MSB-first bit writer used by the Smart Messaging ringtone format. */
internal class BitWriter {
    private val bytes = ArrayList<Int>()
    var bitPosition: Int = 0
        private set

    fun write(value: Int, bits: Int) {
        for (i in bits - 1 downTo 0) {
            val bit = (value ushr i) and 1
            val byteIndex = bitPosition / 8
            while (bytes.size <= byteIndex) bytes.add(0)
            if (bit == 1) bytes[byteIndex] = bytes[byteIndex] or (0x80 ushr (bitPosition % 8))
            bitPosition++
        }
    }

    fun align() {
        if (bitPosition % 8 != 0) bitPosition += 8 - bitPosition % 8
        while (bytes.size < bitPosition / 8) bytes.add(0)
    }

    /** Overwrites [bits] bits at an earlier position (used to back-patch counters). */
    fun patch(position: Int, value: Int, bits: Int) {
        val saved = bitPosition
        for (i in 0 until bits) {
            val p = position + i
            val idx = p / 8
            val mask = 0x80 ushr (p % 8)
            bytes[idx] = bytes[idx] and mask.inv()
        }
        bitPosition = position
        write(value, bits)
        bitPosition = saved
    }

    fun toByteArray(): ByteArray = ByteArray((bitPosition + 7) / 8) { bytes.getOrElse(it) { 0 }.toByte() }
}

internal class BitReader(private val data: ByteArray, startByte: Int = 0) {
    var bitPosition: Int = startByte * 8
        private set

    val remaining: Int get() = data.size * 8 - bitPosition

    fun read(bits: Int): Int {
        if (bits > remaining) throw RingtoneFormatException("Unexpected end of ringtone data")
        var v = 0
        repeat(bits) {
            val b = data[bitPosition / 8].toInt()
            v = (v shl 1) or ((b ushr (7 - bitPosition % 8)) and 1)
            bitPosition++
        }
        return v
    }

    fun skip(bits: Int) {
        bitPosition += bits
    }

    fun align() {
        if (bitPosition % 8 != 0) bitPosition += 8 - bitPosition % 8
    }
}

class RingtoneFormatException(message: String) : Exception(message)
