package com.pocketgammu.core.nokia.ringtone

import com.pocketgammu.core.nokia.sms.SmartMessage

/**
 * Nokia Smart Messaging ringtone (OTT) codec — a Kotlin port of Gammu's
 * GSM_EncodeNokiaRTTLRingtone / GSM_DecodeNokiaRTTLRingtone (gsmring.c).
 */
object SmartRingtoneCodec {
    const val PORT = 0x1581

    private const val CMD_RINGING_TONE_PROGRAMMING = 0x25
    private const val CMD_SOUND = 0x1D
    private const val SONG_BASIC = 0x01
    private const val INS_PATTERN_HEADER = 0
    private const val INS_NOTE = 1
    private const val INS_SCALE = 2
    private const val INS_STYLE = 3
    private const val INS_TEMPO = 4
    private const val INS_VOLUME = 5

    /** Gammu stops at 129 notes for SMS/phone uploads ("phone limitations", per PC Composer). */
    const val MAX_NOTES = 129
    private const val MAX_INSTRUCTIONS = 255
    private const val MAX_NAME = 15

    data class Encoded(val bytes: ByteArray, val notesWritten: Int, val notesTotal: Int) {
        val truncated: Boolean get() = notesWritten < notesTotal
    }

    fun encode(ringtone: Ringtone, maxBytes: Int = Int.MAX_VALUE, loop: Int = 0): Encoded {
        val w = BitWriter()
        w.write(0x02, 8)                              // command-part count
        w.write(CMD_RINGING_TONE_PROGRAMMING, 7)
        w.align()
        w.write(CMD_SOUND, 7)
        w.write(SONG_BASIC, 3)

        val name = sanitizeName(ringtone.name)
        w.write(name.length, 4)
        name.forEach { w.write(it.code, 8) }

        w.write(1, 8)                                 // one song pattern
        w.write(INS_PATTERN_HEADER, 3)
        w.write(0, 2)                                 // pattern A
        w.write(loop.coerceIn(0, 15), 4)
        val countPos = w.bitPosition
        w.write(0, 8)                                 // instruction count, patched below

        var instructions = 0
        var written = 0
        var curScale = -1
        var curStyle = -1
        var curTempo = -1
        val tempoIndex = NokiaTempo.indexOf(ringtone.tempo)

        // Like Gammu: never start with a rest (it confuses PC Composer and some phones).
        val notes = ringtone.notes.dropWhile { it.isRest }

        fun fits(extraBits: Int): Boolean {
            val endBits = w.bitPosition + extraBits + 8 // + command end
            return (endBits + 7) / 8 <= maxBytes
        }

        for (note in notes) {
            if (written >= MAX_NOTES) break
            var needed = 12
            val scale = (note.octave.coerceIn(Note.MIN_OCTAVE, Note.MAX_OCTAVE) - Note.MIN_OCTAVE)
            val needScale = !note.isRest && scale != curScale
            val needStyle = !note.isRest && ringtone.style.code != curStyle
            val needTempo = tempoIndex != curTempo
            if (needScale) needed += 5
            if (needStyle) needed += 5
            if (needTempo) needed += 8
            val extraInstr = 1 + listOf(needScale, needStyle, needTempo).count { it }
            if (!fits(needed) || instructions + extraInstr > MAX_INSTRUCTIONS) break

            if (needScale) { w.write(INS_SCALE, 3); w.write(scale, 2); curScale = scale; instructions++ }
            if (needStyle) { w.write(INS_STYLE, 3); w.write(ringtone.style.code, 2); curStyle = ringtone.style.code; instructions++ }
            if (needTempo) { w.write(INS_TEMPO, 3); w.write(tempoIndex, 5); curTempo = tempoIndex; instructions++ }
            w.write(INS_NOTE, 3)
            w.write(note.name.code, 4)
            w.write(note.duration.code, 3)
            w.write(note.spec.code, 2)
            instructions++
            written++
        }

        w.align()
        w.write(0x00, 8)                              // command end
        w.patch(countPos, instructions, 8)
        return Encoded(w.toByteArray(), notesWritten = written, notesTotal = notes.size)
    }

    fun toSmartMessage(ringtone: Ringtone): SmartMessage = SmartMessage(PORT, encode(ringtone).bytes)

    fun decode(data: ByteArray, offset: Int = 0): Ringtone {
        val r = BitReader(data, offset)
        if (r.read(8) != 0x02) throw RingtoneFormatException("Not a Smart Messaging ringtone (header)")
        if (r.read(7) != CMD_RINGING_TONE_PROGRAMMING) throw RingtoneFormatException("Missing ringing-tone-programming command")
        r.align()
        if (r.read(7) != CMD_SOUND) throw RingtoneFormatException("Missing sound command")
        if (r.read(3) != SONG_BASIC) throw RingtoneFormatException("Only basic song type is supported")
        val nameLen = r.read(4)
        val name = buildString { repeat(nameLen) { append(r.read(8).toChar()) } }
        val patterns = r.read(8)
        if (patterns != 1) throw RingtoneFormatException("Only single-pattern ringtones are supported ($patterns)")
        if (r.read(3) != INS_PATTERN_HEADER) throw RingtoneFormatException("Missing pattern header")
        r.skip(2)                                     // pattern id
        r.read(4)                                     // loop
        val count = r.read(8)

        var octave = 5                                // Gammu default Scale_880
        var style: NoteStyle? = null
        var tempo: Int? = null
        val notes = ArrayList<Note>()
        repeat(count) {
            when (val id = r.read(3)) {
                INS_VOLUME -> r.skip(4)
                INS_STYLE -> NoteStyle.fromCode(r.read(2)).also { if (style == null) style = it }
                INS_TEMPO -> NokiaTempo.values[r.read(5)].also { if (tempo == null) tempo = it }
                INS_SCALE -> octave = Note.MIN_OCTAVE + r.read(2)
                INS_NOTE -> {
                    val n = NoteName.fromCode(r.read(4))
                    val d = NoteDuration.fromCode(r.read(3))
                    val s = DurationSpec.fromCode(r.read(2))
                    notes += Note(n, octave, d, s)
                }
                else -> throw RingtoneFormatException("Unsupported instruction $id")
            }
        }
        return Ringtone(
            name = name.ifBlank { "Ringtone" },
            tempo = tempo ?: 63,
            style = style ?: NoteStyle.NATURAL,
            notes = notes,
        )
    }

    private fun sanitizeName(name: String): String =
        name.filter { it.code in 0x20..0x7E }.take(MAX_NAME)
}
