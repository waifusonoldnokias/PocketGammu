package com.pocketgammu.ui.logo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pocketgammu.core.designsystem.components.ConnectedToggleGroup
import com.pocketgammu.core.designsystem.components.PgButton
import com.pocketgammu.core.designsystem.components.PgDropdown
import com.pocketgammu.core.designsystem.components.SwitchRow
import com.pocketgammu.core.designsystem.components.ToggleOption
import com.pocketgammu.core.designsystem.haptics.LocalPgHaptics
import com.pocketgammu.core.designsystem.theme.LocalLcdColors
import com.pocketgammu.core.nokia.bitmap.MonoBitmap
import com.pocketgammu.core.nokia.image.ConversionSettings
import com.pocketgammu.core.nokia.image.DitherMode
import com.pocketgammu.core.nokia.image.FitMode
import com.pocketgammu.core.nokia.image.ImagePlacement
import com.pocketgammu.core.nokia.image.ImageTransform
import com.pocketgammu.ui.logo.model.ImageSource
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class Handle { LEFT, RIGHT }

/**
 * Full-screen image → pixel-art editor.
 *
 * Touch controls on the preview:
 *  - one finger: move
 *  - pinch: scale (around the fingers)
 *  - twist: rotate (snaps to right angles)
 *  - drag a side handle: change the width, keeping the opposite edge in place
 */
@Composable
fun ImageImportScreen(
    source: ImageSource,
    isUpdate: Boolean,
    canvasWidth: Int,
    canvasHeight: Int,
    preview: (ImageSource) -> MonoBitmap,
    onDismiss: () -> Unit,
    onConfirm: (ImageSource) -> Unit,
) {
    var settings by remember { mutableStateOf(source.settings) }
    var whiteIsClear by remember { mutableStateOf(source.whiteIsClear) }
    val current = source.with(settings, whiteIsClear)
    // ~6k canvas pixels × 9 samples: fast enough to recompute on every gesture frame.
    val bitmap = remember(settings) { preview(current) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                // Top bar
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Cancel") }
                    Text(
                        if (isUpdate) "Adjust image" else "Import image",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    PgButton(if (isUpdate) "Update" else "Add layer", onClick = { onConfirm(current) }, icon = Icons.Rounded.Check)
                }

                TransformPreview(
                    source = source,
                    bitmap = bitmap,
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                    settings = settings,
                    onTransform = { settings = settings.copy(transform = it) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                TransformBar(
                    transform = settings.transform,
                    onChange = { settings = settings.copy(transform = it) },
                )

                // Conversion settings scroll independently; the preview stays put.
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ConnectedToggleGroup(
                        options = FitMode.entries.map { ToggleOption(it, it.label) },
                        selected = settings.fit,
                        onSelect = { settings = settings.copy(fit = it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PgDropdown(
                        label = "Dithering",
                        options = DitherMode.entries,
                        selected = settings.mode,
                        optionLabel = { it.label },
                        onSelect = { settings = settings.copy(mode = it) },
                    )
                    LabeledSlider("Brightness", settings.brightness, -0.5f..0.5f) { settings = settings.copy(brightness = it) }
                    LabeledSlider("Contrast", settings.contrast, 0.5f..2.5f) { settings = settings.copy(contrast = it) }
                    LabeledSlider(if (settings.mode == DitherMode.THRESHOLD) "Threshold" else "Bias", settings.threshold, 0.2f..0.8f) {
                        settings = settings.copy(threshold = it)
                    }
                    SwitchRow(title = "Invert", checked = settings.invert, onCheckedChange = { settings = settings.copy(invert = it) })
                    SwitchRow(
                        title = "White is transparent",
                        subtitle = "Lets layers underneath show through",
                        checked = whiteIsClear,
                        onCheckedChange = { whiteIsClear = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun TransformPreview(
    source: ImageSource,
    bitmap: MonoBitmap,
    canvasWidth: Int,
    canvasHeight: Int,
    settings: ConversionSettings,
    onTransform: (ImageTransform) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lcd = LocalLcdColors.current
    val haptics = LocalPgHaptics.current
    val density = LocalDensity.current
    val handleColor = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.tertiary
    val image = remember(bitmap, lcd) { bitmap.toImageBitmap(lcd.ink.toArgb(), lcd.paper.toArgb()) }
    val latest by rememberUpdatedState(settings)
    val emit by rememberUpdatedState(onTransform)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .heightIn(max = 340.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        // Largest box with the canvas aspect ratio, leaving room around it for handles.
        val pad = with(density) { 28.dp.toPx() }
        val availW = constraints.maxWidth - 2 * pad
        val availH = (if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else availW) - 2 * pad
        val cell = minOf(availW / canvasWidth, availH / canvasHeight).coerceAtLeast(1f)
        val boxW = canvasWidth * cell
        val boxH = canvasHeight * cell
        val totalH = boxH + 2 * pad
        val origin = Offset((constraints.maxWidth - boxW) / 2f, pad)
        val handleRadius = with(density) { 11.dp.toPx() }
        val grabRadius = with(density) { 32.dp.toPx() }

        fun placement(s: ConversionSettings) =
            ImagePlacement.of(source.width, source.height, canvasWidth, canvasHeight, s.fit, s.transform)

        fun toScreen(x: Float, y: Float) = Offset(origin.x + x * cell, origin.y + y * cell)

        fun handleAt(s: ConversionSettings, h: Handle): Offset {
            val p = placement(s)
            val (x, y) = p.toCanvas(if (h == Handle.LEFT) -p.halfWidth else p.halfWidth, 0f)
            return toScreen(x, y)
        }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(with(density) { totalH.toDp() })
                .semantics { contentDescription = "Image preview. Drag to move, pinch to resize, twist to rotate, drag side handles to change width." }
                .pointerInput(source, canvasWidth, canvasHeight) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val s0 = latest
                        val handle = Handle.entries.firstOrNull { h ->
                            (handleAt(s0, h) - down.position).getDistance() <= grabRadius
                        }
                        var t = s0.transform
                        var snapped = abs(t.rotation % 90f) < 0.01f
                        haptics.tick()
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            val p = ImagePlacement.of(source.width, source.height, canvasWidth, canvasHeight, s0.fit, t)
                            val rad = Math.toRadians(t.rotation.toDouble())
                            val ax = cos(rad).toFloat(); val ay = sin(rad).toFloat()   // image x-axis on canvas

                            if (handle != null && pressed.size == 1) {
                                // Width handle: move one edge along the image's own x-axis.
                                val d = pressed[0].positionChange() / cell
                                val along = d.x * ax + d.y * ay
                                val signed = if (handle == Handle.RIGHT) along else -along
                                val newHalf = (p.halfWidth + signed / 2f).coerceAtLeast(0.5f)
                                val ratio = newHalf / p.halfWidth
                                val shift = (newHalf - p.halfWidth) * (if (handle == Handle.RIGHT) 1f else -1f)
                                t = t.copy(
                                    widthScale = (t.widthScale * ratio).coerceIn(ImageTransform.MIN_WIDTH, ImageTransform.MAX_WIDTH),
                                    offsetX = t.offsetX + shift * ax,
                                    offsetY = t.offsetY + shift * ay,
                                )
                            } else if (pressed.size >= 2) {
                                // Pinch + twist + pan, anchored on the fingers' centroid.
                                val zoom = event.calculateZoom()
                                val rot = event.calculateRotation()
                                val pan = event.calculatePan() / cell
                                val c = (event.calculateCentroid() - origin) / cell
                                val newScale = (t.scale * zoom).coerceIn(ImageTransform.MIN_SCALE, ImageTransform.MAX_SCALE)
                                val k = newScale / t.scale
                                val r = Math.toRadians(rot.toDouble())
                                val vx = p.centerX - c.x; val vy = p.centerY - c.y
                                val nx = (vx * cos(r) - vy * sin(r)).toFloat() * k
                                val ny = (vx * sin(r) + vy * cos(r)).toFloat() * k
                                t = t.copy(
                                    scale = newScale,
                                    rotation = ImageTransform.normalizeDegrees(t.rotation + rot),
                                    offsetX = c.x + nx + pan.x - canvasWidth / 2f,
                                    offsetY = c.y + ny + pan.y - canvasHeight / 2f,
                                )
                            } else {
                                val d = pressed[0].positionChange() / cell
                                t = t.copy(offsetX = t.offsetX + d.x, offsetY = t.offsetY + d.y)
                            }
                            // Snap to right angles while twisting, with a tick when it locks.
                            val shown = t.copy(rotation = ImageTransform.snapRotation(t.rotation))
                            val nowSnapped = shown.rotation != t.rotation || abs(t.rotation % 90f) < 0.01f
                            if (nowSnapped && !snapped && pressed.size >= 2) haptics.tick()
                            snapped = nowSnapped
                            emit(shown)
                            event.changes.forEach { it.consume() }
                        } while (true)
                        // Commit the snapped angle so it doesn't drift on the next gesture.
                        emit(t.copy(rotation = ImageTransform.snapRotation(t.rotation)))
                    }
                },
        ) {
            // Pixel preview exactly as it will land on the canvas.
            drawImage(
                image, IntOffset.Zero, IntSize(bitmap.width, bitmap.height),
                IntOffset(origin.x.roundToInt(), origin.y.roundToInt()), IntSize(boxW.roundToInt(), boxH.roundToInt()),
                filterQuality = FilterQuality.None,
            )
            // Image bounds (may extend beyond the canvas).
            val p = placement(latest)
            val path = Path().apply {
                p.corners().forEachIndexed { i, (x, y) ->
                    val o = toScreen(x, y)
                    if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
                }
                close()
            }
            drawPath(path, outline, style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
            for (h in Handle.entries) {
                val o = handleAt(latest, h)
                drawCircle(Color.White, handleRadius + 2.dp.toPx(), o)
                drawCircle(handleColor, handleRadius, o)
            }
        }
    }
}

@Composable
private fun TransformBar(transform: ImageTransform, onChange: (ImageTransform) -> Unit) {
    val haptics = LocalPgHaptics.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ValueChip("Scale", "${(transform.scale * 100).roundToInt()}%", Modifier.weight(1f))
            ValueChip("Width", "${(transform.widthScale * 100).roundToInt()}%", Modifier.weight(1f))
            ValueChip("Angle", "${transform.rotation.roundToInt()}°", Modifier.weight(1f))
            FilledTonalIconButton(onClick = {
                haptics.tick()
                onChange(transform.copy(rotation = ImageTransform.normalizeDegrees(ImageTransform.snapRotation(transform.rotation, 45f) + 90f)))
            }) { Icon(Icons.Rounded.RotateRight, "Rotate 90 degrees") }
            FilledTonalIconButton(onClick = { haptics.tick(); onChange(transform.copy(offsetX = 0f, offsetY = 0f)) }) {
                Icon(Icons.Rounded.CenterFocusStrong, "Centre image")
            }
            FilledTonalIconButton(onClick = { haptics.click(); onChange(ImageTransform.IDENTITY) }) {
                Icon(Icons.Rounded.RestartAlt, "Reset position")
            }
        }
        Text(
            "Drag to move · pinch to resize · twist to rotate · drag the side handles to change the width",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ValueChip(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f))
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(92.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f).padding(end = 4.dp))
    }
}
