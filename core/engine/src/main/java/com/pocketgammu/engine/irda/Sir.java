package com.pocketgammu.engine.irda;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * IrDA SIR asynchronous framing (IrPHY): XBOF/BOF/EOF, control escape and the
 * 16-bit FCS, as in the Linux net/irda wrapper.c.
 */
public final class Sir {
    public static final int XBOF = 0xFF, BOF = 0xC0, EOF = 0xC1, CE = 0x7D, TRANS = 0x20;
    static final int GOOD_FCS = 0xF0B8;
    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? (c >>> 1) ^ 0x8408 : c >>> 1;
            TABLE[i] = c;
        }
    }

    private Sir() {}

    static int fcs(int fcs, int b) {
        return (fcs >>> 8) ^ TABLE[(fcs ^ b) & 0xFF];
    }

    private static void stuff(ByteArrayOutputStream out, int b) {
        if (b == BOF || b == EOF || b == CE) {
            out.write(CE);
            out.write(b ^ TRANS);
        } else {
            out.write(b);
        }
    }

    /** Wraps one IrLAP frame for transmission. */
    public static byte[] wrap(byte[] frame, int len, int xbofs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(len * 2 + xbofs + 8);
        for (int i = 0; i < xbofs; i++) out.write(XBOF);
        out.write(BOF);
        int f = 0xFFFF;
        for (int i = 0; i < len; i++) {
            int b = frame[i] & 0xFF;
            f = fcs(f, b);
            stuff(out, b);
        }
        f = ~f & 0xFFFF;
        stuff(out, f & 0xFF);
        stuff(out, f >> 8);
        out.write(EOF);
        return out.toByteArray();
    }

    /** async_unwrap_char as a stream decoder that returns frames with a good FCS. */
    public static final class Unwrapper {
        private enum State { OUTSIDE, BEGIN, ESCAPE, INSIDE }

        private State state = State.OUTSIDE;
        private final byte[] buf = new byte[4200];
        private int len;
        private int fcs;
        public int badFrames;

        public List<byte[]> feed(byte[] data, int off, int n) {
            List<byte[]> frames = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                byte[] f = feed(data[off + i] & 0xFF);
                if (f != null) frames.add(f);
            }
            return frames;
        }

        public byte[] feed(int b) {
            switch (b) {
                case BOF:
                    state = State.BEGIN;
                    len = 0;
                    fcs = 0xFFFF;
                    return null;
                case EOF:
                    if (state == State.INSIDE || state == State.ESCAPE) {
                        state = State.OUTSIDE;
                        if (len >= 3 && fcs == GOOD_FCS) {
                            byte[] f = new byte[len - 2];
                            System.arraycopy(buf, 0, f, 0, f.length);
                            return f;
                        }
                        badFrames++;
                    }
                    state = State.OUTSIDE;
                    return null;
                case CE:
                    if (state == State.BEGIN || state == State.INSIDE) state = State.ESCAPE;
                    return null;
                default:
                    if (state == State.OUTSIDE) return null;
                    if (state == State.ESCAPE) b ^= TRANS;
                    state = State.INSIDE;
                    if (len >= buf.length) {
                        state = State.OUTSIDE;
                        badFrames++;
                        return null;
                    }
                    buf[len++] = (byte) b;
                    fcs = fcs(fcs, b);
                    return null;
            }
        }
    }
}
