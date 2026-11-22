package com.pocketgammu.ui.logo.model

import com.pocketgammu.core.nokia.logo.LogoFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoDocumentTest {

    @Test fun layersCompositeTopDown() {
        val f = LogoFormat.OPERATOR_DCT3
        val bottom = Layer.blank(1, "a", f.width, f.height).also { Painter(f.width, f.height, it.pixels).rect(0, 0, 9, 9, Px.INK, filled = true) }
        val top = Layer.blank(2, "b", f.width, f.height).also { Painter(f.width, f.height, it.pixels).plot(5, 5, Px.PAPER) }
        val doc = LogoDocument(f, listOf(bottom, top), 2)
        val flat = doc.flatten()
        assertTrue(flat[0, 0])
        assertFalse(flat[5, 5])                 // knocked out by the paper pixel on top
        assertEquals(99, flat.inkCount())
        assertEquals(100, doc.copy(layers = listOf(bottom, top.copy(visible = false))).flatten().inkCount())
    }

    @Test fun fillUsesCompositeRegion() {
        val w = 10; val h = 10
        val outline = Layer.blank(1, "o", w, h).also { Painter(w, h, it.pixels).rect(2, 2, 7, 7, Px.INK, filled = false) }
        val doc = LogoDocument(LogoFormat.OPERATOR_DCT3, emptyList(), 1)
        val composite = BooleanArray(w * h) { outline.pixels[it] == Px.INK }
        val fillLayer = ByteArray(w * h)
        Painter(w, h, fillLayer).fill(4, 4, composite, Px.INK)
        assertEquals(16, fillLayer.count { it == Px.INK })   // 4×4 interior only
        assertTrue(doc.layers.isEmpty())
    }

    @Test fun movedLayerBakesWithOffset() {
        val l = Layer.blank(1, "l", 4, 4).also { it.pixels[0] = Px.INK }.copy(offsetX = 2, offsetY = 1)
        val baked = l.baked(4, 4)
        assertEquals(Px.INK, baked.pixels[1 * 4 + 2])
        assertEquals(0, baked.offsetX)
    }

    @Test fun resizeKeepsContentCentred() {
        val doc = LogoDocument.new(LogoFormat.OPERATOR_DCT3)
        val l = doc.active.also { it.pixels[0] = Px.INK }
        val big = doc.replaceActive(l).resizedTo(LogoFormat.STARTUP_DCT3)
        assertEquals(84, big.width)
        assertTrue(big.flatten()[(84 - 72) / 2, (48 - 14) / 2])
    }

    @Test fun ellipseOutlineIsHollow() {
        val buf = ByteArray(20 * 20)
        Painter(20, 20, buf).ellipse(0, 0, 19, 19, Px.INK, filled = false)
        assertEquals(Px.CLEAR, buf[10 * 20 + 10])
        assertEquals(Px.INK, buf[10 * 20 + 0])
    }
}
