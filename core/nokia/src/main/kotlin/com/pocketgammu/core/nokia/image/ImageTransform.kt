package com.pocketgammu.core.nokia.image

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * User placement of an imported image on the logo canvas, applied on top of the
 * [FitMode] placement. Offsets are in canvas pixels; rotation in degrees (clockwise).
 */
data class ImageTransform(
    val scale: Float = 1f,
    /** Horizontal stretch relative to the height (1 = original aspect). */
    val widthScale: Float = 1f,
    val rotation: Float = 0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    val isIdentity: Boolean get() = this == IDENTITY

    fun clamped(): ImageTransform = copy(
        scale = scale.coerceIn(MIN_SCALE, MAX_SCALE),
        widthScale = widthScale.coerceIn(MIN_WIDTH, MAX_WIDTH),
        rotation = normalizeDegrees(rotation),
    )

    companion object {
        val IDENTITY = ImageTransform()
        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 10f
        const val MIN_WIDTH = 0.2f
        const val MAX_WIDTH = 5f

        fun normalizeDegrees(d: Float): Float {
            var r = d % 360f
            if (r > 180f) r -= 360f
            if (r <= -180f) r += 360f
            return r
        }

        /** Snaps to the nearest right angle when within [tolerance] degrees. */
        fun snapRotation(d: Float, tolerance: Float = 4f): Float {
            val n = (d / 90f).roundToInt() * 90f
            return if (abs(d - n) <= tolerance) normalizeDegrees(n) else normalizeDegrees(d)
        }
    }
}

/**
 * Where the image lands on the canvas: centre, half-extents (in canvas pixels, before
 * rotation) and rotation. Shared by the converter and the touch UI so handles line up
 * exactly with what gets rendered.
 */
data class ImagePlacement(
    val centerX: Float,
    val centerY: Float,
    val halfWidth: Float,
    val halfHeight: Float,
    val rotationDegrees: Float,
) {
    val radians: Double get() = rotationDegrees * PI / 180.0

    /** Canvas point of the image's local point (lx, ly) measured from its centre. */
    fun toCanvas(lx: Float, ly: Float): Pair<Float, Float> {
        val c = cos(radians).toFloat(); val s = sin(radians).toFloat()
        return (centerX + lx * c - ly * s) to (centerY + lx * s + ly * c)
    }

    /** The four corners, clockwise from top-left. */
    fun corners(): List<Pair<Float, Float>> = listOf(
        toCanvas(-halfWidth, -halfHeight), toCanvas(halfWidth, -halfHeight),
        toCanvas(halfWidth, halfHeight), toCanvas(-halfWidth, halfHeight),
    )

    companion object {
        fun of(srcW: Int, srcH: Int, dstW: Int, dstH: Int, fit: FitMode, t: ImageTransform): ImagePlacement {
            val (kx, ky) = when (fit) {
                FitMode.STRETCH -> dstW.toFloat() / srcW to dstH.toFloat() / srcH
                FitMode.FIT -> min(dstW.toFloat() / srcW, dstH.toFloat() / srcH).let { it to it }
                FitMode.FILL -> max(dstW.toFloat() / srcW, dstH.toFloat() / srcH).let { it to it }
            }
            return ImagePlacement(
                centerX = dstW / 2f + t.offsetX,
                centerY = dstH / 2f + t.offsetY,
                halfWidth = srcW * kx * t.scale * t.widthScale / 2f,
                halfHeight = srcH * ky * t.scale / 2f,
                rotationDegrees = t.rotation,
            )
        }
    }
}

/**
 * Luminance image plus a mip pyramid, prepared once per import so live previews
 * during gestures stay cheap.
 */
class PreparedImage internal constructor(val width: Int, val height: Int, internal val levels: List<Level>) {
    internal class Level(val w: Int, val h: Int, val px: FloatArray)

    companion object {
        fun from(argb: IntArray, w: Int, h: Int): PreparedImage {
            val levels = ArrayList<Level>()
            var cur = Level(w, h, ImageConverter.luminance(argb))
            levels += cur
            while (cur.w > 1 || cur.h > 1) {
                val nw = max(1, cur.w / 2); val nh = max(1, cur.h / 2)
                val out = FloatArray(nw * nh)
                for (y in 0 until nh) for (x in 0 until nw) {
                    val x0 = min(cur.w - 1, x * 2); val x1 = min(cur.w - 1, x * 2 + 1)
                    val y0 = min(cur.h - 1, y * 2); val y1 = min(cur.h - 1, y * 2 + 1)
                    out[y * nw + x] = (cur.px[y0 * cur.w + x0] + cur.px[y0 * cur.w + x1] +
                        cur.px[y1 * cur.w + x0] + cur.px[y1 * cur.w + x1]) / 4f
                }
                cur = Level(nw, nh, out)
                levels += cur
            }
            return PreparedImage(w, h, levels)
        }
    }
}

internal object TransformSampler {
    private const val SS = 3 // 3×3 supersamples per canvas pixel

    /** Returns luminance (0 black … 1 white) for the canvas; outside the image is white. */
    fun render(img: PreparedImage, dstW: Int, dstH: Int, fit: FitMode, t: ImageTransform): FloatArray {
        val p = ImagePlacement.of(img.width, img.height, dstW, dstH, fit, t)
        val sx = (p.halfWidth * 2f / img.width).toDouble()   // canvas px per source px
        val sy = (p.halfHeight * 2f / img.height).toDouble()
        val out = FloatArray(dstW * dstH) { 1f }
        if (sx <= 0 || sy <= 0) return out

        // Pick a mip level so each supersample covers about one texel.
        val srcPerSample = 1.0 / (min(sx, sy) * SS)
        val level = if (srcPerSample <= 1.0) 0 else floor(ln(srcPerSample) / ln(2.0)).toInt().coerceIn(0, img.levels.lastIndex)
        val lv = img.levels[level]
        val lscaleX = lv.w.toDouble() / img.width
        val lscaleY = lv.h.toDouble() / img.height

        val c = cos(-p.radians); val s = sin(-p.radians)
        for (y in 0 until dstH) for (x in 0 until dstW) {
            var sum = 0.0
            for (j in 0 until SS) for (i in 0 until SS) {
                val qx = x + (i + 0.5) / SS - p.centerX
                val qy = y + (j + 0.5) / SS - p.centerY
                val rx = qx * c - qy * s
                val ry = qx * s + qy * c
                val u = rx / sx + img.width / 2.0     // source pixel coordinates
                val v = ry / sy + img.height / 2.0
                sum += if (u < 0 || v < 0 || u >= img.width || v >= img.height) 1.0
                else bilinear(lv, u * lscaleX, v * lscaleY)
            }
            out[y * dstW + x] = (sum / (SS * SS)).toFloat()
        }
        return out
    }

    private fun bilinear(l: PreparedImage.Level, u: Double, v: Double): Double {
        val fx = (u - 0.5).coerceIn(0.0, (l.w - 1).toDouble())
        val fy = (v - 0.5).coerceIn(0.0, (l.h - 1).toDouble())
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = min(x0 + 1, l.w - 1); val y1 = min(y0 + 1, l.h - 1)
        val ax = fx - x0; val ay = fy - y0
        val top = l.px[y0 * l.w + x0] * (1 - ax) + l.px[y0 * l.w + x1] * ax
        val bot = l.px[y1 * l.w + x0] * (1 - ax) + l.px[y1 * l.w + x1] * ax
        return top * (1 - ay) + bot * ay
    }
}
