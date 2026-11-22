package com.pocketgammu.core.nokia.ringtone

import kotlin.math.abs
import kotlin.math.pow

/** Note names in Smart Messaging order. [code] is the 4-bit OTT value. */
enum class NoteName(val code: Int, val label: String, val semitone: Int, val sharp: Boolean = false) {
    PAUSE(0, "–", -1),
    C(1, "C", 0), CIS(2, "C#", 1, true), D(3, "D", 2), DIS(4, "D#", 3, true), E(5, "E", 4),
    F(6, "F", 5), FIS(7, "F#", 6, true), G(8, "G", 7), GIS(9, "G#", 8, true), A(10, "A", 9),
    AIS(11, "A#", 10, true), H(12, "B", 11),
    ;

    companion object {
        val pitched: List<NoteName> = entries.filter { it != PAUSE }
        fun fromCode(code: Int): NoteName = entries.firstOrNull { it.code == code } ?: PAUSE
        fun fromSemitone(s: Int): NoteName = pitched[((s % 12) + 12) % 12]
    }
}

/** Basic note value. [code] is the 3-bit OTT value; [denominator] 4 = quarter note. */
enum class NoteDuration(val code: Int, val denominator: Int, val label: String) {
    WHOLE(0, 1, "1"), HALF(1, 2, "1/2"), QUARTER(2, 4, "1/4"),
    EIGHTH(3, 8, "1/8"), SIXTEENTH(4, 16, "1/16"), THIRTY_SECOND(5, 32, "1/32"),
    ;

    /** Length in 1/32 notes. */
    val thirtySeconds: Int get() = 32 / denominator

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: QUARTER
        fun fromDenominator(d: Int) = entries.firstOrNull { it.denominator == d }
    }
}

enum class DurationSpec(val code: Int, val factor: Double) {
    NONE(0, 1.0), DOTTED(1, 1.5), DOUBLE_DOTTED(2, 1.75), TRIPLET(3, 2.0 / 3.0);

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: NONE
    }
}

enum class NoteStyle(val code: Int, val label: String) {
    NATURAL(0, "Natural"), CONTINUOUS(1, "Continuous"), STACCATO(2, "Staccato");

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: NATURAL
    }
}

/**
 * One note or rest. Octaves follow RTTTL numbering; Nokia phones play octaves 4–7
 * (Smart Messaging scales A=440 … A=3520 Hz).
 */
data class Note(
    val name: NoteName,
    val octave: Int = 5,
    val duration: NoteDuration = NoteDuration.QUARTER,
    val spec: DurationSpec = DurationSpec.NONE,
) {
    val isRest: Boolean get() = name == NoteName.PAUSE

    /** Length in beats (quarter note = 1 beat). */
    val beats: Double get() = 4.0 / duration.denominator * spec.factor

    /** MIDI note number, or null for a rest. */
    val midi: Int? get() = if (isRest) null else (octave + 1) * 12 + name.semitone

    val frequencyHz: Double get() = midi?.let { 440.0 * 2.0.pow((it - 69) / 12.0) } ?: 0.0

    fun label(): String = if (isRest) "Rest" else "${name.label}$octave"

    companion object {
        const val MIN_OCTAVE = 4
        const val MAX_OCTAVE = 7
        fun rest(duration: NoteDuration = NoteDuration.QUARTER, spec: DurationSpec = DurationSpec.NONE) =
            Note(NoteName.PAUSE, 5, duration, spec)
    }
}

data class Ringtone(
    val name: String = "PocketGammu",
    val tempo: Int = 125,
    val style: NoteStyle = NoteStyle.NATURAL,
    val notes: List<Note> = emptyList(),
) {
    val durationSeconds: Double get() = notes.sumOf { it.beats } * 60.0 / tempo
}

/** Tempo values representable in Smart Messaging (5-bit index). */
object NokiaTempo {
    val values: List<Int> = listOf(
        25, 28, 31, 35, 40, 45, 50, 56, 63, 70, 80, 90, 100, 112, 125, 140,
        160, 180, 200, 225, 250, 285, 320, 355, 400, 450, 500, 565, 635, 715, 800, 900,
    )

    /** Same rounding rule as Gammu's GSM_RTTLGetTempo: first value >= bpm. */
    fun indexOf(bpm: Int): Int = values.indexOfFirst { bpm <= it }.let { if (it < 0) values.lastIndex else it }

    fun nearest(bpm: Int): Int = values.minBy { abs(it - bpm) }
}
