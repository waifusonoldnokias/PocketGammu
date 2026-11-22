package com.pocketgammu.core.nokia.logo

import com.pocketgammu.core.nokia.bitmap.MonoBitmap
import com.pocketgammu.core.nokia.sms.SmartMessage

/** Mobile network code used to bind an operator logo to a SIM's network. */
data class NetworkCode(val mcc: String, val mnc: String) {
    init {
        require(mcc.length == 3 && mcc.all(Char::isDigit)) { "MCC must be 3 digits" }
        require(mnc.length == 2 && mnc.all(Char::isDigit)) { "MNC must be 2 digits" }
    }

    /** Nokia BCD layout, identical to Gammu's NOKIA_EncodeNetworkCode: 244 05 -> 42 F4 50. */
    fun toBcd(): ByteArray {
        val d = (mcc + mnc).map { it - '0' }
        return byteArrayOf(
            ((d[1] shl 4) or d[0]).toByte(),
            (0xF0 or d[2]).toByte(),
            ((d[4] shl 4) or d[3]).toByte(),
        )
    }

    override fun toString(): String = "$mcc $mnc"

    companion object {
        fun parse(text: String): NetworkCode? {
            val digits = text.filter(Char::isDigit)
            if (digits.length != 5) return null
            return runCatching { NetworkCode(digits.take(3), digits.drop(3)) }.getOrNull()
        }

        fun fromBcd(b: ByteArray, offset: Int = 0): NetworkCode {
            fun lo(i: Int) = b[offset + i].toInt() and 0x0F
            fun hi(i: Int) = (b[offset + i].toInt() ushr 4) and 0x0F
            return NetworkCode("${lo(0)}${hi(0)}${lo(1)}", "${lo(2)}${hi(2)}")
        }
    }
}

/**
 * Builds Smart Messaging payloads, byte-for-byte compatible with Gammu
 * (gsmmulti.c: SMS_NokiaOperatorLogo / SMS_NokiaCallerLogo).
 *
 * Payload = [network code (operator only)] 00 <w> <h> 01 <OTA bitmap>
 */
object SmartLogoEncoder {

    fun operatorLogo(bitmap: MonoBitmap, network: NetworkCode): SmartMessage =
        SmartMessage(
            destinationPort = LogoKind.OPERATOR.smsPort!!,
            payload = network.toBcd() + otaBitmap(bitmap),
        )

    fun callerGroupLogo(bitmap: MonoBitmap): SmartMessage =
        SmartMessage(
            destinationPort = LogoKind.CALLER_GROUP.smsPort!!,
            payload = otaBitmap(bitmap),
        )

    /** Longest picture-message text (Latin-1): at most 6 //SCKL texts (3 binary SMS). */
    const val PICTURE_TEXT_MAX = 120

    /**
     * Picture message, byte-for-byte as Gammu's GSM_EncodeSMS30MultiPartSMS:
     * `30` (version), `02 01 00` (OTA bitmap item, 256 bytes) + 72×28 OTA bitmap,
     * then `00 00 <len>` + ISO-8859-1 text when there is a text.
     */
    fun pictureMessage(bitmap: MonoBitmap, text: String): SmartMessage {
        require(bitmap.width == 72 && bitmap.height == 28) { "Picture messages are 72×28" }
        val bytes = latin1(text.take(PICTURE_TEXT_MAX))
        var payload = byteArrayOf(0x30, 0x02, 0x01, 0x00) + otaBitmap(bitmap)
        if (bytes.isNotEmpty()) payload += byteArrayOf(0x00, 0x00, bytes.size.toByte()) + bytes
        return SmartMessage(destinationPort = LogoKind.PICTURE.smsPort!!, payload = payload)
    }

    /** ISO-8859-1; characters outside it become '?'. */
    fun latin1(text: String): ByteArray = ByteArray(text.length) { i ->
        val c = text[i].code
        (if (c in 0..255) c else '?'.code).toByte()
    }

    fun otaBitmap(bitmap: MonoBitmap): ByteArray {
        require(bitmap.width <= 255 && bitmap.height <= 255) { "OTA bitmaps are limited to 255×255" }
        return byteArrayOf(0x00, bitmap.width.toByte(), bitmap.height.toByte(), 0x01) + bitmap.toOtaBytes()
    }
}
