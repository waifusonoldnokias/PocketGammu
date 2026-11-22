package com.pocketgammu.core.nokia

import com.pocketgammu.core.nokia.bitmap.MonoBitmap
import com.pocketgammu.core.nokia.image.ConversionSettings
import com.pocketgammu.core.nokia.image.DitherMode
import com.pocketgammu.core.nokia.image.FitMode
import com.pocketgammu.core.nokia.image.ImageConverter
import com.pocketgammu.core.nokia.logo.NetworkCode
import com.pocketgammu.core.nokia.logo.SmartLogoEncoder
import com.pocketgammu.core.nokia.ringtone.DurationSpec
import com.pocketgammu.core.nokia.ringtone.MidiImporter
import com.pocketgammu.core.nokia.ringtone.Note
import com.pocketgammu.core.nokia.ringtone.NoteDuration
import com.pocketgammu.core.nokia.ringtone.NoteName
import com.pocketgammu.core.nokia.ringtone.NoteStyle
import com.pocketgammu.core.nokia.ringtone.RingtoneFileType
import com.pocketgammu.core.nokia.ringtone.RingtoneImporter
import com.pocketgammu.core.nokia.ringtone.Rtttl
import com.pocketgammu.core.nokia.ringtone.SmartRingtoneCodec
import com.pocketgammu.core.nokia.sms.SckTextEncoder
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NokiaCodecTest {

    private val nokiaTune =
        "Nokia:d=4,o=5,b=180:8e6,8d6,f#,g#,8c#6,8b,d,e,8b,8a,c#,e,2a"

    @Test fun networkCodeBcdMatchesGammu() {
        assertContentEquals(byteArrayOf(0x42, 0xF4.toByte(), 0x50), NetworkCode("244", "05").toBcd())
        assertEquals(NetworkCode("466", "92"), NetworkCode.fromBcd(NetworkCode("466", "92").toBcd()))
        assertEquals(NetworkCode("310", "26"), NetworkCode.parse("310-26"))
    }

    @Test fun operatorLogoPayloadLayout() {
        val bmp = MonoBitmap(72, 14).edit { this[0, 0] = true; this[71, 13] = true }
        val msg = SmartLogoEncoder.operatorLogo(bmp, NetworkCode("244", "05"))
        assertEquals(0x1582, msg.destinationPort)
        assertEquals(3 + 4 + 126, msg.payload.size)
        assertContentEquals(byteArrayOf(0x42, 0xF4.toByte(), 0x50, 0x00, 72, 14, 0x01), msg.payload.copyOfRange(0, 7))
        assertEquals(0x80.toByte(), msg.payload[7])          // first pixel = MSB
        assertEquals(0x01.toByte(), msg.payload.last())       // last pixel = LSB
        assertEquals(bmp, MonoBitmap.fromOtaBytes(72, 14, msg.payload, 7))
    }

    @Test fun sckSplitsIntoConcatenatedParts() {
        val msg = SmartLogoEncoder.operatorLogo(MonoBitmap(72, 14), NetworkCode("244", "05"))
        val parts = SckTextEncoder.encode(msg, reference = 0x2A)
        assertEquals(2, parts.size)
        parts.forEach { assertTrue(it.length <= 160, "part too long: ${it.length}") }
        assertTrue(parts[0].startsWith("//SCKL158200002A0201 "))
        assertTrue(parts[1].startsWith("//SCKL158200002A0202 "))
        assertEquals(msg.hex(), parts.joinToString("") { it.substringAfter(' ') })
    }

    @Test fun sckShortFormForSmallPayloads() {
        val tone = Rtttl.parse("Beep:d=4,o=5,b=120:c,e,g")
        val parts = SckTextEncoder.encode(SmartRingtoneCodec.toSmartMessage(tone), 1)
        assertEquals(1, parts.size)
        assertTrue(parts[0].startsWith("//SCKL1581 024A3A"), parts[0])
    }

    @Test fun ringtoneHeaderAndRoundTrip() {
        val tone = Rtttl.parse(nokiaTune).copy(style = NoteStyle.STACCATO)
        val enc = SmartRingtoneCodec.encode(tone)
        assertContentEquals(byteArrayOf(0x02, 0x4A, 0x3A), enc.bytes.copyOfRange(0, 3))
        assertEquals(0x00.toByte(), enc.bytes.last())
        val dec = SmartRingtoneCodec.decode(enc.bytes)
        assertEquals("Nokia", dec.name)
        assertEquals(180, dec.tempo)
        assertEquals(NoteStyle.STACCATO, dec.style)
        assertEquals(tone.notes, dec.notes)
        assertTrue(enc.bytes.size <= 133, "Nokia tune should fit one binary SMS")
    }

    @Test fun ringtoneTruncatesAtByteBudget() {
        val notes = List(200) { Note(NoteName.C, 5, NoteDuration.EIGHTH) }
        val enc = SmartRingtoneCodec.encode(com.pocketgammu.core.nokia.ringtone.Ringtone("Long", 125, notes = notes), maxBytes = 60)
        assertTrue(enc.bytes.size <= 60)
        assertTrue(enc.truncated)
        assertEquals(enc.notesWritten, SmartRingtoneCodec.decode(enc.bytes).notes.size)
    }

    @Test fun rtttlParsesAndWrites() {
        val t = Rtttl.parse(nokiaTune)
        assertEquals(13, t.notes.size)
        assertEquals(Note(NoteName.E, 6, NoteDuration.EIGHTH), t.notes[0])
        assertEquals(Note(NoteName.FIS, 5, NoteDuration.QUARTER), t.notes[2])
        assertEquals(Note(NoteName.A, 5, NoteDuration.HALF), t.notes.last())
        val again = Rtttl.parse(Rtttl.write(t))
        assertEquals(t.notes, again.notes)
        assertEquals(t.tempo, again.tempo)
        val dotted = Rtttl.parse("x:d=4,o=5,b=100:8e.6,8e6.,p,h")
        assertEquals(DurationSpec.DOTTED, dotted.notes[0].spec)
        assertEquals(DurationSpec.DOTTED, dotted.notes[1].spec)
        assertTrue(dotted.notes[2].isRest)
        assertEquals(NoteName.H, dotted.notes[3].name)
    }

    @Test fun nrtWithVendorHeaderIsDetected() {
        val ott = SmartRingtoneCodec.encode(Rtttl.parse(nokiaTune)).bytes
        val file = byteArrayOf(0x00, 0x11, 0x22, 0x00) + ott
        assertEquals(RingtoneFileType.NRT, RingtoneImporter.detect(file))
        assertEquals(13, RingtoneImporter.import(file, "tune.nrt").notes.size)
        assertEquals(RingtoneFileType.RTTTL, RingtoneImporter.detect(nokiaTune.toByteArray()))
    }

    @Test fun midiImportProducesTopLine() {
        // One track, 480 tpq, 120 bpm: C5 quarter + E5 (chord, lower G4 ignored), rest, A5 eighth.
        val ev = ArrayList<Byte>()
        fun b(vararg x: Int) = x.forEach { ev += it.toByte() }
        b(0x00, 0xFF, 0x51, 0x03, 0x07, 0xA1, 0x20)            // tempo 500000
        b(0x00, 0x90, 72, 100); b(0x00, 0x90, 67, 100)          // C5 + G4 together
        b(0x83, 0x60, 0x80, 72, 0); b(0x00, 0x80, 67, 0)        // after 480 ticks
        b(0x83, 0x60, 0x90, 81, 100)                            // 480 ticks rest, A5
        b(0x81, 0x70, 0x80, 81, 0)                              // 240 ticks
        b(0x00, 0xFF, 0x2F, 0x00)
        val trk = ev.toByteArray()
        val smf = "MThd".toByteArray() + byteArrayOf(0, 0, 0, 6, 0, 0, 0, 1, 0x01, 0xE0.toByte()) +
            "MTrk".toByteArray() + byteArrayOf(0, 0, 0, trk.size.toByte()) + trk
        assertEquals(RingtoneFileType.MIDI, RingtoneImporter.detect(smf))
        val tone = MidiImporter.import(smf, "Test")
        assertEquals(125, tone.tempo)                            // nearest Nokia tempo to 120
        assertEquals(listOf("C5", "Rest", "A5"), tone.notes.map { it.label() })
        assertEquals(NoteDuration.QUARTER, tone.notes[0].duration)
        assertEquals(NoteDuration.EIGHTH, tone.notes[2].duration)
    }

    @Test fun ditheringProducesExpectedDensity() {
        val w = 64; val h = 32
        val grey = IntArray(w * h) { 0xFF808080.toInt() }
        for (mode in DitherMode.entries) {
            val bmp = ImageConverter.convert(grey, w, h, w, h, ConversionSettings(mode = mode, fit = FitMode.STRETCH))
            val ratio = bmp.inkCount().toDouble() / (w * h)
            if (mode == DitherMode.THRESHOLD) continue
            assertTrue(ratio in 0.35..0.65, "$mode ink ratio $ratio")
        }
        val black = IntArray(4) { 0xFF000000.toInt() }
        assertEquals(72 * 14, ImageConverter.convert(black, 2, 2, 72, 14, ConversionSettings(fit = FitMode.STRETCH)).inkCount())
        // FIT on a square source letterboxes with white
        val fit = ImageConverter.convert(black, 2, 2, 72, 14, ConversionSettings(mode = DitherMode.THRESHOLD))
        assertEquals(14 * 14, fit.inkCount())
    }
}
