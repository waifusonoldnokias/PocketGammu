package com.pocketgammu.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * GSM_SMSMessage (submit side only) and the encoders it needs: Gammu's
 * GSM_EncodeSMSFrame, GSM_PackSemiOctetNumber, the UDH table, and the Nokia
 * Smart Messaging cases of GSM_EncodeMultiPartSMS.
 */
public final class Sms {
    public enum Coding { BIT8, DEFAULT, UNICODE }

    /** UDH types from Gammu's UDHHeaders table that Smart Messaging uses. */
    public enum Udh {
        NONE(null, -1, -1, -1),
        CONCATENATED("00 03 01 00 00", 2, 4, 3),
        NOKIA_RINGTONE("05 04 15 81 00 00", -1, -1, -1),
        NOKIA_OPERATOR_LOGO("05 04 15 82 00 00", -1, -1, -1),
        NOKIA_CALLER_LOGO("05 04 15 83 00 00", -1, -1, -1),
        NOKIA_RINGTONE_LONG("05 04 15 81 00 00 00 03 01 00 00", 8, 10, 9),
        NOKIA_OPERATOR_LOGO_LONG("05 04 15 82 00 00 00 03 02 00 00", 8, 10, 9),
        NOKIA_PROFILE_LONG("05 04 15 8a 00 00 00 03 ce 00 00", 8, 10, 9);

        final byte[] text;
        final int id8, part, all;

        Udh(String hex, int id8, int part, int all) {
            this.text = hex == null ? null : Hex.parse(hex);
            this.id8 = id8;
            this.part = part;
            this.all = all;
        }
    }

    public String number = "";
    public Smsc smsc = new Smsc();
    public Udh udhType = Udh.NONE;
    /** UDH including its length byte (UDHL), or empty. */
    public byte[] udh = new byte[0];
    public Coding coding = Coding.BIT8;
    public byte[] data = new byte[0];
    public int cls = -1;
    public boolean deliveryReport = false;
    public int messageReference = 0;
    public int replaceMessage = 0;

    /** GSM_SMSMessageLayout. 255 means "field not present". */
    public static final class Layout {
        public int text = 255, number = 255, smscNumber = 255, tpdcs = 255, dateTime = 255,
                smscTime = 255, tpStatus = 255, tpudl = 255, tpvp = 255, firstbyte = 255,
                tpmr = 255, tppid = 255;

        static Layout of(int text, int number, int smsc, int dcs, int dt, int smsct, int status,
                         int udl, int vp, int first, int mr, int pid) {
            Layout l = new Layout();
            l.text = text;
            l.number = number;
            l.smscNumber = smsc;
            l.tpdcs = dcs;
            l.dateTime = dt;
            l.smscTime = smsct;
            l.tpStatus = status;
            l.tpudl = udl;
            l.tpvp = vp;
            l.firstbyte = first;
            l.tpmr = mr;
            l.tppid = pid;
            return l;
        }
    }

    /** PHONE_SMSSubmit from libgammu/phone/pfunc.c (DCT-3 frame layout). */
    public static final Layout PHONE_SMS_SUBMIT = Layout.of(36, 17, 0, 15, 255, 255, 255, 16, 29, 12, 13, 14);

    // ---- numbers -----------------------------------------------------------------

    /** GSM_PackSemiOctetNumber. out[0] = type of number. Returns semi-octets or octets. */
    public static int packSemiOctetNumber(String number, byte[] out, int off, boolean semioctet) {
        String b = number;
        int format;
        int skip = 0;
        if (b.startsWith("+")) {
            format = 0x91;
            skip = 1;
        } else if (b.startsWith("000")) {
            format = 0x81;
        } else if (b.startsWith("00")) {
            format = 0x91;
            skip = 2;
        } else {
            format = 0x81;
        }
        for (int i = 0; i < b.length(); i++) {
            if ("+0123456789*#pP".indexOf(b.charAt(i)) < 0) format = 0xD0;
        }
        out[off] = (byte) format;
        int length;
        if (format == 0xD0) {
            byte[] packed = packSevenBits(Hex.latin1(b));
            System.arraycopy(packed, 0, out, off + 1, packed.length);
            length = packed.length * 2;
            if (b.length() == 7) length--;
        } else {
            String digits = b.substring(skip);
            encodeBcd(out, off + 1, digits, digits.length(), true);
            length = digits.length();
        }
        if (semioctet) return length;
        if (length % 2 != 0) length++;
        return length / 2 + 1;
    }

    /** EncodeBCD. Non-digits are encoded the way Gammu's arithmetic does it. */
    public static void encodeBcd(byte[] dest, int off, String src, int len, boolean fill) {
        int current = 0;
        for (int i = 0; i < len; i++) {
            int v = src.charAt(i) - '0';
            if ((i & 1) != 0) {
                dest[off + current] = (byte) (dest[off + current] | (v << 4));
                current++;
            } else {
                dest[off + current] = (byte) v;
            }
        }
        if (fill && (len & 1) != 0) dest[off + current] = (byte) (dest[off + current] | 0xF0);
    }

    /** NOKIA_EncodeNetworkCode: "MCC MNC" to 3 bytes. */
    public static void encodeNetworkCode(byte[] buf, int off, String code) {
        String c = (code + "      ").substring(0, 6);
        encodeBcd(buf, off, c, 6, false);
        buf[off + 1] = (byte) (buf[off + 1] | 0xF0);
    }

    /** NOKIA_DecodeNetworkCode: 3 bytes to "MCC MNC". */
    public static String decodeNetworkCode(byte[] b, int off) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            int v = b[off + i] & 0xFF;
            int lo = v & 0x0F, hi = v >> 4;
            if (lo < 10) sb.append((char) ('0' + lo));
            if (hi < 10) sb.append((char) ('0' + hi));
        }
        String d = sb.toString();
        if (d.length() < 5) return d;
        return d.substring(0, 3) + " " + d.substring(3);
    }

    /** GSM_UnpackSemiOctetNumber for the cases Nokia SMSC replies use. */
    public static String unpackSemiOctetNumber(byte[] b, int pos, boolean semioctet) {
        int len = b[pos] & 0xFF;
        if (len == 0) return "";
        int type = b[pos + 1] & 0xFF;
        int digits = semioctet ? len : (len - 1) * 2;
        StringBuilder sb = new StringBuilder();
        if ((type & 0x70) == 0x10) sb.append('+');
        for (int i = 0; i < digits && pos + 2 + i / 2 < b.length; i++) {
            int v = b[pos + 2 + i / 2] & 0xFF;
            int d = (i % 2 == 0) ? (v & 0x0F) : (v >> 4);
            if (d == 0x0F) break;
            sb.append(d < 10 ? (char) ('0' + d) : d == 10 ? '*' : d == 11 ? '#' : '?');
        }
        return sb.toString();
    }

    /** GSM_PackSevenBitsToEight with no offset. */
    static byte[] packSevenBits(byte[] in) {
        int bits = in.length * 7;
        byte[] out = new byte[(bits + 7) / 8];
        for (int i = 0; i < in.length; i++) {
            int v = in[i] & 0x7F;
            for (int k = 0; k < 7; k++) {
                int pos = i * 7 + k;
                if (((v >> k) & 1) != 0) out[pos / 8] |= (byte) (1 << (pos % 8));
            }
        }
        return out;
    }

    // ---- frame -------------------------------------------------------------------

    private static void numberField(Layout l, int field, String number, byte[] buf, int off, boolean semioctet)
            throws GsmException {
        byte[] packed = new byte[40];
        int plen = packSemiOctetNumber(number, packed, 0, semioctet);
        int fieldLength = semioctet ? 2 + (plen + 1) / 2 : 1 + plen;
        if (fieldLength > 12) throw new GsmException(GsmError.INVALIDDATA, "number too long");
        buf[off + field] = (byte) plen;
        System.arraycopy(packed, 0, buf, off + field + 1, fieldLength - 1);
    }

    /**
     * GSM_EncodeSMSFrame for a submit PDU. Writes into buf at off and returns the
     * frame length (Layout.text + user data length).
     */
    public int encodeFrame(Layout l, byte[] buf, int off, boolean clear) throws GsmException {
        if (clear) for (int i = 0; i < l.text; i++) buf[off + i] = 0;
        buf[off + l.firstbyte] |= 0x01;
        if (deliveryReport) buf[off + l.firstbyte] |= 0x20;
        if (l.number != 255) numberField(l, l.number, number, buf, off, true);
        if (l.smscNumber != 255) numberField(l, l.smscNumber, smsc.number, buf, off, false);
        if (l.tpdcs != 255 && cls >= 0 && cls <= 3) {
            buf[off + l.tpdcs] |= (byte) cls;
            buf[off + l.tpdcs] |= (byte) (coding == Coding.UNICODE ? 0x10 : 0xF0);
        }
        if (l.tpvp != 255) {
            buf[off + l.firstbyte] |= 0x10;
            buf[off + l.tpvp] = (byte) smsc.validityRelative;
        }
        if (l.tpmr != 255) buf[off + l.tpmr] = (byte) messageReference;
        if (l.tppid != 255) {
            buf[off + l.tppid] = 0;
            if (replaceMessage > 0 && replaceMessage < 8) buf[off + l.tppid] = (byte) (0x40 + replaceMessage);
        }
        int udhLen = 0;
        if (udh.length > 0) {
            buf[off + l.firstbyte] |= 0x40;
            udhLen = udh.length;
            System.arraycopy(udh, 0, buf, off + l.text, udhLen);
        }
        int size, size2;
        switch (coding) {
            case BIT8:
                buf[off + l.tpdcs] |= 0x04;
                if (data.length > 140 - udhLen) throw new GsmException(GsmError.INVALIDDATA, "SMS too long");
                System.arraycopy(data, 0, buf, off + l.text + udhLen, data.length);
                size = size2 = data.length + udhLen;
                break;
            case UNICODE:
                buf[off + l.tpdcs] |= 0x08;
                System.arraycopy(data, 0, buf, off + l.text + udhLen, data.length);
                size = size2 = data.length + udhLen;
                break;
            default:
                throw new GsmException(GsmError.NOTIMPLEMENTED, "7-bit text SMS");
        }
        buf[off + l.tpudl] = (byte) size2;
        return size + l.text;
    }

    // ---- Smart Messaging multipart encoder --------------------------------------

    public enum SmartType { OPERATOR_LOGO, OPERATOR_LOGO_LONG, CALLER_LOGO, PICTURE, RINGTONE, RINGTONE_LONG }

    private static int udhId() {
        // GSM_MakeSMSIDFromTime: any byte works, it only links the parts.
        return (int) ((System.currentTimeMillis() / 1000) % 256);
    }

    /** GSM_MakeMultiPartSMS for 8-bit Smart Messaging payloads. */
    static List<Sms> makeMultiPart(byte[] payload, Udh udhType, int cls, int id) throws GsmException {
        List<Sms> out = new ArrayList<>();
        int perPart = 140 - (udhType.text.length + 1);
        int len = 0;
        do {
            int take = Math.min(perPart, payload.length - len);
            Sms sms = new Sms();
            sms.udhType = udhType;
            sms.cls = cls;
            sms.coding = Coding.BIT8;
            sms.data = new byte[take];
            System.arraycopy(payload, len, sms.data, 0, take);
            out.add(sms);
            len += take;
            if (out.size() > 6) throw new GsmException(GsmError.INVALIDDATA, "message needs more than 6 SMS");
        } while (len < payload.length);
        for (int j = 0; j < out.size(); j++) {
            Sms sms = out.get(j);
            byte[] u = new byte[udhType.text.length + 1];
            u[0] = (byte) udhType.text.length;
            System.arraycopy(udhType.text, 0, u, 1, udhType.text.length);
            if (udhType.id8 >= 0) u[udhType.id8 + 1] = (byte) id;
            if (udhType.part >= 0) u[udhType.part + 1] = (byte) (j + 1);
            if (udhType.all >= 0) u[udhType.all + 1] = (byte) out.size();
            sms.udh = u;
        }
        return out;
    }

    /** Smart Messaging 3.0 picture message body (GSM_EncodeSMS30MultiPartSMS). */
    static byte[] pictureBody(GsmBitmap bmp, boolean unicode) {
        byte[] b = new byte[800];
        int len = 0;
        b[len++] = 0x30;
        b[len++] = 0x02; // SM30_OTA
        b[len++] = 0x01;
        b[len++] = 0x00;
        len = bmp.copyNokia(GsmBitmap.PhoneType.NOKIA_PICTURE_IMAGE, b, len);
        if (!bmp.text.isEmpty()) {
            if (unicode) {
                byte[] t = Hex.ucs2(bmp.text);
                b[len++] = 0x01; // SM30_UNICODETEXT
                b[len++] = 0x00;
                b[len++] = (byte) t.length;
                System.arraycopy(t, 0, b, len, t.length);
                len += t.length;
            } else {
                byte[] t = Hex.latin1(bmp.text);
                b[len++] = 0x00; // SM30_ISOTEXT
                b[len++] = 0x00;
                b[len++] = (byte) t.length;
                System.arraycopy(t, 0, b, len, t.length);
                len += t.length;
            }
        }
        byte[] out = new byte[len];
        System.arraycopy(b, 0, out, 0, len);
        return out;
    }

    /** The Smart Messaging cases of GSM_EncodeMultiPartSMS. */
    public static List<Sms> encodeSmart(SmartType type, GsmBitmap bmp, Ringtone tone, boolean unicode)
            throws GsmException {
        int id = udhId();
        byte[] b = new byte[1200];
        int len = 0;
        switch (type) {
            case RINGTONE:
            case RINGTONE_LONG: {
                Ringtone.Encoded e = tone.encodeSmartMessaging(140 - 7);
                Udh u = Udh.NOKIA_RINGTONE;
                if (type == SmartType.RINGTONE_LONG && e.packedCommands != tone.noteCount()) {
                    u = Udh.NOKIA_RINGTONE_LONG;
                    e = tone.encodeSmartMessaging((140 - 12) * 3);
                }
                return makeMultiPart(e.data, u, 1, id);
            }
            case OPERATOR_LOGO_LONG:
                if (bmp.width > 72 || bmp.height > 14) {
                    encodeNetworkCode(b, 0, bmp.networkCode);
                    len = bmp.copyNokia(GsmBitmap.PhoneType.NOKIA_7110_OPERATOR_LOGO, b, 3);
                    return makeMultiPart(java.util.Arrays.copyOf(b, len), Udh.NOKIA_OPERATOR_LOGO_LONG, 1, id);
                }
                // fall through
            case OPERATOR_LOGO:
                encodeNetworkCode(b, 0, bmp.networkCode);
                len = bmp.copyNokia(GsmBitmap.PhoneType.NOKIA_OPERATOR_LOGO, b, 3);
                return makeMultiPart(java.util.Arrays.copyOf(b, len), Udh.NOKIA_OPERATOR_LOGO, 1, id);
            case CALLER_LOGO:
                len = bmp.copyNokia(GsmBitmap.PhoneType.NOKIA_CALLER_LOGO, b, 0);
                return makeMultiPart(java.util.Arrays.copyOf(b, len), Udh.NOKIA_CALLER_LOGO, 1, id);
            case PICTURE:
            default:
                return makeMultiPart(pictureBody(bmp, unicode), Udh.NOKIA_PROFILE_LONG, 1, id);
        }
    }
}
