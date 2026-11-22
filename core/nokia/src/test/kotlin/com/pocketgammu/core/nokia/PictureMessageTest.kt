package com.pocketgammu.core.nokia

import com.pocketgammu.core.nokia.bitmap.MonoBitmap
import com.pocketgammu.core.nokia.logo.LogoFormat
import com.pocketgammu.core.nokia.logo.SmartLogoEncoder
import com.pocketgammu.core.nokia.sms.SckTextEncoder
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PictureMessageTest {
    private val pic = MonoBitmap(72, 28, BooleanArray(72 * 28) { it % 7 == 0 })

    /** Layout of Gammu's GSM_EncodeSMS30MultiPartSMS for a picture message. */
    @Test fun layoutMatchesGammu() {
        val m = SmartLogoEncoder.pictureMessage(pic, "Hi!")
        val p = m.payload
        assertEquals(0x158A, m.destinationPort)
        assertEquals(listOf(0x30, 0x02, 0x01, 0x00), p.take(4).map { it.toInt() and 0xFF })   // version, OTA item, 256 bytes
        assertEquals(listOf(0x00, 72, 28, 0x01), p.slice(4..7).map { it.toInt() and 0xFF })   // OTA header
        assertEquals(4 + 256, 8 + 252)                                                        // 72×28/8 = 252 bitmap bytes
        assertEquals(listOf(0x00, 0x00, 3), p.slice(260..262).map { it.toInt() and 0xFF })   // ISO text item, length 3
        assertEquals("Hi!", String(p, 263, 3, Charsets.ISO_8859_1))
        assertEquals(266, p.size)
    }

    @Test fun noTextMeansNoTextItem() {
        assertEquals(260, SmartLogoEncoder.pictureMessage(pic, "").payload.size)
    }

    @Test fun textIsLatin1AndCapped() {
        val m = SmartLogoEncoder.pictureMessage(pic, "café 😀" + "x".repeat(200))
        val len = m.payload[262].toInt() and 0xFF
        assertEquals(SmartLogoEncoder.PICTURE_TEXT_MAX, len)
        assertEquals('é'.code, m.payload[266].toInt() and 0xFF)
    }

    @Test fun onlyPictureSizeIsAccepted() {
        assertFailsWith<IllegalArgumentException> { SmartLogoEncoder.pictureMessage(MonoBitmap(72, 14, BooleanArray(72 * 14)), "") }
        assertEquals(72 to 28, LogoFormat.PICTURE.width to LogoFormat.PICTURE.height)
    }

    @Test fun longestTextFitsInSixSckTexts() {
        val m = SmartLogoEncoder.pictureMessage(pic, "x".repeat(SmartLogoEncoder.PICTURE_TEXT_MAX))
        assertEquals(m.payload.size, 260 + 3 + 120)
        assertEquals(true, SckTextEncoder.partCount(m) <= 6)
    }
}
