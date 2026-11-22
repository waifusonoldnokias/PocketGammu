package com.pocketgammu.core.nokia.ringtone

/**
 * RTTTL (Ring Tone Text Transfer Language) reader/writer.
 *
 * `name:d=4,o=5,b=125:8e6,8d#6,8e6,4p,...`
 *
 * Accepts both `8e6.` (Nokia spec order) and `8e.6`, `h` as an alias for `b`,
 * and the non-standard `s=` style key.
 */
object Rtttl {

    fun parse(text: String): Ringtone {
        val clean = text.trim().removePrefix("﻿")
        val parts = clean.split(':', limit = 3)
        if (parts.size < 3) throw RingtoneFormatException("RTTTL needs name:defaults:notes")
        val name = parts[0].trim().ifBlank { "Ringtone" }

        var defDuration = 4
        var defOctave = 6
        var bpm = 63
        var style = NoteStyle.NATURAL
        parts[1].split(',').map { it.trim().lowercase() }.filter { '=' in it }.forEach { kv ->
            val (k, v) = kv.split('=', limit = 2).map(String::trim)
            when (k) {
                "d" -> v.toIntOrNull()?.let { defDuration = it }
                "o" -> v.toIntOrNull()?.let { defOctave = it }
                "b" -> v.toIntOrNull()?.let { bpm = it }
                "s" -> style = when (v.firstOrNull()) { 'c' -> NoteStyle.CONTINUOUS; 's' -> NoteStyle.STACCATO; else -> NoteStyle.NATURAL }
            }
        }

        val notes = parts[2].split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.map { token ->
            parseNote(token, defDuration, defOctave)
        }
        return Ringtone(name = name, tempo = bpm.coerceIn(25, 900), style = style, notes = notes)
    }

    private val NOTE_RE = Regex("""^(\d{1,2})?([a-hp])(#?)(\.?)(\d)?(\.?)$""")

    private fun parseNote(token: String, defDuration: Int, defOctave: Int): Note {
        val m = NOTE_RE.matchEntire(token) ?: throw RingtoneFormatException("Bad RTTTL note \"$token\"")
        val (durStr, letter, sharp, dot1, octStr, dot2) = m.destructured
        val duration = NoteDuration.fromDenominator(durStr.toIntOrNull() ?: defDuration)
            ?: NoteDuration.fromDenominator(defDuration) ?: NoteDuration.QUARTER
        val spec = if (dot1.isNotEmpty() || dot2.isNotEmpty()) DurationSpec.DOTTED else DurationSpec.NONE
        if (letter == "p") return Note.rest(duration, spec)

        var octave = octStr.toIntOrNull() ?: defOctave
        var semitone = when (letter) {
            "c" -> 0; "d" -> 2; "e" -> 4; "f" -> 5; "g" -> 7; "a" -> 9; else -> 11 // b / h
        } + if (sharp.isNotEmpty()) 1 else 0
        if (semitone == 12) { semitone = 0; octave++ }     // b# -> next C
        return Note(NoteName.fromSemitone(semitone), octave.coerceIn(Note.MIN_OCTAVE, Note.MAX_OCTAVE), duration, spec)
    }

    /**
     * Writes standard RTTTL. Double-dotted notes are written as dotted and triplets as plain
     * notes, because RTTTL cannot express them.
     */
    fun write(ringtone: Ringtone): String {
        val notes = ringtone.notes
        val d = notes.groupingBy { it.duration.denominator }.eachCount().maxByOrNull { it.value }?.key ?: 4
        val o = notes.filterNot { it.isRest }.groupingBy { it.octave }.eachCount().maxByOrNull { it.value }?.key ?: 6
        val name = ringtone.name.replace(Regex("[:,]"), " ").trim().ifBlank { "PocketGammu" }
        val body = notes.joinToString(",") { n ->
            buildString {
                if (n.duration.denominator != d) append(n.duration.denominator)
                if (n.isRest) {
                    append('p')
                } else {
                    append(n.name.label.lowercase())
                    if (n.octave != o) append(n.octave)
                }
                if (n.spec == DurationSpec.DOTTED || n.spec == DurationSpec.DOUBLE_DOTTED) append('.')
            }
        }
        // Style is not part of standard RTTTL, so it is intentionally not exported.
        return "$name:d=$d,o=$o,b=${ringtone.tempo}:$body"
    }
}
