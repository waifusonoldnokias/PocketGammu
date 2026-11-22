package com.pocketgammu.core.nokia.bitmap

/**
 * Immutable 1-bit bitmap. `true` = ink (black pixel on a Nokia display).
 *
 * Pure Kotlin so it can be shared by the editor, the Smart Messaging encoders
 * and the Gammu bridge without touching android.graphics.
 */
class MonoBitmap(
    val width: Int,
    val height: Int,
    private val pixels: BooleanArray = BooleanArray(width * height),
) {
    init {
        require(width > 0 && height > 0) { "Bitmap must be at least 1x1" }
        require(pixels.size == width * height) { "Pixel buffer size mismatch" }
    }

    operator fun get(x: Int, y: Int): Boolean =
        if (x in 0 until width && y in 0 until height) pixels[y * width + x] else false

    fun toBooleanArray(): BooleanArray = pixels.copyOf()

    fun inkCount(): Int = pixels.count { it }

    fun edit(block: MutableMonoBitmap.() -> Unit): MonoBitmap =
        MutableMonoBitmap(width, height, pixels.copyOf()).apply(block).toMonoBitmap()

    /**
     * Packs pixels row-major, most significant bit first (pixel = width*y + x).
     * This is the layout used by Smart Messaging OTA bitmaps.
     */
    fun toOtaBytes(): ByteArray {
        val out = ByteArray((width * height + 7) / 8)
        for (i in pixels.indices) {
            if (pixels[i]) out[i / 8] = (out[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
        }
        return out
    }

    override fun equals(other: Any?): Boolean =
        other is MonoBitmap && other.width == width && other.height == height &&
            other.pixels.contentEquals(pixels)

    override fun hashCode(): Int = 31 * (31 * width + height) + pixels.contentHashCode()

    companion object {
        fun fromOtaBytes(width: Int, height: Int, data: ByteArray, offset: Int = 0): MonoBitmap {
            val px = BooleanArray(width * height) { i ->
                val b = data.getOrElse(offset + i / 8) { 0 }.toInt()
                (b and (0x80 ushr (i % 8))) != 0
            }
            return MonoBitmap(width, height, px)
        }
    }
}

class MutableMonoBitmap(val width: Int, val height: Int, private val pixels: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean =
        if (x in 0 until width && y in 0 until height) pixels[y * width + x] else false

    operator fun set(x: Int, y: Int, value: Boolean) {
        if (x in 0 until width && y in 0 until height) pixels[y * width + x] = value
    }

    fun clear(value: Boolean = false) = pixels.fill(value)

    fun invert() {
        for (i in pixels.indices) pixels[i] = !pixels[i]
    }

    fun toMonoBitmap(): MonoBitmap = MonoBitmap(width, height, pixels.copyOf())
}
