package com.pocketgammu.core.nokia.image

import com.pocketgammu.core.nokia.bitmap.MonoBitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class DitherMode(val label: String) {
    THRESHOLD("Threshold"),
    FLOYD_STEINBERG("Floyd–Steinberg"),
    ATKINSON("Atkinson"),
    BAYER_4("Ordered 4×4"),
    BAYER_8("Ordered 8×8"),
}

enum class FitMode(val label: String) { FIT("Fit"), FILL("Fill"), STRETCH("Stretch") }

data class ConversionSettings(
    val mode: DitherMode = DitherMode.FLOYD_STEINBERG,
    /** -1..1, added to luminance. */
    val brightness: Float = 0f,
    /** 0..3, 1 = unchanged. */
    val contrast: Float = 1f,
    /** 0..1 cut-off used by THRESHOLD and as the bias for the others. */
    val threshold: Float = 0.5f,
    val gamma: Float = 1f,
    val invert: Boolean = false,
    val fit: FitMode = FitMode.FIT,
    /** Scale, width, rotation and position chosen with the touch controls. */
    val transform: ImageTransform = ImageTransform.IDENTITY,
)

/**
 * Converts arbitrary images to Nokia 1-bit bitmaps. Works on plain ARGB int arrays
 * so it is independent of android.graphics and unit-testable on the JVM.
 */
object ImageConverter {

    fun convert(argb: IntArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int, s: ConversionSettings): MonoBitmap =
        convert(PreparedImage.from(argb, srcW, srcH), dstW, dstH, s)

    /** Use a [PreparedImage] when converting the same picture repeatedly (live preview). */
    fun convert(img: PreparedImage, dstW: Int, dstH: Int, s: ConversionSettings): MonoBitmap {
        val lum = if (s.transform.isIdentity) {
            // Untransformed: exact area-average resampling.
            resample(img.levels[0].px, img.width, img.height, dstW, dstH, s.fit)
        } else {
            TransformSampler.render(img, dstW, dstH, s.fit, s.transform)
        }
        adjust(lum, s)
        val ink = dither(lum, dstW, dstH, s.mode, s.threshold)
        return MonoBitmap(dstW, dstH, ink)
    }

    /** 0 = black, 1 = white. Transparent pixels count as white. */
    fun luminance(argb: IntArray): FloatArray = FloatArray(argb.size) { i ->
        val c = argb[i]
        val a = ((c ushr 24) and 0xFF) / 255f
        val r = ((c ushr 16) and 0xFF) / 255f
        val g = ((c ushr 8) and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
        l * a + (1f - a)
    }

    /**
     * Box-filter (area average) resampling — better than bilinear for big reductions.
     * Areas outside the image (FIT letterboxing) are white.
     */
    fun resample(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int, fit: FitMode): FloatArray {
        val sx: Float
        val sy: Float
        when (fit) {
            FitMode.STRETCH -> { sx = sw.toFloat() / dw; sy = sh.toFloat() / dh }
            FitMode.FIT -> { val k = max(sw.toFloat() / dw, sh.toFloat() / dh); sx = k; sy = k }
            FitMode.FILL -> { val k = min(sw.toFloat() / dw, sh.toFloat() / dh); sx = k; sy = k }
        }
        val ox = (sw - dw * sx) / 2f
        val oy = (sh - dh * sy) / 2f
        val out = FloatArray(dw * dh)
        for (y in 0 until dh) for (x in 0 until dw) {
            val x0 = ox + x * sx; val x1 = x0 + sx
            val y0 = oy + y * sy; val y1 = y0 + sy
            var sum = 0f; var area = 0f
            var yy = kotlin.math.floor(y0).toInt()
            while (yy < y1) {
                val wy = min(y1, yy + 1f) - max(y0, yy.toFloat())
                var xx = kotlin.math.floor(x0).toInt()
                while (xx < x1) {
                    val wx = min(x1, xx + 1f) - max(x0, xx.toFloat())
                    val w = wx * wy
                    val v = if (xx in 0 until sw && yy in 0 until sh) src[yy * sw + xx] else 1f
                    sum += v * w; area += w
                    xx++
                }
                yy++
            }
            out[y * dw + x] = if (area > 0f) sum / area else 1f
        }
        return out
    }

    private fun adjust(l: FloatArray, s: ConversionSettings) {
        for (i in l.indices) {
            var v = l[i]
            if (s.gamma != 1f) v = v.coerceIn(0f, 1f).pow(1f / s.gamma)
            v = (v - 0.5f) * s.contrast + 0.5f + s.brightness
            if (s.invert) v = 1f - v
            l[i] = v.coerceIn(0f, 1f)
        }
    }

    /** Returns ink (true = black). */
    fun dither(lum: FloatArray, w: Int, h: Int, mode: DitherMode, threshold: Float = 0.5f): BooleanArray {
        val ink = BooleanArray(w * h)
        when (mode) {
            DitherMode.THRESHOLD -> for (i in lum.indices) ink[i] = lum[i] < threshold
            DitherMode.BAYER_4, DitherMode.BAYER_8 -> {
                val m = if (mode == DitherMode.BAYER_4) BAYER4 else BAYER8
                val n = if (mode == DitherMode.BAYER_4) 4 else 8
                val bias = threshold - 0.5f
                for (y in 0 until h) for (x in 0 until w) {
                    val t = (m[(y % n) * n + (x % n)] + 0.5f) / (n * n) + bias
                    ink[y * w + x] = lum[y * w + x] < t
                }
            }
            DitherMode.FLOYD_STEINBERG -> diffuse(lum.copyOf(), w, h, threshold, ink, FS)
            DitherMode.ATKINSON -> diffuse(lum.copyOf(), w, h, threshold, ink, ATKINSON)
        }
        return ink
    }

    private class Kernel(val taps: List<Triple<Int, Int, Float>>)

    private val FS = Kernel(listOf(Triple(1, 0, 7 / 16f), Triple(-1, 1, 3 / 16f), Triple(0, 1, 5 / 16f), Triple(1, 1, 1 / 16f)))
    private val ATKINSON = Kernel(listOf(1 to 0, 2 to 0, -1 to 1, 0 to 1, 1 to 1, 0 to 2).map { Triple(it.first, it.second, 1 / 8f) })

    /** Serpentine error diffusion. */
    private fun diffuse(l: FloatArray, w: Int, h: Int, t: Float, ink: BooleanArray, k: Kernel) {
        for (y in 0 until h) {
            val ltr = y % 2 == 0
            val xs = if (ltr) 0 until w else (w - 1 downTo 0)
            for (x in xs) {
                val i = y * w + x
                val old = l[i]
                val black = old < t
                ink[i] = black
                val err = old - if (black) 0f else 1f
                for ((dx, dy, f) in k.taps) {
                    val nx = if (ltr) x + dx else x - dx
                    val ny = y + dy
                    if (nx in 0 until w && ny < h) l[ny * w + nx] += err * f
                }
            }
        }
    }

    private val BAYER4 = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
    private val BAYER8 = IntArray(64) { i ->
        val x = i % 8; val y = i / 8
        // Recursive Bayer construction from the 4×4 matrix.
        4 * BAYER4[(y % 4) * 4 + (x % 4)] + intArrayOf(0, 2, 3, 1)[(y / 4) * 2 + (x / 4)]
    }
}
