package com.pocketgammu.ui.logo

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InvertColors
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketgammu.LocalAppContainer
import com.pocketgammu.core.designsystem.blur.RegisterOverlay
import com.pocketgammu.core.designsystem.haptics.LocalPgHaptics
import com.pocketgammu.core.nokia.logo.LogoFormat
import com.pocketgammu.core.nokia.logo.LogoKind
import com.pocketgammu.core.nokia.logo.SmartLogoEncoder
import com.pocketgammu.ui.LocalSnackbar
import com.pocketgammu.ui.common.SendSheet
import com.pocketgammu.ui.logo.model.ImageSource
import com.pocketgammu.ui.logo.model.TextSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which pop-up is open (only one at a time). */
private sealed interface LogoOverlay {
    data object Send : LogoOverlay
    data class Text(val spec: TextSpec?, val layerId: Long?) : LogoOverlay
    data class Image(val source: ImageSource, val layerId: Long?) : LogoOverlay
}

@Composable
fun LogoEditorScreen(onGoHome: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: LogoEditorViewModel = viewModel { LogoEditorViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val viewport = remember { CanvasViewport() }
    val haptics = LocalPgHaptics.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var overlay by remember { mutableStateOf<LogoOverlay?>(null) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val argb = ImageLoader.load(context, uri, maxEdge = 1024)
                    ImageSource.create(argb.pixels, argb.width, argb.height, vm.defaultImageSettings(), whiteIsClear = true)
                }
            }
                .onSuccess { source -> overlay = LogoOverlay.Image(source, layerId = null) }
                .onFailure { snackbar.showSnackbar("Couldn't open that image") }
        }
    }

    val saveBmp = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/bmp")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = vm.exportBmp()
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri)) { "No output stream" }.use { it.write(bytes) }
                }
            }.onSuccess { haptics.confirm(); snackbar.showSnackbar("Saved as BMP") }
                .onFailure { haptics.reject(); snackbar.showSnackbar("Couldn't save the image") }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        EditorTopBar(
            format = state.doc.format,
            canUndo = state.canUndo,
            canRedo = state.canRedo,
            onFormat = { haptics.tick(); viewport.reset(); vm.setFormat(it) },
            onClear = { haptics.longPress(); vm.clearCanvas() },
            onInvert = { haptics.click(); vm.invertActive() },
            onUndo = { haptics.tick(); vm.undo() },
            onRedo = { haptics.tick(); vm.redo() },
            onSave = { haptics.click(); saveBmp.launch(vm.bmpFileName) },
            onSend = { haptics.click(); overlay = LogoOverlay.Send },
        )

        // The canvas takes all remaining space and is never covered by controls.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            LogoCanvas(
                doc = state.doc,
                showGrid = state.showGrid,
                highlight = if (state.tool == Tool.MOVE) state.doc.active else null,
                viewport = viewport,
                onStrokeStart = vm::strokeStart,
                onStrokeMove = vm::strokeMove,
                onStrokeEnd = vm::strokeEnd,
                onStrokeCancel = vm::strokeCancel,
            )
        }

        // Contextual tool panel for the selected category.
        Surface(color = MaterialTheme.colorScheme.surface) {
            AnimatedContent(
                targetState = state.panel,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "panel",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) { panel ->
                when (panel) {
                    EditorPanel.DRAW -> DrawPanel(state, vm)
                    EditorPanel.SHAPES -> ShapesPanel(state, vm)
                    EditorPanel.TEXT -> TextPanel(
                        state = state,
                        onAdd = { overlay = LogoOverlay.Text(null, null) },
                        onEdit = { overlay = LogoOverlay.Text(state.doc.active.text, state.doc.activeId) },
                        onCenter = { haptics.tick(); vm.centerActive() },
                    )
                    EditorPanel.IMAGE -> ImagePanel(
                        state = state,
                        onImport = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onAdjust = { state.doc.active.image?.let { overlay = LogoOverlay.Image(it, state.doc.activeId) } },
                    )
                    EditorPanel.LAYERS -> LayersPanel(state, vm)
                }
            }
        }

        CategoryBar(selected = state.panel, onSelect = { haptics.tick(); vm.selectPanel(it) })
    }

    when (val o = overlay) {
        LogoOverlay.Send -> {
            val format = state.doc.format
            val picture = format.kind == LogoKind.PICTURE
            SendSheet(
                title = if (picture) "Send picture message" else "Send logo",
                itemDescription = "${format.label} · ${format.sizeLabel}",
                needsNetworkCode = format.kind == LogoKind.OPERATOR,
                smsUnsupportedReason = vm.smsUnsupportedReason,
                buildSms = vm::smartMessage,
                sendCable = { vm.sendToPhone(it) },
                onDismiss = { overlay = null },
                onGoHome = onGoHome,
                messageText = picture,
                messageTextMax = SmartLogoEncoder.PICTURE_TEXT_MAX,
                pictureSlot = picture,
            )
        }
        is LogoOverlay.Text -> TextLayerDialog(
            initial = o.spec,
            onDismiss = { overlay = null },
            onConfirm = { spec -> haptics.confirm(); vm.addOrUpdateText(spec, o.layerId); overlay = null },
        )
        is LogoOverlay.Image -> ImageImportScreen(
            source = o.source,
            isUpdate = o.layerId != null,
            canvasWidth = state.doc.width,
            canvasHeight = state.doc.height,
            preview = vm::previewImage,
            onDismiss = { overlay = null },
            onConfirm = { src -> haptics.confirm(); vm.addOrUpdateImage(src, o.layerId); overlay = null },
        )
        null -> Unit
    }
}

@Composable
private fun EditorTopBar(
    format: LogoFormat,
    canUndo: Boolean,
    canRedo: Boolean,
    onFormat: (LogoFormat) -> Unit,
    onClear: () -> Unit,
    onInvert: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onSend: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Surface(
                onClick = { menu = true },
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(format.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text(format.sizeLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
                    }
                    Icon(Icons.Rounded.ArrowDropDown, null)
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = MaterialTheme.shapes.large) {
                RegisterOverlay()
                LogoFormat.entries.forEach { f ->
                    DropdownMenuItem(
                        text = { Column { Text(f.label); Text(f.sizeLabel, style = MaterialTheme.typography.bodySmall) } },
                        onClick = { menu = false; onFormat(f) },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Invert layer") }, leadingIcon = { Icon(Icons.Rounded.InvertColors, null) }, onClick = { menu = false; onInvert() })
                DropdownMenuItem(text = { Text("Clear canvas") }, leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) }, onClick = { menu = false; onClear() })
            }
        }
        IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo") }
        IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.AutoMirrored.Rounded.Redo, "Redo") }
        IconButton(onClick = onSave) { Icon(Icons.Rounded.Save, "Save as BMP") }
        FilledIconButton(onClick = onSend) { Icon(Icons.AutoMirrored.Rounded.Send, "Send logo") }
    }
}

@Composable
private fun CategoryBar(selected: EditorPanel, onSelect: (EditorPanel) -> Unit) {
    val items = listOf(
        EditorPanel.DRAW to Icons.Rounded.Brush,
        EditorPanel.SHAPES to Icons.Rounded.Category,
        EditorPanel.TEXT to Icons.Rounded.TextFields,
        EditorPanel.IMAGE to Icons.Rounded.Image,
        EditorPanel.LAYERS to Icons.Rounded.Layers,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        items.forEach { (panel, icon) -> CategoryItem(panel.label, icon, panel == selected) { onSelect(panel) } }
    }
}

@Composable
private fun CategoryItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    // Material navigation-bar pairing: the icon sits on the indicator pill, the label on the bar.
    val scheme = MaterialTheme.colorScheme
    val pill by animateColorAsState(if (selected) scheme.secondaryContainer else scheme.surfaceContainerLow, label = "pill")
    val iconTint = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant
    val labelColor = if (selected) scheme.onSurface else scheme.onSurfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .semantics { role = Role.Tab; this.selected = selected }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Box(
            Modifier.clip(MaterialTheme.shapes.extraLarge).background(pill).padding(horizontal = 14.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = labelColor,
        )
    }
}
