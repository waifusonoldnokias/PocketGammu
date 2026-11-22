package com.pocketgammu.ui.logo

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketgammu.LocalAppContainer
import com.pocketgammu.core.designsystem.components.ConnectedToggleGroup
import com.pocketgammu.core.designsystem.components.PgDialog
import com.pocketgammu.core.designsystem.components.PgDropdown
import com.pocketgammu.core.designsystem.components.PgTextField
import com.pocketgammu.core.designsystem.components.SwitchRow
import com.pocketgammu.core.designsystem.components.ToggleOption
import com.pocketgammu.core.designsystem.haptics.LocalPgHaptics
import com.pocketgammu.core.designsystem.theme.LocalLcdColors
import com.pocketgammu.ui.LocalSnackbar
import com.pocketgammu.ui.logo.model.FontRef
import com.pocketgammu.ui.logo.model.Px
import com.pocketgammu.ui.logo.model.TextSpec
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun TextLayerDialog(initial: TextSpec?, onDismiss: () -> Unit, onConfirm: (TextSpec) -> Unit) {
    val container = LocalAppContainer.current
    val fontStore = container.fonts
    val haptics = LocalPgHaptics.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val imported by fontStore.fonts.collectAsStateWithLifecycle()

    var text by rememberSaveable { mutableStateOf(initial?.text ?: "") }
    var font by remember { mutableStateOf(initial?.font ?: FontRef.DEFAULT) }
    var fontSize by remember { mutableFloatStateOf((initial?.sizePx ?: 11).toFloat()) }
    var width by remember { mutableFloatStateOf((initial?.widthPercent ?: 100).toFloat()) }
    var spacing by remember { mutableFloatStateOf((initial?.spacingPx ?: 0).toFloat()) }
    var outline by remember { mutableIntStateOf(initial?.outlinePx ?: 0) }
    var bold by rememberSaveable { mutableStateOf(initial?.bold ?: true) }
    var inverted by rememberSaveable { mutableStateOf(initial?.inverted ?: false) }

    val spec = TextSpec(
        text = text, font = font, sizePx = fontSize.roundToInt(), bold = bold,
        widthPercent = width.roundToInt(), spacingPx = spacing.roundToInt(), outlinePx = outline, inverted = inverted,
    )
    val raster = remember(spec, imported) {
        if (text.isBlank()) null else TextRasterizer.render(spec, fontStore.typeface(spec.font, spec.bold))
    }
    val lcd = LocalLcdColors.current

    val pickFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            fontStore.import(uri)
                .onSuccess { name -> haptics.confirm(); font = FontRef.Custom(name) }
                .onFailure { haptics.reject(); snackbar.showSnackbar(it.message ?: "Couldn't import that font") }
        }
    }

    PgDialog(
        title = if (initial == null) "Add text" else "Edit text",
        onDismiss = onDismiss,
        confirm = { TextButton(onClick = { onConfirm(spec) }, enabled = text.isNotBlank()) { Text(if (initial == null) "Add" else "Update") } },
        dismiss = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        PgTextField(value = text, onValueChange = { text = it.take(40) }, label = "Text", singleLine = false)

        // Live pixel preview, exactly as it lands on the canvas. Grey = transparent.
        Box(
            Modifier.fillMaxWidth().height(80.dp).clip(MaterialTheme.shapes.medium).background(lcd.paper),
            contentAlignment = Alignment.Center,
        ) {
            if (raster != null) {
                Canvas(Modifier.fillMaxWidth().height(80.dp).padding(8.dp)) {
                    val cell = min(size.width / raster.width, size.height / raster.height)
                    val ox = (size.width - raster.width * cell) / 2
                    val oy = (size.height - raster.height * cell) / 2
                    drawRect(lcd.grid, Offset(ox, oy), Size(raster.width * cell, raster.height * cell))
                    for (y in 0 until raster.height) for (x in 0 until raster.width) {
                        val color = when (raster.pixels[y * raster.width + x]) {
                            Px.INK -> lcd.ink
                            Px.PAPER -> lcd.paper
                            else -> null
                        } ?: continue
                        drawRect(color, Offset(ox + x * cell, oy + y * cell), Size(cell, cell))
                    }
                }
            } else {
                Text("Preview", color = lcd.ink.copy(alpha = 0.5f))
            }
        }
        Text(
            raster?.let { "${it.width}×${it.height} px" } ?: " ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Font: built-ins + imported files, with import / remove.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PgDropdown(
                label = "Font",
                options = FontRef.builtins + imported.map { FontRef.Custom(it) },
                selected = font,
                optionLabel = { it.label },
                supporting = { if (it is FontRef.Custom) "Imported" else "Built-in" },
                onSelect = { font = it },
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(onClick = {
                haptics.click()
                pickFont.launch(arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf",
                    "application/font-sfnt", "application/vnd.ms-opentype", "application/octet-stream", "*/*"))
            }) { Icon(Icons.Rounded.FontDownload, "Import .ttf or .otf font") }
            val custom = font as? FontRef.Custom
            if (custom != null) {
                IconButton(onClick = { haptics.longPress(); fontStore.delete(custom.fileName); font = FontRef.DEFAULT }) {
                    Icon(Icons.Rounded.Delete, "Remove imported font")
                }
            }
        }

        LabeledSlider("Size", "${fontSize.roundToInt()} px", fontSize, 6f..40f, steps = 33) { fontSize = it }
        LabeledSlider("Width", "${width.roundToInt()}%", width, 50f..200f, steps = 29) { width = it }
        LabeledSlider("Spacing", "${spacing.roundToInt()} px", spacing, -2f..8f, steps = 9) { spacing = it }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Outline", style = MaterialTheme.typography.labelLarge)
            ConnectedToggleGroup(
                options = listOf(ToggleOption(0, "None"), ToggleOption(1, "1 px"), ToggleOption(2, "2 px"), ToggleOption(3, "3 px")),
                selected = outline,
                onSelect = { outline = it },
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SwitchRow(title = "Bold", checked = bold, onCheckedChange = { bold = it })
        SwitchRow(
            title = "Inverted",
            subtitle = if (outline > 0) "Paper letters with an ink outline" else "Paper letters (for dark backgrounds)",
            checked = inverted,
            onCheckedChange = { inverted = it },
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(76.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = current, onValueChange = onChange, valueRange = range, steps = steps, modifier = Modifier.weight(1f))
    }
}
