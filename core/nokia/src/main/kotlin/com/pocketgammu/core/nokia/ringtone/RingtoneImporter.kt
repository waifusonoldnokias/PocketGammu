package com.pocketgammu.core.nokia.ringtone

enum class RingtoneFileType(val extension: String, val label: String) {
    RTTTL("txt", "RTTTL text"),
    MIDI("mid", "MIDI song"),
    NRT("nrt", "Nokia Ringing Tone"),
}

/**
 * Detects the format from the file contents (extensions on old files are unreliable)
 * and returns an editable [Ringtone].
 */
object RingtoneImporter {

    /** Smart Messaging ringtone header: 02 4A 3A (count, programming, sound). */
    private val OTT_SIGNATURE = byteArrayOf(0x02, 0x4A, 0x3A)

    fun detect(bytes: ByteArray): RingtoneFileType? = when {
        bytes.size >= 4 && bytes[0] == 'M'.code.toByte() && bytes[1] == 'T'.code.toByte() &&
            bytes[2] == 'h'.code.toByte() && bytes[3] == 'd'.code.toByte() -> RingtoneFileType.MIDI
        indexOfOtt(bytes) >= 0 -> RingtoneFileType.NRT
        looksLikeRtttl(bytes) -> RingtoneFileType.RTTTL
        else -> null
    }

    fun import(bytes: ByteArray, fileName: String, midiTrack: Int? = null): Ringtone {
        val baseName = fileName.substringBeforeLast('.').take(15).ifBlank { "Ringtone" }
        return when (detect(bytes)) {
            RingtoneFileType.MIDI -> MidiImporter.import(bytes, baseName, midiTrack)
            RingtoneFileType.NRT -> readNrt(bytes)
            RingtoneFileType.RTTTL -> Rtttl.parse(bytes.toString(Charsets.ISO_8859_1))
            null -> throw RingtoneFormatException("Unrecognised ringtone file")
        }
    }

    /**
     * Nokia binary ringtone files (.nrt / .ott) carry a Smart Messaging ringtone,
     * sometimes behind a vendor header — the same search Gammu uses for Communicator files.
     */
    fun readNrt(bytes: ByteArray): Ringtone {
        val at = indexOfOtt(bytes)
        if (at < 0) throw RingtoneFormatException("No Nokia ringtone data found in file")
        return SmartRingtoneCodec.decode(bytes, at)
    }

    private fun indexOfOtt(bytes: ByteArray): Int {
        outer@ for (i in 0..bytes.size - OTT_SIGNATURE.size) {
            for (j in OTT_SIGNATURE.indices) if (bytes[i + j] != OTT_SIGNATURE[j]) continue@outer
            return i
        }
        return -1
    }

    private fun looksLikeRtttl(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > 64 * 1024) return false
        val text = bytes.toString(Charsets.ISO_8859_1)
        return text.count { it == ':' } >= 2 && text.all { it.code in 0x09..0x7E || it == '﻿' || it.code > 0x7F }
    }
}
