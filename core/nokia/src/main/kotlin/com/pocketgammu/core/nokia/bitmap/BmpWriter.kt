package com.pocketgammu.core.nokia.bitmap

/**
 * Writes a [MonoBitmap] as a 1-bit Windows BMP (BITMAPINFOHEADER, 2-colour palette:
 * index 0 = black ink, index 1 = white paper), the format Nokia PC Suite / LogoManager use.
 */
object BmpWriter {
    private const val FILE_HEADER = 14
    private const val INFO_HEADER = 40
    private const val PALETTE = 8

    fun write(bitmap: MonoBitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val stride = ((w + 31) / 32) * 4 // rows are padded to 4 bytes
        val imageSize = stride * h
        val offset = FILE_HEADER + INFO_HEADER + PALETTE
        val out = ByteArray(offset + imageSize)
        var p = 0
        fun u8(v: Int) { out[p++] = v.toByte() }
        fun u16(v: Int) { u8(v); u8(v ushr 8) }
        fun u32(v: Int) { u16(v); u16(v ushr 16) }

        // BITMAPFILEHEADER
        u8('B'.code); u8('M'.code); u32(out.size); u16(0); u16(0); u32(offset)
        // BITMAPINFOHEADER (positive height = bottom-up rows)
        u32(INFO_HEADER); u32(w); u32(h); u16(1); u16(1); u32(0); u32(imageSize)
        u32(2835); u32(2835) // 72 dpi
        u32(2); u32(2)
        // Palette (B, G, R, reserved)
        u32(0x00000000) // 0: black
        u32(0x00FFFFFF) // 1: white

        for (row in 0 until h) {
            val y = h - 1 - row
            val base = offset + row * stride
            for (x in 0 until w) {
                // Bit set = palette index 1 = white, i.e. paper.
                if (!bitmap[x, y]) {
                    val i = base + (x ushr 3)
                    out[i] = (out[i].toInt() or (0x80 ushr (x and 7))).toByte()
                }
            }
        }
        return out
    }
}
