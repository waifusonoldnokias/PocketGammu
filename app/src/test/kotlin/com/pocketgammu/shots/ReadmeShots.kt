package com.pocketgammu.shots

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketgammu.MainActivity
import com.pocketgammu.PocketGammuApplication
import com.pocketgammu.core.gammu.model.ConnectionType
import com.pocketgammu.core.gammu.model.PhoneFamily
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the README screenshots against a simulated 3510i. Run with -PreadmeShots (see DEVELOPMENT.md). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadmeShots {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun settle() { rule.waitForIdle(); repeat(10) { rule.mainClock.advanceTimeBy(100) }; rule.waitForIdle() }
    private fun click(t: String) { rule.onAllNodesWithText(t).onFirst().performClick(); settle() }
    private fun desc(d: String) { rule.onNodeWithContentDescription(d).performClick(); settle() }
    private fun shot(name: String) {
        settle()
        val dir = File("build/readme-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun theme() {
        desc("Settings"); click("Light"); click(System.getProperty("shots.scheme") ?: "Rei")
        rule.activity.onBackPressedDispatcher.onBackPressed(); settle()
    }

    /** Once a phone is connected the app never goes idle in Robolectric, so connecting comes last. */
    private fun connect() {
        val app = rule.activity.application as PocketGammuApplication
        runBlocking { app.container.phone.connect(SimulatedS40Phone(), ConnectionType.FBUS, PhoneFamily.AUTO).getOrThrow() }
        Thread.sleep(1500)
    }

    /** 5×7 pixel font, enough letters for "PocketGammu". */
    private val glyphs = mapOf(
        'P' to "11110 10001 10001 11110 10000 10000 10000", 'o' to "00000 00000 01110 10001 10001 10001 01110",
        'c' to "00000 00000 01110 10000 10000 10001 01110", 'k' to "10000 10000 10010 10100 11000 10100 10010",
        'e' to "00000 00000 01110 10001 11111 10000 01110", 't' to "01000 01000 11100 01000 01000 01001 00110",
        'G' to "01110 10001 10000 10111 10001 10001 01111", 'a' to "00000 00000 01110 00001 01111 10001 01111",
        'm' to "00000 00000 11010 10101 10101 10001 10001", 'u' to "00000 00000 10001 10001 10001 10011 01101",
    )

    /** Paints [word] with the pencil, one horizontal run of pixels per stroke, like a person would. */
    private fun drawWord(word: String, x0: Int, y0: Int) {
        val box = rule.onNodeWithTag("logoCanvas").fetchSemanticsNode().boundsInRoot
        val margin = 16f * rule.density.density
        val cell = minOf((box.width - 2 * margin) / 72, (box.height - 2 * margin) / 14)
        val origin = androidx.compose.ui.geometry.Offset(box.center.x - 36 * cell, box.center.y - 7 * cell)
        fun at(x: Int, y: Int) = origin + androidx.compose.ui.geometry.Offset((x + 0.5f) * cell, (y + 0.5f) * cell)
        word.forEachIndexed { i, ch ->
            val rows = glyphs.getValue(ch).split(' ')
            rows.forEachIndexed { y, row ->
                var x = 0
                while (x < row.length) {
                    if (row[x] == '1') {
                        var end = x
                        while (end + 1 < row.length && row[end + 1] == '1') end++
                        val gx = x0 + i * 6
                        rule.onRoot().performTouchInput {
                            down(at(gx + x, y0 + y))
                            for (k in x + 1..end) moveTo(at(gx + k, y0 + y))
                            up()
                        }
                        rule.waitForIdle()
                        x = end + 1
                    } else x++
                }
            }
        }
        settle()
    }

    @Test fun editorsThenHome() {
        theme()
        click("Logo")
        click("Draw")
        drawWord("PocketGammu", x0 = 3, y0 = 3)
        shot("logo")

        click("Ringtone")
        // The editor opens with a melody already loaded; shown as is.
        shot("ringtone")

        click("Home")
        connect()
        shot("home")
    }

    @Test fun files() {
        theme()
        click("Files")
        connect()
        click("C (Permanent_memory)")
        Thread.sleep(800); settle()
        click("Images")
        Thread.sleep(800); settle()
        shot("files")
    }
}
