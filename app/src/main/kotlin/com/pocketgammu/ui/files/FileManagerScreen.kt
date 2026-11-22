package com.pocketgammu.ui.files

import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.UsbOff
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketgammu.LocalAppContainer
import com.pocketgammu.core.designsystem.components.EmptyState
import com.pocketgammu.core.designsystem.components.PgBottomSheet
import com.pocketgammu.core.designsystem.components.PgButton
import com.pocketgammu.core.designsystem.components.PgButtonStyle
import com.pocketgammu.core.designsystem.components.PgDialog
import com.pocketgammu.core.designsystem.components.PgTextField
import com.pocketgammu.core.designsystem.components.ShapeIcon
import com.pocketgammu.core.designsystem.haptics.LocalPgHaptics
import com.pocketgammu.core.designsystem.theme.PgShapeSet
import com.pocketgammu.core.gammu.model.ConnectionState
import com.pocketgammu.core.gammu.model.PhoneFile
import com.pocketgammu.ui.LocalSnackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private enum class FileKind(val icon: ImageVector, val polygon: () -> RoundedPolygon) {
    FOLDER(Icons.Rounded.Folder, { PgShapeSet.folder }),
    IMAGE(Icons.Rounded.Image, { PgShapeSet.image }),
    SOUND(Icons.Rounded.MusicNote, { PgShapeSet.music }),
    APP(Icons.Rounded.Apps, { PgShapeSet.app }),
    OTHER(Icons.AutoMirrored.Rounded.InsertDriveFile, { PgShapeSet.file }),
}

private fun PhoneFile.kind(): FileKind {
    if (isFolder) return FileKind.FOLDER
    return when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "bmp", "wbmp" -> FileKind.IMAGE
        "mid", "midi", "amr", "mp3", "wav", "aac", "mmf", "nrt", "ott", "rng" -> FileKind.SOUND
        "jar", "jad" -> FileKind.APP
        else -> FileKind.OTHER
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1048576.0)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FileManagerScreen(onGoHome: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: FileManagerViewModel = viewModel { FileManagerViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalPgHaptics.current
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<PhoneFile?>(null) }
    var confirmDelete by remember { mutableStateOf<PhoneFile?>(null) }
    var newFolder by remember { mutableStateOf(false) }

    val notify: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        // Kept in the ViewModel: the screen can be recreated while the save picker is open.
        val file = vm.takePendingDownload() ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        vm.download(file, sink = { bytes ->
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("No output") }
        }, done = notify)
    }
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file"
                    name to (context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't read file"))
                }
            }.onSuccess { (name, bytes) -> vm.upload(name, bytes, notify) }
                .onFailure { notify(it.message ?: "Couldn't read file") }
        }
    }

    BackHandler(enabled = state.connected && !state.atRoot) { haptics.tick(); vm.up() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // Header
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Files", style = MaterialTheme.typography.headlineLarge)
                Text(
                    (state.connection as? ConnectionState.Connected)?.info?.displayName?.let { "Series 40 · $it" } ?: "Series 40 v1 – v2",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.connected) {
                IconButton(onClick = { haptics.click(); newFolder = true }, enabled = !state.atRoot) { Icon(Icons.Rounded.CreateNewFolder, "New folder") }
                IconButton(onClick = { haptics.click(); uploadLauncher.launch(arrayOf("*/*")) }, enabled = !state.atRoot) { Icon(Icons.Rounded.Upload, "Upload file") }
                IconButton(onClick = { haptics.tick(); vm.refresh() }) { Icon(Icons.Rounded.Refresh, "Refresh") }
            }
        }

        if (state.connected) {
            Breadcrumbs(state.path, onUp = { haptics.tick(); vm.up() }, onGoTo = { haptics.tick(); vm.goTo(it) })
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !state.connected -> EmptyState(
                    icon = Icons.Rounded.UsbOff,
                    polygon = PgShapeSet.disconnected,
                    title = "No phone connected",
                    message = "Connect a Series 40 phone (6100, 3510i, 3200, 6020…) from the Home page to browse its files.",
                    action = { PgButton("Go to Home", onClick = onGoHome, icon = Icons.Rounded.Home, style = PgButtonStyle.Tonal) },
                    modifier = Modifier.align(Alignment.Center),
                )
                state.loading -> LoadingIndicator(Modifier.align(Alignment.Center).size(64.dp))
                state.error != null -> EmptyState(
                    icon = Icons.Rounded.FolderOff,
                    polygon = PgShapeSet.error,
                    title = "Couldn't open folder",
                    message = state.error!!,
                    action = { PgButton("Retry", onClick = vm::refresh, icon = Icons.Rounded.Refresh, style = PgButtonStyle.Tonal) },
                    modifier = Modifier.align(Alignment.Center),
                )
                state.entries.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.Folder,
                    polygon = PgShapeSet.folder,
                    title = "Empty folder",
                    message = if (state.atRoot) "No drives reported by the phone." else "Upload a file to put something here.",
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.entries, key = { it.id }) { file ->
                        FileRow(file) {
                            haptics.tick()
                            if (file.isFolder) vm.open(file) else selected = file
                        }
                    }
                }
            }
        }

        AnimatedVisibility(state.transfer != null) {
            state.transfer?.let { t ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text((if (t.upload) "Uploading " else "Downloading ") + t.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    selected?.let { file ->
        PgBottomSheet(onDismiss = { selected = null }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val kind = file.kind()
                ShapeIcon(kind.icon, kind.polygon(), size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(fileMeta(file), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            PgButton("Save to this device", icon = Icons.Rounded.Download, large = true, modifier = Modifier.fillMaxWidth(), onClick = {
                selected = null; vm.pendingDownload = file; saveLauncher.launch(file.name)
            })
            PgButton("Delete from phone", icon = Icons.Rounded.Delete, style = PgButtonStyle.Outlined, enabled = !file.protected, modifier = Modifier.fillMaxWidth(), onClick = {
                selected = null; confirmDelete = file
            })
        }
    }

    confirmDelete?.let { file ->
        PgDialog(
            title = "Delete ${file.name}?",
            onDismiss = { confirmDelete = null },
            confirm = { TextButton(onClick = { haptics.longPress(); vm.delete(file, notify); confirmDelete = null }) { Text("Delete") } },
            dismiss = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        ) { Text("This removes it from the phone. It can't be undone.") }
    }

    if (newFolder) {
        var name by rememberSaveable { mutableStateOf("") }
        PgDialog(
            title = "New folder",
            onDismiss = { newFolder = false },
            confirm = { TextButton(onClick = { vm.createFolder(name.trim(), notify); newFolder = false }, enabled = name.isNotBlank()) { Text("Create") } },
            dismiss = { TextButton(onClick = { newFolder = false }) { Text("Cancel") } },
        ) { PgTextField(value = name, onValueChange = { name = it.take(32) }, label = "Folder name") }
    }
}

private fun fileMeta(file: PhoneFile): String = buildList {
    if (!file.isFolder) add(formatSize(file.size))
    file.modifiedEpochMs?.let { add(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))) }
    if (file.protected) add("protected")
}.joinToString(" · ").ifEmpty { if (file.isFolder) "Folder" else "File" }

@Composable
private fun Breadcrumbs(path: List<Crumb>, onUp: () -> Unit, onGoTo: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onUp, enabled = path.size > 1) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Up one folder") }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            itemsIndexed(path) { i, crumb ->
                val last = i == path.lastIndex
                Surface(
                    onClick = { onGoTo(i) },
                    enabled = !last,
                    shape = MaterialTheme.shapes.extraLarge,
                    color = if (last) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(crumb.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
                if (!last) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FileRow(file: PhoneFile, onClick: () -> Unit) {
    val kind = file.kind()
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(
                kind.icon, kind.polygon(), size = 44.dp,
                containerColor = if (file.isFolder) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (file.isFolder) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(fileMeta(file), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (file.protected) Icon(Icons.Rounded.Lock, "Protected", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (file.isFolder) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
