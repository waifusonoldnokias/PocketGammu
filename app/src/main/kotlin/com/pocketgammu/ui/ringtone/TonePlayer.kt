package com.pocketgammu.ui.ringtone

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.pocketgammu.core.nokia.ringtone.Note
import com.pocketgammu.core.nokia.ringtone.NoteStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Sine-wave preview: soft on the ears (the old square wave mimicked the DCT3 buzzer but
 * sounded harsh). Each note fades in and out over a few ms so there are no clicks.
 * Streaming writes block, which paces playback and lets [play] report the current note.
 */
class TonePlayer {
    private val rate = 22_050

    suspend fun play(notes: List<Note>, tempo: Int, style: NoteStyle, onNote: (Int) -> Unit) = withContext(Dispatchers.Default) {
        val track = newTrack()
        try {
            track.play()
            notes.forEachIndexed { i, note ->
                ensureActive()
                onNote(i)
                val pcm = render(note, note.beats * 60.0 / tempo, style)
                track.write(pcm, 0, pcm.size)
            }
            // Let the buffer drain before releasing.
            val tail = ShortArray(rate / 10)
            track.write(tail, 0, tail.size)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    suspend fun beep(note: Note) = withContext(Dispatchers.Default) {
        val track = newTrack()
        try {
            track.play()
            val pcm = render(note, 0.18, NoteStyle.CONTINUOUS)
            track.write(pcm, 0, pcm.size)
            val tail = ShortArray(rate / 20)
            track.write(tail, 0, tail.size)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun render(note: Note, seconds: Double, style: NoteStyle): ShortArray {
        val total = (seconds * rate).toInt().coerceAtLeast(1)
        val out = ShortArray(total)
        if (note.isRest) return out
        val sounding = (total * when (style) {
            NoteStyle.NATURAL -> 0.9
            NoteStyle.CONTINUOUS -> 1.0
            NoteStyle.STACCATO -> 0.5
        }).toInt()
        val step = 2.0 * PI * note.frequencyHz / rate
        val ramp = min(rate / 200, sounding / 4).coerceAtLeast(1)   // ~5 ms fade
        val amp = 0.35 * Short.MAX_VALUE
        for (i in 0 until sounding) {
            val env = when {
                i < ramp -> i.toDouble() / ramp
                i > sounding - ramp -> (sounding - i).toDouble() / ramp
                else -> 1.0
            }
            out[i] = (sin(step * i) * amp * env).toInt().toShort()
        }
        return out
    }

    private fun newTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(min * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }
}
