package com.pocketgammu.core.gammu.engine

/**
 * IrDA SIR (asynchronous, ≤115200 bps) framing — a port of Linux net/irda/wrapper.c.
 *
 * Frame on the wire: [XBOF × n] BOF  escaped(A C I… FCS-lo FCS-hi)  EOF
 * Escaping: BOF, EOF and CE become CE, byte xor 0x20.
 */
object Sir {
    const val BOF = 0xC0
    const val XBOF = 0xFF
    const val EOF = 0xC1
    const val CE = 0x7D
    const val TRANS = 0x20

    fun wrap(frame: ByteArray, extraBofs: Int = 0): ByteArray {
        val out = java.io.ByteArrayOutputStream(frame.size * 2 + extraBofs + 6)
        repeat(extraBofs) { out.write(XBOF) }
        out.write(BOF)
        fun put(b: Int) {
            if (b == BOF || b == EOF || b == CE) { out.write(CE); out.write(b xor TRANS) } else out.write(b)
        }
        for (b in frame) put(b.toInt() and 0xFF)
        val fcs = Fcs16.compute(frame).inv() and 0xFFFF
        put(fcs and 0xFF)
        put(fcs ushr 8)
        out.write(EOF)
        return out.toByteArray()
    }

    /** Incremental decoder: feed raw received bytes, collect complete frames with a valid FCS. */
    class Unwrapper(private val maxFrame: Int = 4096) {
        private enum class State { OUTSIDE, BEGIN, INSIDE, ESCAPE }
        private var state = State.OUTSIDE
        private val buf = ByteArray(maxFrame + 2)
        private var len = 0
        var badFrames = 0
            private set

        fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset, sink: (ByteArray) -> Unit) {
            for (i in offset until offset + length) feed(data[i].toInt() and 0xFF, sink)
        }

        fun feed(b: Int, sink: (ByteArray) -> Unit) {
            when (b) {
                BOF -> { state = State.BEGIN; len = 0 }      // (re)start, also inside a broken frame
                EOF -> {
                    if (state == State.INSIDE && len >= 4) {
                        if (Fcs16.compute(buf, 0, len) == Fcs16.GOOD) sink(buf.copyOf(len - 2)) else badFrames++
                    }
                    state = State.OUTSIDE; len = 0
                }
                CE -> if (state == State.BEGIN || state == State.INSIDE) state = State.ESCAPE
                else -> when (state) {
                    State.OUTSIDE -> Unit                      // XBOFs and noise between frames
                    State.BEGIN, State.INSIDE -> append(b)
                    State.ESCAPE -> append(b xor TRANS)
                }
            }
        }

        private fun append(b: Int) {
            if (len >= buf.size) { state = State.OUTSIDE; len = 0; badFrames++; return }
            buf[len++] = b.toByte()
            state = State.INSIDE
        }

        fun reset() { state = State.OUTSIDE; len = 0 }
    }
}

/** IrDA/PPP FCS-16 (reflected CCITT, poly 0x8408). */
object Fcs16 {
    const val INIT = 0xFFFF
    const val GOOD = 0xF0B8

    private val table = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8408 else c ushr 1 }
        c
    }

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset, init: Int = INIT): Int {
        var fcs = init
        for (i in offset until offset + length) fcs = (fcs ushr 8) xor table[(fcs xor data[i].toInt()) and 0xFF]
        return fcs and 0xFFFF
    }
}
