package com.pocketgammu.core.nokia.sms

/**
 * Text-mode Smart Messaging ("//SCKL") encoder.
 *
 * Header layout (Nokia Smart Messaging spec, text-based headers):
 * ```
 * //SCKL <dst:4hex> [<src:4hex> <ref:2hex> <total:2hex> <seq:2hex>] <space> <hex payload>
 * ```
 * A payload that fits one 160-character SMS uses the short header (`//SCKL1582 ...`).
 * Anything larger is split into concatenated parts that all carry the full header.
 */
object SckTextEncoder {
    const val MAX_SMS_CHARS = 160

    private const val SHORT_HEADER_LEN = 6 + 4 + 1          // "//SCKL" + dst + space
    private const val LONG_HEADER_LEN = 6 + 4 + 4 + 6 + 1   // + src + ref/total/seq + space

    /** Bytes that fit in each concatenated part (hex digits must be even). */
    val BYTES_PER_PART: Int = (MAX_SMS_CHARS - LONG_HEADER_LEN) / 2

    fun encode(message: SmartMessage, reference: Int): List<String> {
        val hex = message.hex()
        val dst = hex4(message.destinationPort)
        if (SHORT_HEADER_LEN + hex.length <= MAX_SMS_CHARS) {
            return listOf("//SCKL$dst $hex")
        }
        val chunks = hex.chunked(BYTES_PER_PART * 2)
        require(chunks.size <= 255) { "Payload too large for concatenated //SCKL (${chunks.size} parts)" }
        val src = hex4(message.sourcePort)
        val ref = hex2(reference and 0xFF)
        val total = hex2(chunks.size)
        return chunks.mapIndexed { i, chunk -> "//SCKL$dst$src$ref$total${hex2(i + 1)} $chunk" }
    }

    fun partCount(message: SmartMessage): Int = encode(message, 0).size

    private fun hex4(v: Int) = v.toString(16).uppercase().padStart(4, '0').takeLast(4)
    private fun hex2(v: Int) = v.toString(16).uppercase().padStart(2, '0').takeLast(2)
}
