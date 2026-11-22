package com.pocketgammu.ui.logo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pocketgammu.core.designsystem.haptics.LocalPgHaptics
import com.pocketgammu.core.designsystem.theme.LocalLcdColors
import com.pocketgammu.ui.logo.model.Layer
import com.pocketgammu.ui.logo.model.LogoDocument
import kotlin.math.floor
import kotlin.math.min

/** Zoom/pan of the canvas. Zoom 1 = fit to the available area. */
@Stable
class CanvasViewport {
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
    val isTransformed: Boolean get() = zoom != 1f || pan != Offset.Zero
    fun reset() { zoom = 1f; pan = Offset.Zero }
}

/**
 * Pixel canvas: one finger draws with the current tool, two fingers pan and pinch-zoom.
 * Nothing overlays the drawing except a small "fit" button, shown only while zoomed.
 */
@Composable
fun LogoCanvas(
    doc: LogoDocument,
    showGrid: Boolean,
    highlight: Layer?,
    viewport: CanvasViewport,
    onStrokeStart: (Int, Int) -> Unit,
    onStrokeMove: (Int, Int) -> Unit,
    onStrokeEnd: () -> Unit,
    onStrokeCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lcd = LocalLcdColors.current
    val haptics = LocalPgHaptics.current
    val composite = remember(doc) { doc.flatten() }
    val image = remember(composite, lcd) { composite.toImageBitmap(lcd.ink.toArgb(), lcd.paper.toArgb()) }
    val outline = MaterialTheme.colorScheme.primary
    val start by rememberUpdatedState(onStrokeStart)
    val move by rememberUpdatedState(onStrokeMove)
    val end by rememberUpdatedState(onStrokeEnd)
    val cancel by rememberUpdatedState(onStrokeCancel)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val areaW = constraints.maxWidth.toFloat()
        val areaH = constraints.maxHeight.toFloat()
        val margin = with(density) { 16.dp.toPx() }
        val baseCell = min((areaW - 2 * margin) / doc.width, (areaH - 2 * margin) / doc.height).coerceAtLeast(1f)

        // Geometry read by the gesture handler without restarting it.
        val geometry by rememberUpdatedState(Triple(baseCell, Offset(areaW / 2, areaH / 2), IntSize(doc.width, doc.height)))

        fun cell() = geometry.first * viewport.zoom
        fun origin(): Offset {
            val c = cell(); val (w, h) = geometry.third
            return geometry.second - Offset(w * c / 2, h * c / 2) + viewport.pan
        }
        fun toPixel(p: Offset): Pair<Int, Int> {
            val o = origin(); val c = cell()
            return floor((p.x - o.x) / c).toInt() to floor((p.y - o.y) / c).toInt()
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .testTag("logoCanvas")
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var drawing = true
                        haptics.pixel()
                        toPixel(down.position).let { (x, y) -> start(x, y) }
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2) {
                                if (drawing) { cancel(); drawing = false }
                                val centroid = event.calculateCentroid()
                                val zoomChange = event.calculateZoom()
                                val oldCell = cell()
                                val before = (centroid - origin()) / oldCell
                                viewport.zoom = (viewport.zoom * zoomChange).coerceIn(1f, 16f)
                                val newCell = cell()
                                val (w, h) = geometry.third
                                val base = geometry.second - Offset(w * newCell / 2, h * newCell / 2)
                                viewport.pan = centroid - before * newCell - base + event.calculatePan()
                                event.changes.forEach { it.consume() }
                            } else if (drawing) {
                                val ch = event.changes.firstOrNull { it.pressed } ?: event.changes.first()
                                if (ch.positionChanged()) toPixel(ch.position).let { (x, y) -> move(x, y) }
                                ch.consume()
                            }
                        } while (event.changes.any { it.pressed })
                        if (drawing) end()
                    }
                },
        ) {
            val c = cell()
            val o = origin()
            val size = Size(doc.width * c, doc.height * c)
            // Bezel
            drawRoundRect(lcd.ink.copy(alpha = 0.12f), o - Offset(6f, 6f), Size(size.width + 12f, size.height + 12f), CornerRadius(14f))
            drawImage(
                image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(doc.width, doc.height),
                dstOffset = IntOffset(o.x.toInt(), o.y.toInt()),
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = FilterQuality.None,
            )
            if (showGrid && c >= 7.dp.toPx()) {
                for (x in 1 until doc.width) {
                    val px = o.x + x * c
                    drawLine(lcd.grid, Offset(px, o.y), Offset(px, o.y + size.height), strokeWidth = 1f)
                }
                for (y in 1 until doc.height) {
                    val py = o.y + y * c
                    drawLine(lcd.grid, Offset(o.x, py), Offset(o.x + size.width, py), strokeWidth = 1f)
                }
            }
            // Bounds of a positioned (text) layer, so it's clear what the Move tool will drag.
            if (highlight != null && (highlight.text != null || highlight.offsetX != 0 || highlight.offsetY != 0)) {
                drawRect(
                    outline,
                    topLeft = o + Offset(highlight.offsetX * c, highlight.offsetY * c),
                    size = Size(highlight.width * c, highlight.height * c),
                    style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                )
            }
        }

        AnimatedVisibility(
            visible = viewport.isTransformed,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
        ) {
            SmallFloatingActionButton(
                onClick = { haptics.tick(); viewport.reset() },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ) { Icon(Icons.Rounded.ZoomOutMap, contentDescription = "Fit to screen") }
        }
    }
}
