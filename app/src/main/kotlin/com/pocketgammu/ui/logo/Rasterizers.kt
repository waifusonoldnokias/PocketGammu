package com.pocketgammu.ui.logo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.pocketgammu.core.nokia.bitmap.MonoBitmap
import com.pocketgammu.ui.logo.model.TextOutline
import com.pocketgammu.ui.logo.model.TextRaster
import com.pocketgammu.ui.logo.model.TextSpec
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders text without anti-aliasing into layer pixels: glyph mask (with width scaling
 * and letter spacing) via android.graphics, outline via [TextOutline].
 */
object TextRasterizer {
    fun render(spec: TextSpec, typeface: Typeface): TextRaster {
        val paint = Paint().apply {
            isAntiAlias = false
            isSubpixelText = false
            isLinearText = false
            textSize = spec.sizePx.toFloat()
            color = Color.BLACK
            this.typeface = typeface
            textScaleX = spec.widthPercent.coerceIn(25, 400) / 100f
            letterSpacing = spec.spacingPx / spec.sizePx.toFloat().coerceAtLeast(1f)   // em units
        }
        val lines = spec.text.ifEmpty { " " }.split('\n')
        val fm = paint.fontMetrics
        val lineHeight = ceil(fm.descent - fm.ascent).toInt().coerceAtLeast(1)
        // Generous canvas: italic overhangs and negative spacing can spill past measureText.
        val pad = spec.sizePx
        val width = max(1, lines.maxOf { ceil(paint.measureText(it)).toInt() }) + 2 * pad
        val height = lineHeight * lines.size + pad
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        lines.forEachIndexed { i, line -> canvas.drawText(line, pad.toFloat(), -fm.ascent + i * lineHeight, paint) }
        val argb = IntArray(width * height).also { bmp.getPixels(it, 0, width, 0, 0, width, height) }
        bmp.recycle()
        val mask = BooleanArray(width * height) { (argb[it] ushr 24) > 0x7F }
        return TextOutline.compose(mask, width, height, spec.outlinePx, spec.inverted)
    }
}

/** Loads a picked image as a software ARGB array, downscaled so the long edge ≤ [maxEdge]. */
object ImageLoader {
    class Argb(val pixels: IntArray, val width: Int, val height: Int)

    fun load(context: Context, uri: Uri, maxEdge: Int = 512): Argb {
        val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = info.size
                val scale = maxEdge.toFloat() / max(s.width, s.height)
                if (scale < 1f) decoder.setTargetSize((s.width * scale).roundToInt().coerceAtLeast(1), (s.height * scale).roundToInt().coerceAtLeast(1))
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: error("Couldn't open the image")
        }
        val sw = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val px = IntArray(sw.width * sw.height).also { sw.getPixels(it, 0, sw.width, 0, 0, sw.width, sw.height) }
        return Argb(px, sw.width, sw.height)
    }
}

/** Mono bitmap → ImageBitmap using the LCD colours, for crisp nearest-neighbour drawing. */
fun MonoBitmap.toImageBitmap(ink: Int, paper: Int): ImageBitmap {
    val px = IntArray(width * height) { i -> if (this[i % width, i / width]) ink else paper }
    return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
