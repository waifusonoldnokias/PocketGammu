package com.pocketgammu.ui.ringtone

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketgammu.LocalAppContainer
import com.pocketgammu.core.designsystem.blur.RegisterOverlay
import com.pocketgammu.core.designsystem.components.ConnectedToggleGroup
import com.pocketgammu.core.designsystem.components.PgDialog
import com.pocketgammu.core.designsystem.components.PgTextField
import com.pocketgammu.core.designsystem.components.ToggleOption
import com.pocketgammu.core.designsystem.haptics.LocalPgHaptics
import com.pocketgammu.core.designsystem.theme.PgShapeSet
import com.pocketgammu.core.designsystem.theme.rememberShape
import com.pocketgammu.core.nokia.ringtone.DurationSpec
import com.pocketgammu.core.nokia.ringtone.NokiaTempo
import com.pocketgammu.core.nokia.ringtone.Note
import com.pocketgammu.core.nokia.ringtone.NoteDuration
import com.pocketgammu.core.nokia.ringtone.NoteStyle
import com.pocketgammu.core.nokia.ringtone.SmartRingtoneCodec
import com.pocketgammu.ui.LocalSnackbar
import com.pocketgammu.ui.common.SendSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RingtoneEditorScreen(onGoHome: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: RingtoneEditorViewModel = viewModel { RingtoneEditorViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalPgHaptics.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var showSend by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    val tone = state.ringtone

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Ringtone"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't open file")
                    name to bytes
                }
            }
            result.onSuccess { (name, bytes) ->
                val error = vm.importFile(bytes, name)
                if (error == null) haptics.confirm() else { haptics.reject(); snackbar.showSnackbar(error) }
            }.onFailure { haptics.reject(); snackbar.showSnackbar(it.message ?: "Couldn't open file") }
        }
    }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = vm.exportRtttl()
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
            }.onSuccess { haptics.confirm(); snackbar.showSnackbar("Saved RTTTL") }
                .onFailure { haptics.reject(); snackbar.showSnackbar("Couldn't save the file") }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // Top bar
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = { haptics.tick(); showRename = true },
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.weight(1f),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tone.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text("Ringtone", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
                    }
                    Icon(Icons.Rounded.Edit, "Rename", Modifier.size(18.dp))
                }
            }
            IconButton(onClick = { haptics.click(); openFile.launch(arrayOf("*/*")) }) { Icon(Icons.Rounded.FolderOpen, "Import TXT, MID or NRT") }
            IconButton(onClick = { haptics.click(); saveFile.launch("${tone.name.ifBlank { "ringtone" }}.txt") }) { Icon(Icons.Rounded.Save, "Export RTTTL") }
            FilledIconButton(onClick = { haptics.click(); showSend = true }) { Icon(Icons.AutoMirrored.Rounded.Send, "Send ringtone") }
        }

        // Song settings
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val idx = NokiaTempo.indexOf(tone.tempo)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${tone.tempo} bpm", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(76.dp))
                    Slider(
                        value = idx.toFloat(),
                        onValueChange = { v ->
                            val bpm = NokiaTempo.values[v.roundToInt()]
                            if (bpm != tone.tempo) { haptics.tick(); vm.setTempo(bpm) }
                        },
                        valueRange = 0f..NokiaTempo.values.lastIndex.toFloat(),
                        steps = NokiaTempo.values.size - 2,
                        modifier = Modifier.weight(1f),
                    )
                }
                ConnectedToggleGroup(
                    options = NoteStyle.entries.map { ToggleOption(it, it.label) },
                    selected = tone.style,
                    onSelect = vm::setStyle,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.overLimit) Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp).padding(end = 4.dp))
                    Text(
                        "${tone.notes.size} notes · ${"%.1f".format(tone.durationSeconds)} s · ${state.encodedBytes} bytes · ${state.smsParts} SMS" +
                            if (state.overLimit) " · phones keep the first ${SmartRingtoneCodec.MAX_NOTES}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.overLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Note track
        NoteTrack(
            notes = tone.notes,
            cursor = state.cursor,
            editing = state.editing,
            playing = state.playingIndex,
            onSelect = { haptics.tick(); vm.selectNote(it) },
            onEnd = { haptics.tick(); vm.moveCursorToEnd() },
            modifier = Modifier.weight(1f).fillMaxWidth().heightIn(min = 84.dp),
        )

        // Transport + editing actions
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LargeFloatingActionButton(
                onClick = { haptics.click(); vm.togglePlay() },
                shape = rememberShape(if (state.isPlaying) PgShapeSet.music else PgShapeSet.connecting),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(72.dp),
            ) { Icon(if (state.isPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, if (state.isPlaying) "Stop" else "Play", Modifier.size(32.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { haptics.tick(); vm.undo() }, enabled = state.canUndo) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo") }
                    IconButton(onClick = { haptics.tick(); vm.redo() }, enabled = state.canRedo) { Icon(Icons.AutoMirrored.Rounded.Redo, "Redo") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { haptics.click(); vm.insertRest() }) { Text(if (state.editing != null) "Make rest" else "Rest") }
                    FilledTonalIconButton(onClick = { haptics.click(); vm.backspace() }) { Icon(Icons.AutoMirrored.Rounded.Backspace, "Delete note") }
                    MoreMenu(onClear = { haptics.longPress(); vm.clear() })
                }
            }
        }

        // Entry panel: duration, dot, octave, keyboard
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ConnectedToggleGroup(
                options = NoteDuration.entries.map { ToggleOption(it, it.label) },
                selected = state.duration,
                onSelect = vm::setDuration,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleButton(
                    checked = state.dotted,
                    onCheckedChange = { haptics.tick(); vm.toggleDot() },
                            ) { Text("Dotted") }
                Spacer(Modifier.weight(1f))
                FilledTonalIconButton(onClick = { haptics.tick(); vm.shiftOctave(-1) }, enabled = state.octave > Note.MIN_OCTAVE) { Icon(Icons.Rounded.Remove, "Octave down") }
                Text("Octave ${state.octave}", style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, modifier = Modifier.width(76.dp))
                FilledTonalIconButton(onClick = { haptics.tick(); vm.shiftOctave(1) }, enabled = state.octave < Note.MAX_OCTAVE) { Icon(Icons.Rounded.Add, "Octave up") }
            }
            if (state.editing != null) {
                Text(
                    "Editing note ${state.editing!! + 1} — tap it again to finish",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            PianoKeyboard(
                octave = state.octave,
                onKey = { haptics.click(); vm.pressKey(it) },
                modifier = Modifier.height(128.dp).padding(bottom = 8.dp),
            )
        }
    }

    if (showSend) {
        SendSheet(
            title = "Send ringtone",
            itemDescription = "${tone.name} · ${tone.notes.size} notes",
            needsNetworkCode = false,
            smsUnsupportedReason = if (tone.notes.isEmpty()) "Add some notes first." else null,
            buildSms = { _ -> vm.smartMessage() },
            sendCable = { _ -> vm.sendToPhone() },
            onDismiss = { showSend = false },
            onGoHome = onGoHome,
        )
    }

    if (showRename) {
        var name by rememberSaveable { mutableStateOf(tone.name) }
        PgDialog(
            title = "Ringtone name",
            onDismiss = { showRename = false },
            confirm = { TextButton(onClick = { vm.rename(name); showRename = false }) { Text("Save") } },
            dismiss = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
        ) {
            PgTextField(value = name, onValueChange = { name = it.take(15) }, label = "Name", supporting = "Up to 15 characters")
        }
    }

    state.pendingMidi?.let { pending ->
        PgDialog(
            title = "Choose melody",
            onDismiss = vm::dismissMidi,
            confirm = { TextButton(onClick = vm::dismissMidi) { Text("Cancel") } },
        ) {
            Text("Nokia ringtones are monophonic. Pick the track that carries the tune.", style = MaterialTheme.typography.bodyMedium)
            TrackOption("All tracks (top line)", "Highest note at any moment") { vm.chooseMidiTrack(null) }
            pending.file.tracks.forEach { t -> TrackOption(t.name, "${t.noteCount} notes") { vm.chooseMidiTrack(t.index) } }
        }
    }
}

@Composable
private fun TrackOption(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MoreMenu(onClear: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.large) {
            RegisterOverlay()
            DropdownMenuItem(text = { Text("Clear all notes") }, leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) }, onClick = { open = false; onClear() })
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NoteTrack(
    notes: List<Note>,
    cursor: Int,
    editing: Int?,
    playing: Int?,
    onSelect: (Int) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val focus = playing ?: editing ?: (cursor - 1).coerceAtLeast(0)
    LaunchedEffect(focus, notes.size) {
        if (notes.isNotEmpty()) listState.animateScrollToItem((focus - 2).coerceAtLeast(0))
    }
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(notes) { i, note ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (editing == null && cursor == i) Caret()
                    NoteTile(note, selected = editing == i, playing = playing == i, onClick = { onSelect(i) })
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (editing == null && cursor == notes.size) Caret()
                    Box(
                        Modifier
                            .size(56.dp, 72.dp)
                            .clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable(onClick = onEnd),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Add, "Insert at end", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun Caret() {
    Box(
        Modifier
            .padding(end = 6.dp)
            .width(4.dp)
            .fillMaxHeight(0.7f)
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.primary),
    )
}

@Composable
private fun NoteTile(note: Note, selected: Boolean, playing: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        when {
            playing -> scheme.tertiary
            selected -> scheme.primary
            note.isRest -> scheme.surfaceContainer
            else -> scheme.secondaryContainer
        },
        label = "tile",
    )
    val fg = when {
        playing -> scheme.onTertiary
        selected -> scheme.onPrimary
        else -> scheme.onSecondaryContainer
    }
    val shape = if (selected || playing) rememberShape(PgShapeSet.selectedNote) else MaterialTheme.shapes.large
    Column(
        Modifier
            .size(56.dp, 72.dp)
            .clip(shape)
            .background(bg)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(if (note.isRest) "—" else note.label(), style = MaterialTheme.typography.titleSmall, color = fg)
        val dot = if (note.spec == DurationSpec.DOTTED || note.spec == DurationSpec.DOUBLE_DOTTED) "." else ""
        Text(note.duration.label + dot, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.8f))
    }
}
