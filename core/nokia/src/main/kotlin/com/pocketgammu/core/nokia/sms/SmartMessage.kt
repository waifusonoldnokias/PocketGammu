package com.pocketgammu.core.nokia.sms

/** A Nokia Smart Messaging payload addressed to a WDP port (e.g. 0x1581 ringtone, 0x1582 operator logo). */
class SmartMessage(
    val destinationPort: Int,
    val payload: ByteArray,
    val sourcePort: Int = 0,
) {
    val sizeBytes: Int get() = payload.size

    fun hex(): String = payload.toHex()
}

internal fun ByteArray.toHex(): String {
    val digits = "0123456789ABCDEF"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(digits[v ushr 4]).append(digits[v and 0x0F])
    }
    return sb.toString()
}
