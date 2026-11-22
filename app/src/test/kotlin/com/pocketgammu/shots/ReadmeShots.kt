package com.pocketgammu.shots

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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

    /**
     * Loads test resource readme/startup-logo.png (96×65, one bit) into a "Startup logo
     * (7110 class)" canvas through the real Import image flow: the photo picker is answered
     * with the file, and Threshold keeps the pixels exactly as drawn.
     */
    private fun importStartupLogo() {
        click("Operator logo"); click("Startup logo (7110 class)")
        val file = File.createTempFile("startup-logo", ".png").apply {
            outputStream().use { out -> ReadmeShots::class.java.getResourceAsStream("/readme/startup-logo.png")!!.copyTo(out) }
        }
        click("Image"); click("Import image")
        val activity = org.robolectric.Shadows.shadowOf(rule.activity)
        val request = activity.nextStartedActivityForResult
        activity.receiveResult(request.intent, android.app.Activity.RESULT_OK, android.content.Intent().setData(android.net.Uri.fromFile(file)))
        repeat(30) { Thread.sleep(100); settle() }
        click("Floyd–Steinberg"); click("Threshold")
        click("Add layer")
        click("Draw"); desc("Pencil")
    }


    @Test fun editorsThenHome() {
        theme()
        click("Logo")
        importStartupLogo()
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
