package com.pocketgammu.core.nokia.ringtone

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Converts a Standard MIDI File into a monophonic Nokia ringtone.
 *
 * Strategy: collect note intervals (drums on channel 10 are ignored), reduce them to a
 * single "top line" melody (the highest sounding note wins), quantise to 1/32 notes,
 * then shift octaves so as many notes as possible land in the Nokia range (4–7).
 */
object MidiImporter {

    data class MidiTrackInfo(val index: Int, val name: String, val noteCount: Int)

    data class MidiFile(
        val ticksPerQuarter: Int,
        val microsPerQuarter: Int,
        val tracks: List<MidiTrackInfo>,
        val notes: List<NoteEvent>,
    ) {
        val bpm: Int get() = (60_000_000.0 / microsPerQuarter).roundToInt()
    }

    data class NoteEvent(val track: Int, val start: Long, val end: Long, val pitch: Int)

    fun read(bytes: ByteArray): MidiFile = Parser(bytes).parse()

    /**
     * @param trackIndex limit to one track, or null to merge every non-drum track.
     */
    fun toRingtone(file: MidiFile, name: String, trackIndex: Int? = null): Ringtone {
        val source = file.notes.filter { trackIndex == null || it.track == trackIndex }
        val melody = topLine(source)
        val unit = (file.ticksPerQuarter / 8.0).coerceAtLeast(1.0)   // ticks per 1/32 note

        val octaveShift = bestOctaveShift(melody.map { it.pitch })
        val out = ArrayList<Note>()
        var cursor = 0L                                             // in 1/32 units
        val firstStart = melody.firstOrNull()?.start ?: 0L
        for (ev in melody) {
            val start = ((ev.start - firstStart) / unit).roundToInt().toLong()
            val gap = start - cursor
            if (gap > 0) { out += restsFor(gap.toInt()); cursor += gap }
            val len = ((ev.end - ev.start) / unit).roundToInt().coerceAtLeast(1)
            val (dur, spec, used) = nearestValue(len)
            val pitch = ev.pitch + octaveShift * 12
            val octave = (pitch / 12 - 1).coerceIn(Note.MIN_OCTAVE, Note.MAX_OCTAVE)
            out += Note(NoteName.fromSemitone(pitch % 12), octave, dur, spec)
            cursor += used
        }
        return Ringtone(
            name = name.take(15),
            tempo = NokiaTempo.nearest(file.bpm),
            style = NoteStyle.NATURAL,
            notes = out,
        )
    }

    fun import(bytes: ByteArray, name: String, trackIndex: Int? = null): Ringtone =
        toRingtone(read(bytes), name, trackIndex)

    // --- melody reduction -------------------------------------------------

    internal fun topLine(events: List<NoteEvent>): List<NoteEvent> {
        val sorted = events.sortedWith(compareBy<NoteEvent> { it.start }.thenByDescending { it.pitch })
        val result = ArrayList<NoteEvent>()
        for (ev in sorted) {
            val last = result.lastOrNull()
            when {
                last == null || ev.start >= last.end -> result += ev
                ev.start == last.start -> Unit                       // chord: keep the higher (already first)
                ev.pitch > last.pitch -> {                           // higher note interrupts
                    result[result.lastIndex] = last.copy(end = ev.start)
                    result += ev
                }
                ev.end > last.end -> result += ev.copy(start = last.end)  // lower note sustains past the top note
            }
        }
        return result.filter { it.end > it.start }
    }

    private fun bestOctaveShift(pitches: List<Int>): Int {
        if (pitches.isEmpty()) return 0
        val lo = (Note.MIN_OCTAVE + 1) * 12
        val hi = (Note.MAX_OCTAVE + 2) * 12 - 1
        return (-5..5).maxWith(
            compareBy<Int> { k -> pitches.count { it + k * 12 in lo..hi } }.thenBy { -abs(it) },
        )
    }

    // --- quantisation -----------------------------------------------------

    private data class Value(val duration: NoteDuration, val spec: DurationSpec, val units: Int)

    private val VALUES: List<Value> = buildList {
        for (d in NoteDuration.entries) {
            add(Value(d, DurationSpec.NONE, d.thirtySeconds))
            if (d.thirtySeconds >= 2) add(Value(d, DurationSpec.DOTTED, d.thirtySeconds * 3 / 2))
        }
    }.sortedByDescending { it.units }

    private fun nearestValue(units: Int): Triple<NoteDuration, DurationSpec, Int> {
        val v = VALUES.minWith(compareBy<Value> { abs(it.units - units) }.thenBy { it.spec != DurationSpec.NONE })
        return Triple(v.duration, v.spec, v.units)
    }

    private fun restsFor(units: Int): List<Note> {
        var left = units
        val rests = ArrayList<Note>()
        while (left > 0) {
            val v = VALUES.first { it.units <= left }
            rests += Note.rest(v.duration, v.spec)
            left -= v.units
        }
        return rests
    }

    // --- SMF parser -------------------------------------------------------

    private class Parser(private val b: ByteArray) {
        private var p = 0

        fun parse(): MidiFile {
            if (tag() != "MThd") throw RingtoneFormatException("Not a MIDI file")
            val hdrLen = u32()
            u16() // format
            val ntrks = u16()
            val division = u16()
            p += hdrLen - 6
            val tpq = if (division and 0x8000 != 0) 480 else division.coerceAtLeast(1)

            var tempo = -1
            val tracks = ArrayList<MidiTrackInfo>()
            val notes = ArrayList<NoteEvent>()
            for (t in 0 until ntrks) {
                if (p + 8 > b.size) break
                val id = tag()
                val len = u32()
                val end = (p + len).coerceAtMost(b.size)
                if (id != "MTrk") { p = end; continue }
                var tick = 0L
                var running = 0
                var name = "Track ${t + 1}"
                val open = HashMap<Int, Long>()               // key = channel*128+pitch
                var count = 0
                while (p < end) {
                    tick += vlq()
                    var status = u8()
                    if (status < 0x80) { p--; status = running } else if (status < 0xF0) running = status
                    when {
                        status == 0xFF -> {
                            val type = u8(); val l = vlq().toInt()
                            if (type == 0x51 && l == 3 && tempo < 0) tempo = (u8() shl 16) or (u8() shl 8) or u8()
                            else if (type == 0x03) { name = String(b, p, l, Charsets.ISO_8859_1).trim().ifBlank { name }; p += l }
                            else p += l
                            if (type == 0x2F) break
                        }
                        status == 0xF0 || status == 0xF7 -> p += vlq().toInt()
                        else -> {
                            val ch = status and 0x0F
                            when (status and 0xF0) {
                                0x80, 0x90 -> {
                                    val key = u8(); val vel = u8()
                                    val k = ch * 128 + key
                                    val isOn = (status and 0xF0) == 0x90 && vel > 0
                                    open.remove(k)?.let { s ->
                                        if (ch != 9 && tick > s) { notes += NoteEvent(t, s, tick, key); count++ }
                                    }
                                    if (isOn) open[k] = tick
                                }
                                0xA0, 0xB0, 0xE0 -> p += 2
                                0xC0, 0xD0 -> p += 1
                                else -> p++
                            }
                        }
                    }
                }
                p = end
                tracks += MidiTrackInfo(t, name, count)
            }
            if (notes.isEmpty()) throw RingtoneFormatException("MIDI file contains no melodic notes")
            return MidiFile(tpq, if (tempo > 0) tempo else 500_000, tracks.filter { it.noteCount > 0 }, notes)
        }

        private fun u8(): Int = if (p < b.size) b[p++].toInt() and 0xFF else throw RingtoneFormatException("Truncated MIDI file")
        private fun u16() = (u8() shl 8) or u8()
        private fun u32() = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
        private fun tag() = String(CharArray(4) { u8().toChar() })
        private fun vlq(): Long {
            var v = 0L
            repeat(4) {
                val c = u8()
                v = (v shl 7) or (c and 0x7F).toLong()
                if (c and 0x80 == 0) return v
            }
            return v
        }
    }
}
