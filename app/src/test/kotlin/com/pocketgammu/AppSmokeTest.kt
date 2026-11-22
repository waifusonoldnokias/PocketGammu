package com.pocketgammu

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Launches the real app and opens every screen, panel and sheet to catch
 * crash-on-open bugs. Runs on the JVM with Robolectric.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AppSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun click(text: String) = rule.onAllNodesWithText(text).onFirst().performClick().also { rule.waitForIdle() }
    private fun clickDesc(desc: String) = rule.onNodeWithContentDescription(desc).performClick().also { rule.waitForIdle() }

    @Test fun everyScreenOpens() {
        // The headline is a vector image now, found by its description.
        rule.onNodeWithContentDescription("PocketGammu", substring = true).assertExists()
        rule.onNodeWithText("Not connected").assertExists()
        // GammuDroid's connection flow: device list, connection card, tips, shareable log.
        rule.onNodeWithText("Cable or dongle").assertExists()
        rule.onNodeWithText("No USB device attached").assertExists()
        rule.onNodeWithText("Phone family (Gammu module)").assertExists()
        rule.onAllNodesWithText("Tips").onFirst().assertExists()
        click("Log")
        rule.onNodeWithText("Share").assertExists()
        click("Close")

        // Logo editor: every panel, the text dialog and the send sheet.
        click("Logo")
        // Colour swatches plus an Erase tool (picking a colour goes back to the pencil).
        for (swatch in listOf("Black", "White", "Transparent", "Erase")) rule.onNodeWithContentDescription(swatch).assertIsDisplayed()
        clickDesc("White"); clickDesc("Transparent"); clickDesc("Black")
        clickDesc("Erase")
        rule.onRoot().performTouchInput { swipe(center, center + Offset(80f, 0f), durationMillis = 200) }
        rule.waitForIdle()
        clickDesc("Undo"); clickDesc("Pencil")
        rule.onNodeWithContentDescription("Save as BMP").assertExists()
        for (panel in listOf("Shapes", "Text", "Image", "Layers", "Draw")) click(panel)
        // Draw a stroke across the middle of the canvas, then undo it.
        rule.onRoot().performTouchInput { swipe(center, center + Offset(120f, 30f), durationMillis = 300) }
        rule.waitForIdle()
        clickDesc("Undo")
        click("Shapes")
        rule.onRoot().performTouchInput { swipe(center - Offset(60f, 20f), center + Offset(60f, 20f), durationMillis = 300) }
        rule.waitForIdle()
        click("Text"); click("Add text")
        // New text options: outline, width/spacing sliders, inverted, font import button.
        click("2 px"); click("Inverted"); click("None")
        org.junit.Assert.assertTrue(rule.onAllNodesWithText("Width", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithContentDescription("Import .ttf or .otf font").assertExists()
        rule.onNodeWithText("Cancel").performClick(); rule.waitForIdle()
        click("Layers"); clickDesc("Add layer"); clickDesc("Duplicate layer"); clickDesc("Delete layer")
        clickDesc("Send logo")
        rule.onNodeWithText("Send logo").assertExists()
        click("By SMS")
        rule.onNodeWithText("Recipient number").assertExists()
        rule.activity.onBackPressedDispatcher.onBackPressed(); rule.waitForIdle()

        // Picture message: 72×28 format, send sheet with a message text field.
        click("Logo")
        click("Operator logo"); click("Picture message")
        rule.onAllNodesWithText("72×28").onFirst().assertExists()
        clickDesc("Send logo")
        rule.onNodeWithText("Send picture message").assertExists()
        rule.onNodeWithText("Message text").assertExists()
        rule.activity.onBackPressedDispatcher.onBackPressed(); rule.waitForIdle()

        // Ringtone editor: keyboard, editing, send sheet.
        click("Ringtone")
        clickDesc("C5"); clickDesc("Delete note")
        click("Staccato")
        clickDesc("Send ringtone")
        rule.onNodeWithText("Send ringtone").assertExists()
        rule.activity.onBackPressedDispatcher.onBackPressed(); rule.waitForIdle()

        // File manager (not connected state).
        click("Files")
        rule.onNodeWithText("No phone connected").assertExists()

        // Settings: theme and every palette.
        click("Home")
        clickDesc("Settings")
        click("Dark")
        for (p in listOf("Teto", "Miku", "Neru", "Gumi", "Rei", "System")) click(p)
        click("Light")
        val haptics = rule.onAllNodesWithText("Haptic feedback", useUnmergedTree = true).fetchSemanticsNodes()
        org.junit.Assert.assertTrue("Settings screen should show the haptics switch", haptics.isNotEmpty())
        rule.onNodeWithText("App Version: ${com.pocketgammu.BuildConfig.VERSION_NAME}").performScrollTo().assertExists()
        rule.onNodeWithText("conceptualized by Noki-min", substring = true).assertExists()
    }
}
