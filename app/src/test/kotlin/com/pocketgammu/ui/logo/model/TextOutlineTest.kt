package com.pocketgammu.ui.logo.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TextOutlineTest {
    private fun dot(w: Int = 5, h: Int = 5) = BooleanArray(w * h).also { it[(h / 2) * w + w / 2] = true }

    @Test fun noOutlineTrimsToGlyph() {
        val r = TextOutline.compose(dot(), 5, 5, outlinePx = 0, inverted = false)
        assertEquals(1, r.width); assertEquals(1, r.height)
        assertEquals(Px.INK, r.pixels[0])
    }

    @Test fun onePixelOutlineUsesAllNeighbours() {
        val r = TextOutline.compose(dot(), 5, 5, outlinePx = 1, inverted = false)
        assertEquals(3, r.width); assertEquals(3, r.height)
        assertEquals(Px.INK, r.pixels[4])
        assertEquals(8, r.pixels.count { it == Px.PAPER })
    }

    @Test fun invertedThickOutlineIsRounded() {
        val r = TextOutline.compose(dot(), 5, 5, outlinePx = 2, inverted = true)
        assertEquals(5, r.width)
        assertEquals(Px.PAPER, r.pixels[12])                 // letter
        assertEquals(20, r.pixels.count { it == Px.INK })    // ring without the 4 corners
        assertEquals(Px.CLEAR, r.pixels[0])                  // corner stays transparent
    }
}
