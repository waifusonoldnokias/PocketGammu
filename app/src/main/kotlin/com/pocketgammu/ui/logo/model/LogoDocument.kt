package com.pocketgammu.ui.logo.model

import com.pocketgammu.core.nokia.bitmap.MonoBitmap
import com.pocketgammu.core.nokia.image.ConversionSettings
import com.pocketgammu.core.nokia.image.PreparedImage
import com.pocketgammu.core.nokia.logo.LogoFormat

/** Layer pixel values. */
object Px {
    const val CLEAR: Byte = 0
    const val INK: Byte = 1
    const val PAPER: Byte = 2
}

enum class LogoFont(val label: String) { SANS("Sans"), SERIF("Serif"), MONO("Mono"), CONDENSED("Condensed") }

/** A built-in typeface or a .ttf/.otf the user imported (stored by file name). */
sealed interface FontRef {
    val label: String

    data class Builtin(val font: LogoFont) : FontRef {
        override val label: String get() = font.label
    }

    data class Custom(val fileName: String) : FontRef {
        override val label: String get() = fileName.substringBeforeLast('.')
    }

    companion object {
        val DEFAULT: FontRef = Builtin(LogoFont.SANS)
        val builtins: List<FontRef> = LogoFont.entries.map { Builtin(it) }
    }
}

data class TextSpec(
    val text: String,
    val font: FontRef = FontRef.DEFAULT,
    val sizePx: Int = 11,
    val bold: Boolean = true,
    /** Horizontal scale of the glyphs, 100 = normal. */
    val widthPercent: Int = 100,
    /** Extra space between letters, in pixels (can be negative). */
    val spacingPx: Int = 0,
    /** Outline thickness around the letters, 0 = none. */
    val outlinePx: Int = 0,
    /** Paper-coloured letters (with an ink outline) instead of ink letters. */
    val inverted: Boolean = false,
)

/** Original image kept on an image layer so dithering can be re-adjusted later. */
class ImageSource(
    /** Luminance + mip pyramid, built once when the picture is imported. */
    val prepared: PreparedImage,
    val settings: ConversionSettings,
    val whiteIsClear: Boolean,
) {
    val width: Int get() = prepared.width
    val height: Int get() = prepared.height

    fun with(settings: ConversionSettings = this.settings, whiteIsClear: Boolean = this.whiteIsClear) =
        ImageSource(prepared, settings, whiteIsClear)

    companion object {
        fun create(argb: IntArray, width: Int, height: Int, settings: ConversionSettings, whiteIsClear: Boolean) =
            ImageSource(PreparedImage.from(argb, width, height), settings, whiteIsClear)
    }
}

/**
 * One layer. Pixels are stored in layer-local coordinates; [offsetX]/[offsetY] place the
 * layer on the canvas. Drawing layers are canvas-sized; text layers are sized to the text.
 */
class Layer(
    val id: Long,
    val name: String,
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val visible: Boolean = true,
    val text: TextSpec? = null,
    val image: ImageSource? = null,
) {
    fun at(docX: Int, docY: Int): Byte {
        val x = docX - offsetX
        val y = docY - offsetY
        return if (x in 0 until width && y in 0 until height) pixels[y * width + x] else Px.CLEAR
    }

    fun copy(
        name: String = this.name,
        width: Int = this.width,
        height: Int = this.height,
        pixels: ByteArray = this.pixels,
        offsetX: Int = this.offsetX,
        offsetY: Int = this.offsetY,
        visible: Boolean = this.visible,
        text: TextSpec? = this.text,
        image: ImageSource? = this.image,
        id: Long = this.id,
    ) = Layer(id, name, width, height, pixels, offsetX, offsetY, visible, text, image)

    /** Re-materialises the layer as a plain canvas-sized drawing layer (offset applied). */
    fun baked(docW: Int, docH: Int): Layer {
        if (offsetX == 0 && offsetY == 0 && width == docW && height == docH && text == null) return this
        val out = ByteArray(docW * docH)
        for (y in 0 until docH) for (x in 0 until docW) out[y * docW + x] = at(x, y)
        return Layer(id, name, docW, docH, out, visible = visible, image = null)
    }

    val isEmpty: Boolean get() = pixels.all { it == Px.CLEAR }

    companion object {
        fun blank(id: Long, name: String, w: Int, h: Int) = Layer(id, name, w, h, ByteArray(w * h))
    }
}

data class LogoDocument(
    val format: LogoFormat,
    val layers: List<Layer>,
    val activeId: Long,
) {
    val width: Int get() = format.width
    val height: Int get() = format.height
    val active: Layer get() = layers.first { it.id == activeId }
    val activeIndex: Int get() = layers.indexOfFirst { it.id == activeId }

    /** Composite of visible layers, bottom to top, over paper. */
    fun flatten(): MonoBitmap {
        val ink = BooleanArray(width * height)
        for (layer in layers) {
            if (!layer.visible) continue
            for (y in 0 until height) for (x in 0 until width) {
                when (layer.at(x, y)) {
                    Px.INK -> ink[y * width + x] = true
                    Px.PAPER -> ink[y * width + x] = false
                }
            }
        }
        return MonoBitmap(width, height, ink)
    }

    fun replaceActive(layer: Layer): LogoDocument = copy(layers = layers.map { if (it.id == activeId) layer else it })

    fun nextId(): Long = (layers.maxOfOrNull { it.id } ?: 0L) + 1

    /** Changes the canvas size, keeping content centred. */
    fun resizedTo(newFormat: LogoFormat): LogoDocument {
        val dx = (newFormat.width - width) / 2
        val dy = (newFormat.height - height) / 2
        val moved = layers.map { l ->
            if (l.text != null) l.copy(offsetX = l.offsetX + dx, offsetY = l.offsetY + dy)
            else l.copy(offsetX = l.offsetX + dx, offsetY = l.offsetY + dy).baked(newFormat.width, newFormat.height)
                .copy(image = l.image)
        }
        return LogoDocument(newFormat, moved, activeId)
    }

    companion object {
        fun new(format: LogoFormat) = LogoDocument(format, listOf(Layer.blank(1, "Layer 1", format.width, format.height)), 1)
    }
}
