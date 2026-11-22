package com.pocketgammu.ui.ringtone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketgammu.AppContainer
import com.pocketgammu.core.nokia.ringtone.DurationSpec
import com.pocketgammu.core.nokia.ringtone.MidiImporter
import com.pocketgammu.core.nokia.ringtone.Note
import com.pocketgammu.core.nokia.ringtone.NoteDuration
import com.pocketgammu.core.nokia.ringtone.NoteName
import com.pocketgammu.core.nokia.ringtone.NoteStyle
import com.pocketgammu.core.nokia.ringtone.Ringtone
import com.pocketgammu.core.nokia.ringtone.RingtoneFileType
import com.pocketgammu.core.nokia.ringtone.RingtoneImporter
import com.pocketgammu.core.nokia.ringtone.Rtttl
import com.pocketgammu.core.nokia.ringtone.SmartRingtoneCodec
import com.pocketgammu.core.nokia.sms.SckTextEncoder
import com.pocketgammu.core.nokia.sms.SmartMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PendingMidi(val file: MidiImporter.MidiFile, val name: String)

data class RingtoneUiState(
    val ringtone: Ringtone = Ringtone(),
    /** Insert position (0..notes.size). */
    val cursor: Int = 0,
    /** Index of the note being edited, or null in insert mode. */
    val editing: Int? = null,
    val duration: NoteDuration = NoteDuration.QUARTER,
    val dotted: Boolean = false,
    val octave: Int = 5,
    val playingIndex: Int? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val pendingMidi: PendingMidi? = null,
) {
    val encodedBytes: Int get() = SmartRingtoneCodec.encode(ringtone).bytes.size
    val smsParts: Int get() = SckTextEncoder.partCount(SmartRingtoneCodec.toSmartMessage(ringtone))
    val overLimit: Boolean get() = ringtone.notes.size > SmartRingtoneCodec.MAX_NOTES
    val isPlaying: Boolean get() = playingIndex != null
}

class RingtoneEditorViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(RingtoneUiState(ringtone = Rtttl.parse(DEMO), cursor = Rtttl.parse(DEMO).notes.size))
    val state: StateFlow<RingtoneUiState> = _state.asStateFlow()

    private val player = TonePlayer()
    private var playJob: Job? = null
    private var beepJob: Job? = null
    private val undo = ArrayDeque<Ringtone>()
    private val redo = ArrayDeque<Ringtone>()

    private val s get() = _state.value

    private fun edit(newTone: Ringtone, cursor: Int = s.cursor, editing: Int? = s.editing) {
        undo.addLast(s.ringtone); if (undo.size > 100) undo.removeFirst()
        redo.clear()
        _state.update { it.copy(ringtone = newTone, cursor = cursor.coerceIn(0, newTone.notes.size), editing = editing, canUndo = true, canRedo = false) }
    }

    fun undo() {
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(s.ringtone)
        _state.update { it.copy(ringtone = prev, cursor = prev.notes.size, editing = null, canUndo = undo.isNotEmpty(), canRedo = true) }
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(s.ringtone)
        _state.update { it.copy(ringtone = next, cursor = next.notes.size, editing = null, canUndo = true, canRedo = redo.isNotEmpty()) }
    }

    private val spec get() = if (s.dotted) DurationSpec.DOTTED else DurationSpec.NONE

    /** Piano key: replaces the edited note's pitch, or inserts a new note at the cursor. */
    fun pressKey(semitone: Int) {
        val note = Note(NoteName.fromSemitone(semitone), s.octave, s.duration, spec)
        beep(note)
        val notes = s.ringtone.notes.toMutableList()
        val e = s.editing
        if (e != null) {
            notes[e] = notes[e].copy(name = note.name, octave = note.octave)
            edit(s.ringtone.copy(notes = notes))
        } else {
            notes.add(s.cursor, note)
            edit(s.ringtone.copy(notes = notes), cursor = s.cursor + 1)
        }
    }

    fun insertRest() {
        val notes = s.ringtone.notes.toMutableList()
        val e = s.editing
        if (e != null) {
            notes[e] = Note.rest(notes[e].duration, notes[e].spec)
            edit(s.ringtone.copy(notes = notes))
        } else {
            notes.add(s.cursor, Note.rest(s.duration, spec))
            edit(s.ringtone.copy(notes = notes), cursor = s.cursor + 1)
        }
    }

    fun backspace() {
        val notes = s.ringtone.notes.toMutableList()
        val e = s.editing
        when {
            e != null -> { notes.removeAt(e); edit(s.ringtone.copy(notes = notes), cursor = e, editing = null) }
            s.cursor > 0 -> { notes.removeAt(s.cursor - 1); edit(s.ringtone.copy(notes = notes), cursor = s.cursor - 1) }
        }
    }

    fun selectNote(index: Int) {
        if (s.editing == index) { finishEdit(); return }
        val n = s.ringtone.notes.getOrNull(index) ?: return
        _state.update {
            it.copy(
                editing = index,
                duration = n.duration,
                dotted = n.spec == DurationSpec.DOTTED || n.spec == DurationSpec.DOUBLE_DOTTED,
                octave = if (n.isRest) it.octave else n.octave,
            )
        }
        if (!n.isRest) beep(n)
    }

    fun finishEdit() = _state.update { it.copy(cursor = (it.editing ?: (it.cursor - 1)) + 1, editing = null) }

    fun moveCursorToEnd() = _state.update { it.copy(cursor = it.ringtone.notes.size, editing = null) }

    fun setDuration(d: NoteDuration) {
        _state.update { it.copy(duration = d) }
        applyToEdited { it.copy(duration = d) }
    }

    fun toggleDot() {
        _state.update { it.copy(dotted = !it.dotted) }
        applyToEdited { it.copy(spec = spec) }
    }

    fun shiftOctave(delta: Int) {
        val o = (s.octave + delta).coerceIn(Note.MIN_OCTAVE, Note.MAX_OCTAVE)
        _state.update { it.copy(octave = o) }
        applyToEdited { if (it.isRest) it else it.copy(octave = o) }
    }

    private fun applyToEdited(change: (Note) -> Note) {
        val e = s.editing ?: return
        val notes = s.ringtone.notes.toMutableList()
        notes[e] = change(notes[e])
        edit(s.ringtone.copy(notes = notes))
    }

    fun setTempo(bpm: Int) { if (bpm != s.ringtone.tempo) edit(s.ringtone.copy(tempo = bpm)) }
    fun setStyle(style: NoteStyle) { if (style != s.ringtone.style) edit(s.ringtone.copy(style = style)) }
    fun rename(name: String) = edit(s.ringtone.copy(name = name.take(15)))
    fun clear() = edit(s.ringtone.copy(notes = emptyList()), cursor = 0, editing = null)

    // --- playback --------------------------------------------------------

    fun togglePlay() {
        if (s.isPlaying) { stop(); return }
        val from = s.editing ?: 0
        val notes = s.ringtone.notes.drop(from)
        if (notes.isEmpty()) return
        playJob = viewModelScope.launch {
            try {
                player.play(notes, s.ringtone.tempo, s.ringtone.style) { i -> _state.update { it.copy(playingIndex = from + i) } }
            } finally {
                _state.update { it.copy(playingIndex = null) }
            }
        }
    }

    fun stop() { playJob?.cancel(); playJob = null }

    private fun beep(note: Note) {
        if (s.isPlaying) return
        beepJob?.cancel()
        beepJob = viewModelScope.launch { runCatching { player.beep(note) } }
    }

    // --- files -----------------------------------------------------------

    /** Returns an error message, or null on success. */
    fun importFile(bytes: ByteArray, fileName: String): String? = runCatching {
        if (RingtoneImporter.detect(bytes) == RingtoneFileType.MIDI) {
            val midi = MidiImporter.read(bytes)
            val name = fileName.substringBeforeLast('.').take(15)
            if (midi.tracks.size > 1) {
                _state.update { it.copy(pendingMidi = PendingMidi(midi, name)) }
                return null
            }
            loadImported(MidiImporter.toRingtone(midi, name))
        } else {
            loadImported(RingtoneImporter.import(bytes, fileName))
        }
    }.exceptionOrNull()?.let { it.message ?: "Couldn't read that file" }

    fun chooseMidiTrack(trackIndex: Int?) {
        val p = s.pendingMidi ?: return
        _state.update { it.copy(pendingMidi = null) }
        loadImported(MidiImporter.toRingtone(p.file, p.name, trackIndex))
    }

    fun dismissMidi() = _state.update { it.copy(pendingMidi = null) }

    private fun loadImported(tone: Ringtone) {
        stop()
        edit(tone, cursor = tone.notes.size, editing = null)
    }

    fun exportRtttl(): String = Rtttl.write(s.ringtone)

    fun smartMessage(): SmartMessage = SmartRingtoneCodec.toSmartMessage(s.ringtone)

    suspend fun sendToPhone(): Result<Unit> = container.phone.setRingtone(s.ringtone)

    override fun onCleared() { stop(); beepJob?.cancel() }

    private companion object {
        const val DEMO = "PocketGammu:d=8,o=5,b=140:e6,d6,4f#,4g#,c#6,b,4d,4e,b,a,4c#,4e,2a"
    }
}
