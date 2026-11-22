package com.pocketgammu.ui.logo.model

/** Layer-ready text pixels (Px values), sized to the glyphs plus outline. */
class TextRaster(val width: Int, val height: Int, val pixels: ByteArray)

/**
 * Turns a glyph mask into layer pixels with an optional outline. Pure Kotlin so the
 * pixel rules are unit-tested; the Android side only rasterises the glyph mask.
 *
 * - letters: INK (or PAPER when [inverted])
 * - outline: the opposite colour, [outlinePx] thick (1 px uses all 8 neighbours,
 *   thicker outlines are rounded)
 * - everything else: CLEAR, so layers underneath show through
 */
object TextOutline {

    fun compose(mask: BooleanArray, w: Int, h: Int, outlinePx: Int, inverted: Boolean): TextRaster {
        val (tw, th, trimmed) = trim(mask, w, h)
        val r = outlinePx.coerceIn(0, 8)
        val ow = tw + 2 * r
        val oh = th + 2 * r
        val fill = if (inverted) Px.PAPER else Px.INK
        val edge = if (inverted) Px.INK else Px.PAPER
        val out = ByteArray(ow * oh)
        for (y in 0 until th) for (x in 0 until tw) {
            if (trimmed[y * tw + x]) out[(y + r) * ow + (x + r)] = fill
        }
        if (r > 0) {
            val offsets = buildList {
                for (dy in -r..r) for (dx in -r..r) {
                    if (dx == 0 && dy == 0) continue
                    if (r == 1 || dx * dx + dy * dy <= r * r + r) add(dx to dy)
                }
            }
            for (y in 0 until oh) for (x in 0 until ow) {
                if (out[y * ow + x] != Px.CLEAR) continue
                val near = offsets.any { (dx, dy) ->
                    val sx = x + dx; val sy = y + dy
                    sx in 0 until ow && sy in 0 until oh && out[sy * ow + sx] == fill
                }
                if (near) out[y * ow + x] = edge
            }
        }
        return TextRaster(ow, oh, out)
    }

    /** Crops empty rows/columns so the layer hugs the glyphs. */
    private fun trim(mask: BooleanArray, w: Int, h: Int): Triple<Int, Int, BooleanArray> {
        var top = h; var bottom = -1; var left = w; var right = -1
        for (y in 0 until h) for (x in 0 until w) if (mask[y * w + x]) {
            top = minOf(top, y); bottom = maxOf(bottom, y); left = minOf(left, x); right = maxOf(right, x)
        }
        if (bottom < 0) return Triple(1, 1, BooleanArray(1))
        val tw = right - left + 1; val th = bottom - top + 1
        return Triple(tw, th, BooleanArray(tw * th) { i -> mask[(top + i / tw) * w + left + i % tw] })
    }
}
