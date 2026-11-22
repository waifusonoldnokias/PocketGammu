package com.pocketgammu.core.nokia

import com.pocketgammu.core.nokia.image.ConversionSettings
import com.pocketgammu.core.nokia.image.DitherMode
import com.pocketgammu.core.nokia.image.FitMode
import com.pocketgammu.core.nokia.image.ImageConverter
import com.pocketgammu.core.nokia.image.ImagePlacement
import com.pocketgammu.core.nokia.image.ImageTransform
import com.pocketgammu.core.nokia.image.PreparedImage
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageTransformTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    /** 40×20 image, black, into an 80×40 canvas with FIT → exactly fills it. */
    private val img = PreparedImage.from(IntArray(40 * 20) { black }, 40, 20)
    private fun ink(t: ImageTransform, fit: FitMode = FitMode.FIT) =
        ImageConverter.convert(img, 80, 40, ConversionSettings(mode = DitherMode.THRESHOLD, fit = fit, transform = t))

    @Test fun nearIdentityTransformMatchesUntransformed() {
        assertEquals(80 * 40, ink(ImageTransform.IDENTITY).inkCount())
        assertEquals(80 * 40, ink(ImageTransform(scale = 1.0001f)).inkCount())
    }

    @Test fun scaleHalvesCoverage() {
        val n = ink(ImageTransform(scale = 0.5f)).inkCount()
        assertEquals(40 * 20, n)
        val bmp = ink(ImageTransform(scale = 0.5f))
        assertTrue(bmp[40, 20] && !bmp[5, 5])          // centred
    }

    @Test fun widthStretchesOnlyHorizontally() {
        val bmp = ink(ImageTransform(scale = 0.5f, widthScale = 1.5f))
        assertEquals(60 * 20, bmp.inkCount())
        assertTrue(bmp[11, 20] && !bmp[9, 20])
        assertTrue(!bmp[40, 9] && bmp[40, 10])
    }

    @Test fun offsetMovesImage() {
        val bmp = ink(ImageTransform(scale = 0.5f, offsetX = 10f, offsetY = -5f))
        assertTrue(bmp[30, 5] && !bmp[29, 5] && bmp[69, 24] && !bmp[70, 24])
    }

    @Test fun rotation90SwapsExtents() {
        // 20×10 canvas image rotated 90° becomes 10 wide × 20 tall.
        val bmp = ink(ImageTransform(scale = 0.5f, widthScale = 0.5f * 2f, rotation = 90f))
        val cols = (0 until 80).count { x -> (0 until 40).any { y -> bmp[x, y] } }
        val rows = (0 until 40).count { y -> (0 until 80).any { x -> bmp[x, y] } }
        assertEquals(20, cols); assertEquals(40, rows)
    }

    @Test fun rotation45KeepsArea() {
        val n = ink(ImageTransform(scale = 0.4f, rotation = 45f)).inkCount()
        assertTrue(abs(n - 32 * 16) < 40, "area $n")
    }

    @Test fun placementCornersMatchRender() {
        val p = ImagePlacement.of(40, 20, 80, 40, FitMode.FIT, ImageTransform(scale = 0.5f, offsetX = 10f))
        val (x, y) = p.corners()[0]
        assertEquals(30f, x, 0.01f); assertEquals(10f, y, 0.01f)
    }

    @Test fun snapsNearRightAngles() {
        assertEquals(90f, ImageTransform.snapRotation(87f))
        assertEquals(80f, ImageTransform.snapRotation(80f))
        assertEquals(-90f, ImageTransform.normalizeDegrees(270f))
    }

    @Test fun largeReductionUsesMipsWithoutAliasing() {
        // 512×512 checkerboard of single pixels → mid grey; dithering keeps ~50% ink.
        val big = PreparedImage.from(IntArray(512 * 512) { i -> if ((i % 512 + i / 512) % 2 == 0) black else white }, 512, 512)
        val bmp = ImageConverter.convert(big, 48, 48, ConversionSettings(transform = ImageTransform(rotation = 30f)))
        val r = bmp.inkCount() / (48.0 * 48)
        assertTrue(r in 0.25..0.6, "ratio $r")
    }
}
