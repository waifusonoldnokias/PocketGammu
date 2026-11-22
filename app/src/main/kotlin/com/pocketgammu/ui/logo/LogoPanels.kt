package com.pocketgammu.ui.logo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FormatColorFill
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.HorizontalRule
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.PanoramaFishEye
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pocketgammu.core.designsystem.components.ConnectedToggleGroup
import com.pocketgammu.core.designsystem.components.PgButton
import com.pocketgammu.core.designsystem.components.PgButtonStyle
import com.pocketgammu.core.designsystem.components.ToggleOption
import com.pocketgammu.core.designsystem.components.ToolToggle
import com.pocketgammu.core.designsystem.haptics.LocalPgHaptics
import com.pocketgammu.core.designsystem.theme.LocalLcdColors
import com.pocketgammu.ui.logo.model.Layer
import com.pocketgammu.ui.logo.model.LogoDocument

/*
 * Panels are sized so every control fits a 360 dp wide phone without scrolling.
 */

@Composable
private fun ToolRow(tools: List<Pair<Tool, androidx.compose.ui.graphics.vector.ImageVector>>, state: LogoUiState, vm: LogoEditorViewModel, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        tools.forEach { (tool, icon) -> ToolToggle(icon, tool.label, state.tool == tool, onClick = { vm.selectTool(tool) }) }
        Box(Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun BrushOptions(state: LogoUiState, vm: LogoEditorViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        ConnectedToggleGroup(
            options = listOf(ToggleOption(1, "1px"), ToggleOption(2, "2px"), ToggleOption(3, "3px")),
            selected = state.brush,
            onSelect = vm::setBrush,
            compact = true,
            // Weighted so the swatches are measured first and always keep their room (the
            // shrink-to-fit labels would otherwise let the group take the whole row).
            modifier = Modifier.weight(1f),
        )
        ColorSwatches(state.color, vm::setColor)
    }
}

/** Black / white / transparent swatches. Transparent works like an eraser on upper layers. */
@Composable
private fun ColorSwatches(selected: InkColor, onSelect: (InkColor) -> Unit) {
    val haptics = LocalPgHaptics.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        InkColor.entries.forEach { color ->
            val isSelected = color == selected
            val ring = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .border(3.dp, ring, CircleShape)
                    .selectable(selected = isSelected, role = Role.RadioButton) { haptics.tick(); onSelect(color) }
                    .semantics { contentDescription = color.label }
                    .padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                val outline = MaterialTheme.colorScheme.outline
                Canvas(Modifier.fillMaxSize().clip(CircleShape).border(1.dp, outline, CircleShape)) {
                    when (color) {
                        InkColor.INK -> drawRect(Color.Black)
                        InkColor.PAPER -> drawRect(Color.White)
                        InkColor.CLEAR -> {
                            // Checkerboard = transparent, like image editors.
                            val cell = size.width / 4f
                            drawRect(Color.White)
                            for (cy in 0 until 4) for (cx in 0 until 4) {
                                if ((cx + cy) % 2 == 0) drawRect(Color(0xFFBDBDBD), Offset(cx * cell, cy * cell), Size(cell, cell))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DrawPanel(state: LogoUiState, vm: LogoEditorViewModel) {
    val haptics = LocalPgHaptics.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolRow(
            listOf(
                Tool.PENCIL to Icons.Rounded.Edit,
                Tool.ERASER to EraserIcon,
                Tool.FILL to Icons.Rounded.FormatColorFill,
                Tool.MOVE to Icons.Rounded.OpenWith,
            ),
            state, vm,
        ) {
            ToolToggle(Icons.Rounded.GridOn, "Grid", state.showGrid, onClick = { haptics.tick(); vm.toggleGrid() })
        }
        BrushOptions(state, vm)
    }
}

@Composable
fun ShapesPanel(state: LogoUiState, vm: LogoEditorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolRow(
            listOf(
                Tool.LINE to Icons.Rounded.HorizontalRule,
                Tool.RECT to Icons.Rounded.CropSquare,
                Tool.ELLIPSE to Icons.Rounded.PanoramaFishEye,
            ),
            state, vm,
        ) {
            ToolToggle(Icons.Rounded.Stop, "Filled", state.filled, onClick = { vm.setFilled(!state.filled) }, enabled = state.tool != Tool.LINE)
        }
        BrushOptions(state, vm)
    }
}

@Composable
fun TextPanel(state: LogoUiState, onAdd: () -> Unit, onEdit: () -> Unit, onCenter: () -> Unit) {
    val isText = state.doc.active.text != null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PgButton("Add text", onClick = onAdd, icon = Icons.Rounded.Title, modifier = Modifier.weight(1f))
            PgButton("Edit", onClick = onEdit, icon = Icons.Rounded.Edit, enabled = isText, style = PgButtonStyle.Tonal, modifier = Modifier.weight(1f))
            PgButton("Centre", onClick = onCenter, icon = Icons.Rounded.CenterFocusStrong, enabled = isText, style = PgButtonStyle.Outlined, modifier = Modifier.weight(1f))
        }
        PanelHint(if (isText) "Drag on the canvas to position the text." else "Text goes on its own layer and stays editable until you paint on it.")
    }
}

@Composable
fun ImagePanel(state: LogoUiState, onImport: () -> Unit, onAdjust: () -> Unit) {
    val hasImage = state.doc.active.image != null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PgButton("Import image", onClick = onImport, icon = Icons.Rounded.AddPhotoAlternate, modifier = Modifier.weight(1.4f))
            PgButton("Adjust", onClick = onAdjust, icon = Icons.Rounded.Tune, enabled = hasImage, style = PgButtonStyle.Tonal, modifier = Modifier.weight(1f))
        }
        PanelHint("Photos are dithered to ${state.doc.width}×${state.doc.height} on a new layer.")
    }
}

@Composable
fun LayersPanel(state: LogoUiState, vm: LogoEditorViewModel) {
    val haptics = LocalPgHaptics.current
    val doc = state.doc
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val listState = rememberLazyListState()
        // Top layer first, like most editors.
        LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(doc.layers.asReversed(), key = { it.id }) { layer ->
                LayerChip(
                    layer = layer,
                    doc = doc,
                    selected = layer.id == doc.activeId,
                    onClick = { haptics.tick(); vm.selectLayer(layer.id) },
                    onToggleVisible = { haptics.tick(); vm.toggleVisibility(layer.id) },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { haptics.click(); vm.addLayer() }) { Icon(Icons.Rounded.LibraryAdd, "Add layer") }
            IconButton(onClick = { haptics.click(); vm.duplicateLayer() }) { Icon(Icons.Rounded.ContentCopy, "Duplicate layer") }
            IconButton(onClick = { haptics.tick(); vm.moveLayer(up = true) }, enabled = doc.activeIndex < doc.layers.lastIndex) { Icon(Icons.Rounded.ArrowUpward, "Move layer up") }
            IconButton(onClick = { haptics.tick(); vm.moveLayer(up = false) }, enabled = doc.activeIndex > 0) { Icon(Icons.Rounded.ArrowDownward, "Move layer down") }
            IconButton(onClick = { haptics.longPress(); vm.deleteLayer() }) { Icon(Icons.Rounded.Delete, "Delete layer") }
        }
    }
}

@Composable
private fun LayerChip(layer: Layer, doc: LogoDocument, selected: Boolean, onClick: () -> Unit, onToggleVisible: () -> Unit) {
    val lcd = LocalLcdColors.current
    val thumb = remember(layer, doc.width, doc.height, lcd) {
        LogoDocument(doc.format, listOf(layer.copy(visible = true)), layer.id).flatten().toImageBitmap(lcd.ink.toArgb(), lcd.paper.toArgb())
    }
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier
            .clip(MaterialTheme.shapes.large)
            .border(if (selected) 2.dp else 1.dp, border, MaterialTheme.shapes.large)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.width(56.dp).height(56.dp * doc.height / doc.width).clip(MaterialTheme.shapes.extraSmall)) {
            drawImage(thumb, IntOffset.Zero, IntSize(doc.width, doc.height), IntOffset.Zero, IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.None)
        }
        Text(
            layer.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).width(64.dp),
        )
        IconButton(onClick = onToggleVisible, modifier = Modifier.size(36.dp)) {
            Icon(if (layer.visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, if (layer.visible) "Hide layer" else "Show layer", Modifier.size(18.dp))
        }
    }
}

@Composable
private fun PanelHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
}

/** Eraser glyph (Material Icons has none): a tilted block with its rubber end filled, on a baseline. */
private val EraserIcon: ImageVector by lazy {
    ImageVector.Builder("Eraser", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(8.2f, 9.8f); lineTo(14.2f, 15.8f); lineTo(11f, 19f); lineTo(5f, 13f); close()
        }
        path(
            stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
            strokeLineJoin = StrokeJoin.Round, strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(15f, 3f); lineTo(21f, 9f); lineTo(11f, 19f); lineTo(5f, 13f); close()
            moveTo(12f, 21f); lineTo(20f, 21f)
        }
    }.build()
}
