package com.pocketgammu

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketgammu.core.designsystem.theme.PocketGammuTheme
import com.pocketgammu.core.nokia.image.ConversionSettings
import com.pocketgammu.core.nokia.image.ImageConverter
import com.pocketgammu.ui.logo.ImageImportScreen
import com.pocketgammu.ui.logo.model.ImageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ImageImportScreenTest {
    @get:Rule val rule = createComposeRule()

    private var confirmed: ImageSource? = null

    private fun show() {
        // 200×100 grey gradient: with FIT it spans the full width of an 84×48 canvas.
        val argb = IntArray(200 * 100) { i -> 0xFF000000.toInt() or ((i % 200) * 0x010101) }
        val src = ImageSource.create(argb, 200, 100, ConversionSettings(), whiteIsClear = true)
        rule.setContent {
            PocketGammuTheme {
                var open by mutableStateOf(true)
                if (open) ImageImportScreen(
                    source = src, isUpdate = false, canvasWidth = 84, canvasHeight = 48,
                    preview = { s -> ImageConverter.convert(s.prepared, 84, 48, s.settings) },
                    onDismiss = { open = false },
                    onConfirm = { confirmed = it; open = false },
                )
            }
        }
        rule.waitForIdle()
    }

    private val preview get() = rule.onNode(hasContentDescription("Image preview", substring = true))
    private fun chip(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test fun touchControlsChangeTheTransform() {
        show()
        preview.assertIsDisplayed()
        assertTrue(chip("100%") && chip("0°"))

        // Pinch out → scale grows.
        preview.performTouchInput {
            pinch(center - Offset(40f, 0f), center - Offset(120f, 0f), center + Offset(40f, 0f), center + Offset(120f, 0f), durationMillis = 300)
        }
        rule.waitForIdle()

        // Reset, then drag the right-hand width handle inwards.
        rule.onNodeWithContentDescription("Reset position").performClick(); rule.waitForIdle()
        preview.performTouchInput {
            val x = width - 28.dp.toPx()
            swipe(Offset(x, centerY), Offset(x - 120f, centerY), durationMillis = 300)
        }
        rule.waitForIdle()

        // Rotate 90° with the button, move with one finger, then confirm.
        rule.onNodeWithContentDescription("Rotate 90 degrees").performClick(); rule.waitForIdle()
        assertTrue(chip("90°"))
        preview.performTouchInput { swipe(center, center + Offset(60f, 20f), durationMillis = 300) }
        rule.waitForIdle()
        rule.onNodeWithText("Add layer").performClick(); rule.waitForIdle()

        val t = confirmed!!.settings.transform
        assertEquals(90f, t.rotation, 0.01f)
        assertTrue("width handle should narrow the image (was ${t.widthScale})", t.widthScale < 0.95f)
        assertNotEquals(0f, t.offsetX)
    }

    @Test fun pinchScalesAndTwistRotates() {
        show()
        preview.performTouchInput {
            pinch(center - Offset(40f, 0f), center - Offset(120f, 0f), center + Offset(40f, 0f), center + Offset(120f, 0f), durationMillis = 300)
        }
        rule.waitForIdle()
        rule.onNodeWithText("Add layer").performClick(); rule.waitForIdle()
        val t = confirmed!!.settings.transform
        assertTrue("pinch out should enlarge (scale ${t.scale})", t.scale > 1.5f)
        assertEquals(0f, t.rotation, 0.5f)
    }
}
