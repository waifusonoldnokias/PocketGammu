package com.pocketgammu.ui.logo.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pixel operations on a canvas-sized layer buffer. Coordinates are document coordinates;
 * the layer must be [Layer.baked] (offset 0, canvas size) before painting.
 */
class Painter(private val w: Int, private val h: Int, val buffer: ByteArray) {

    fun plot(x: Int, y: Int, value: Byte, size: Int = 1) {
        val start = -(size - 1) / 2
        for (dy in 0 until size) for (dx in 0 until size) set(x + start + dx, y + start + dy, value)
    }

    private fun set(x: Int, y: Int, value: Byte) {
        if (x in 0 until w && y in 0 until h) buffer[y * w + x] = value
    }

    /** Bresenham line with a square brush. */
    fun line(x0: Int, y0: Int, x1: Int, y1: Int, value: Byte, size: Int = 1) {
        var x = x0; var y = y0
        val dx = abs(x1 - x0); val sx = if (x0 < x1) 1 else -1
        val dy = -abs(y1 - y0); val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            plot(x, y, value, size)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    fun rect(x0: Int, y0: Int, x1: Int, y1: Int, value: Byte, filled: Boolean, size: Int = 1) {
        val l = min(x0, x1); val r = max(x0, x1); val t = min(y0, y1); val b = max(y0, y1)
        for (y in t..b) for (x in l..r) {
            val edge = x - l < size || r - x < size || y - t < size || b - y < size
            if (filled || edge) set(x, y, value)
        }
    }

    fun ellipse(x0: Int, y0: Int, x1: Int, y1: Int, value: Byte, filled: Boolean, size: Int = 1) {
        val l = min(x0, x1); val r = max(x0, x1); val t = min(y0, y1); val b = max(y0, y1)
        val cx = (l + r) / 2.0; val cy = (t + b) / 2.0
        val rx = (r - l + 1) / 2.0; val ry = (b - t + 1) / 2.0
        fun inside(x: Int, y: Int): Boolean {
            if (x < l || x > r || y < t || y > b) return false
            val nx = (x - cx) / rx; val ny = (y - cy) / ry
            return nx * nx + ny * ny <= 1.0
        }
        for (y in t..b) for (x in l..r) {
            if (!inside(x, y)) continue
            // Outline = pixels that have an outside neighbour within `size`.
            val edge = !filled && (1..size).any { d ->
                !inside(x - d, y) || !inside(x + d, y) || !inside(x, y - d) || !inside(x, y + d)
            }
            if (filled || edge) set(x, y, value)
        }
    }

    /**
     * Flood fill: the region is taken from the [composite] (what the user sees), the result
     * is written into this layer. 4-connected.
     */
    fun fill(x: Int, y: Int, composite: BooleanArray, value: Byte) {
        if (x !in 0 until w || y !in 0 until h) return
        val target = composite[y * w + x]
        val seen = BooleanArray(w * h)
        val stack = ArrayDeque<Int>()
        stack.addLast(y * w + x)
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            if (seen[i] || composite[i] != target) continue
            seen[i] = true
            buffer[i] = value
            val px = i % w; val py = i / w
            if (px > 0) stack.addLast(i - 1)
            if (px < w - 1) stack.addLast(i + 1)
            if (py > 0) stack.addLast(i - w)
            if (py < h - 1) stack.addLast(i + w)
        }
    }
}
