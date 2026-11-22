package com.pocketgammu.core.nokia

import com.pocketgammu.core.nokia.bitmap.BmpWriter
import com.pocketgammu.core.nokia.bitmap.MonoBitmap
import org.junit.Test
import kotlin.test.assertEquals

class BmpWriterTest {
    private fun ByteArray.u16(i: Int) = (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8)
    private fun ByteArray.u32(i: Int) = u16(i) or (u16(i + 2) shl 16)

    @Test fun headerAndPixels() {
        // 3x2: top-left ink, bottom-right ink.
        val bmp = MonoBitmap(3, 2, booleanArrayOf(true, false, false, false, false, true))
        val out = BmpWriter.write(bmp)
        assertEquals('B'.code, out[0].toInt()); assertEquals('M'.code, out[1].toInt())
        assertEquals(out.size, out.u32(2))
        assertEquals(62, out.u32(10)) // pixel data offset
        assertEquals(3, out.u32(18)); assertEquals(2, out.u32(22))
        assertEquals(1, out.u16(28)) // 1 bpp
        assertEquals(0x00FFFFFF, out.u32(58)) // palette[1] = white
        assertEquals(62 + 2 * 4, out.size) // rows padded to 4 bytes
        // Bottom row first: pixels (0,1),(1,1) white, (2,1) black -> 110xxxxx
        assertEquals(0xC0, out[62].toInt() and 0xFF)
        // Top row: (0,0) black, others white -> 011xxxxx
        assertEquals(0x60, out[66].toInt() and 0xFF)
    }

    @Test fun wideRowsArePadded() {
        val out = BmpWriter.write(MonoBitmap(96, 65))
        assertEquals(62 + 12 * 65, out.size)
        assertEquals(0xFF, out[62].toInt() and 0xFF) // blank = all white
    }
}
