package com.pocketgammu.engine;

/** Small helpers for hex dumps and byte handling. */
public final class Hex {
    private static final char[] DIGITS = "0123456789ABCDEF".toCharArray();

    private Hex() {}

    public static String of(byte[] b) {
        return b == null ? "" : of(b, 0, b.length);
    }

    public static String of(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(' ');
            int v = b[off + i] & 0xFF;
            sb.append(DIGITS[v >> 4]).append(DIGITS[v & 15]);
        }
        return sb.toString();
    }

    public static byte[] parse(String s) {
        String t = s.replaceAll("[^0-9A-Fa-f]", "");
        byte[] out = new byte[t.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(t.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    public static byte[] bytes(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i];
        return out;
    }

    /** Gammu-style UCS-2 big-endian bytes (no terminator). */
    public static byte[] ucs2(String s) {
        byte[] out = new byte[s.length() * 2];
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out[i * 2] = (byte) (c >> 8);
            out[i * 2 + 1] = (byte) c;
        }
        return out;
    }

    /** Latin-1 bytes; characters outside it become '?'. */
    public static byte[] latin1(String s) {
        byte[] out = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out[i] = (byte) (c < 256 ? c : '?');
        }
        return out;
    }

    public static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
