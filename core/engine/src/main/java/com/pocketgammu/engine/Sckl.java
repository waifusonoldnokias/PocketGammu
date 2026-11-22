package com.pocketgammu.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Nokia's text form of Smart Messaging ("//SCKL"), as in Smart Sender: the
 * port header and the payload travel as printable hex in ordinary text SMS,
 * so any Android phone can send them, split over as many texts as needed.
 *
 * Single message:  //SCKL&lt;dest port&gt; &lt;payload hex&gt;
 * Multi-part:      //SCKL&lt;dest&gt;&lt;source&gt;&lt;reference&gt;&lt;total&gt;&lt;part&gt; &lt;payload hex&gt;
 */
public final class Sckl {
    public static final int PORT_RINGTONE = 0x1581, PORT_OPERATOR_LOGO = 0x1582, PORT_CALLER_LOGO = 0x1583,
            PORT_PICTURE = 0x158A;
    public static final int SMS_CHARS = 160;
    private static final int SHORT_HEADER = "//SCKL1581 ".length();
    private static final int LONG_HEADER = "//SCKL15811581010301 ".length();
    public static final int SINGLE_CAPACITY = (SMS_CHARS - SHORT_HEADER) / 2;
    public static final int FRAGMENT_CAPACITY = (SMS_CHARS - LONG_HEADER) / 2;

    private Sckl() {}

    private static final char[] H = "0123456789ABCDEF".toCharArray();

    private static void hex(StringBuilder sb, int v) {
        sb.append(H[(v >> 4) & 15]).append(H[v & 15]);
    }

    public static int partCount(int payloadLength) {
        if (payloadLength <= SINGLE_CAPACITY) return 1;
        return (payloadLength + FRAGMENT_CAPACITY - 1) / FRAGMENT_CAPACITY;
    }

    public static List<String> fragments(int port, byte[] payload, int reference) throws GsmException {
        List<String> out = new ArrayList<>();
        StringBuilder ph = new StringBuilder();
        hex(ph, port >> 8);
        hex(ph, port);
        if (payload.length <= SINGLE_CAPACITY) {
            StringBuilder sb = new StringBuilder("//SCKL").append(ph).append(' ');
            for (byte b : payload) hex(sb, b);
            out.add(sb.toString());
            return out;
        }
        int total = partCount(payload.length);
        if (total > 255) throw new GsmException(GsmError.INVALIDDATA, "message is too long");
        for (int i = 0; i < total; i++) {
            int from = i * FRAGMENT_CAPACITY, len = Math.min(FRAGMENT_CAPACITY, payload.length - from);
            StringBuilder sb = new StringBuilder("//SCKL").append(ph).append(ph);
            hex(sb, reference);
            hex(sb, total);
            hex(sb, i + 1);
            sb.append(' ');
            for (int k = 0; k < len; k++) hex(sb, payload[from + k]);
            out.add(sb.toString());
        }
        return out;
    }

    /** Standard operator logo: 30 &lt;net 3&gt; 0A &lt;OTA bitmap 72x14&gt;. */
    public static byte[] operatorLogo(GsmBitmap bmp, String networkCode) {
        byte[] b = new byte[300];
        b[0] = 0x30;
        Sms.encodeNetworkCode(b, 1, networkCode);
        b[4] = 0x0A;
        int len = bmp.copyNokia(GsmBitmap.PhoneType.NOKIA_OPERATOR_LOGO, b, 5);
        return java.util.Arrays.copyOf(b, len);
    }

    /** Caller line identification icon: 30 &lt;OTA bitmap 72x14&gt;. */
    public static byte[] callerLogo(GsmBitmap bmp) {
        byte[] b = new byte[300];
        b[0] = 0x30;
        int len = bmp.copyNokia(GsmBitmap.PhoneType.NOKIA_CALLER_LOGO, b, 1);
        return java.util.Arrays.copyOf(b, len);
    }

    /** Smart Messaging 3.0 picture message with optional text. */
    public static byte[] picture(GsmBitmap bmp) {
        return Sms.pictureBody(bmp, false);
    }

    /** Ringtone, as many notes as fit in maxBytes. */
    public static Ringtone.Encoded ringtone(Ringtone tone, int maxBytes) {
        return tone.encodeSmartMessaging(maxBytes);
    }

    private static int ref = (int) (System.nanoTime() & 0xFF);

    public static synchronized int nextReference() {
        ref = (ref + 1) & 0xFF;
        return ref;
    }
}
