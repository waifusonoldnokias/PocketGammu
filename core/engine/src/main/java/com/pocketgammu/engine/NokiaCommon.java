package com.pocketgammu.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared Nokia code: DCT3DCT4_GetModel/GetFirmware (nfunc.c) and the DCT-3
 * helpers from dct3func.c used by both the N6110 and N7110 modules.
 */
public final class NokiaCommon {
    static final byte[] FRAME_HEADER = {0x00, 0x01, 0x00};      // N6110_FRAME_HEADER
    static final byte[] FRAME_HEADER_7110 = {0x00, 0x01, 0x01}; // N7110_FRAME_HEADER

    private NokiaCommon() {}

    static byte[] req(int... tail) {
        byte[] b = new byte[3 + tail.length];
        System.arraycopy(FRAME_HEADER, 0, b, 0, 3);
        for (int i = 0; i < tail.length; i++) b[3 + i] = (byte) tail[i];
        return b;
    }

    /** SplitLines(..., " \n", ...) + GetLineString(n), 1-based. */
    static String token(ProtocolMessage msg, int n) {
        List<int[]> spans = new ArrayList<>();
        boolean white = true;
        int start = 0;
        for (int i = 0; i < msg.length; i++) {
            int c = msg.u(i);
            boolean nowWhite = c == 0x20 || c == 0x0A;
            if (white && !nowWhite) {
                start = i;
                white = false;
            } else if (!white && nowWhite) {
                spans.add(new int[] {start, i});
                white = true;
            }
        }
        if (!white) spans.add(new int[] {start, msg.length});
        if (n < 1 || n > spans.size()) return "";
        int[] sp = spans.get(n - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = sp[0]; i < sp[1]; i++) {
            int c = msg.u(i);
            if (c >= 0x20 && c < 0x7F) sb.append((char) c);
        }
        return sb.toString();
    }

    static double stringToDouble(String v) {
        StringBuilder sb = new StringBuilder();
        boolean dot = false;
        for (char c : v.toCharArray()) {
            if (Character.isDigit(c)) sb.append(c);
            else if (c == '.' && !dot) {
                sb.append(c);
                dot = true;
            } else if (sb.length() > 0) break;
        }
        try {
            return sb.length() == 0 ? 0 : Double.parseDouble(sb.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** DCT3DCT4_ReplyGetModelFirmware. */
    static final Reply.Handler MODEL_FIRMWARE = new Reply.Handler() {
        @Override
        public GsmError handle(ProtocolMessage msg, Session s) {
            s.model = token(msg, 4);
            s.modelInfo = Models.byNumber(s.model);
            s.verDate = token(msg, 3);
            s.version = token(msg, 2);
            s.verNum = stringToDouble(s.version);
            s.log("Received model " + s.model + ", firmware " + s.version + " (" + s.verDate + ")");
            return GsmError.NONE;
        }
    };

    /** DCT3DCT4_GetModel (also fills the firmware fields). */
    static void getModel(Session s) throws IOException {
        if (!s.model.isEmpty()) return;
        s.log("Getting model");
        GsmException.check(s.waitFor(req(0x03, 0x00), 0xD1, 3, RequestId.GET_MODEL));
    }

    // ---- DCT-3 helpers --------------------------------------------------------------

    /** DCT3_EnableSecurity. */
    static void enableSecurity(Session s, int status) throws IOException {
        s.log("Setting state of security commands");
        GsmException.check(s.waitFor(Hex.bytes(0x00, 0x01, 0x64, status), 0x40, 4, RequestId.ENABLE_SECURITY));
    }

    static void addDct3Replies(final PhoneModule m) {
        m.add(0x40, 0x02, 0x64, RequestId.ENABLE_SECURITY, Reply.NONE);
        m.add(0x40, 0x02, 0x66, RequestId.GET_IMEI, new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session s) {
                StringBuilder sb = new StringBuilder();
                for (int i = 4; i < 20 && msg.u(i) != 0; i++) sb.append((char) msg.u(i));
                s.imei = sb.toString();
                return GsmError.NONE;
            }
        });
        m.add(0x0A, 0x03, 0x71, RequestId.GET_NETWORK_INFO, new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session s) {
                return replyNetworkInfo(m, msg);
            }
        });
        m.add(0x0A, 0x03, 0x71, RequestId.INCOMING_FRAME, Reply.NONE);
        m.add(0x02, 0x03, 0x34, RequestId.GET_SMSC, new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session s) {
                Smsc c = m.smsc;
                c.validityRelative = msg.u(8) == 0 ? 0xFF : msg.u(8);
                StringBuilder name = new StringBuilder();
                for (int i = 33; i < msg.length && msg.u(i) != 0; i++) name.append((char) msg.u(i));
                c.name = name.toString();
                c.defaultNumber = Sms.unpackSemiOctetNumber(msg.buffer, 9, true);
                c.number = Sms.unpackSemiOctetNumber(msg.buffer, 21, false);
                return GsmError.NONE;
            }
        });
        m.add(0x02, 0x03, 0x35, RequestId.GET_SMSC, new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session s) {
                return GsmError.INVALIDLOCATION;
            }
        });
        m.add(0x02, 0x03, 0x02, RequestId.INCOMING_FRAME, new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session s) {
                s.log("SMS sent OK");
                m.smsReference = msg.u(5);
                m.smsStatus = 0;
                return GsmError.NONE;
            }
        });
        m.add(0x02, 0x03, 0x03, RequestId.INCOMING_FRAME, new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session s) {
                s.log("SMS send error " + msg.u(6));
                m.smsStatus = Math.max(1, msg.u(6));
                return GsmError.NONE;
            }
        });
        // Frames DCT-3 phones send on their own; answering them with NoneReply keeps the log quiet.
        m.add(0x09, 0x03, 0x80, RequestId.INCOMING_FRAME, Reply.NONE);
        m.add(0x09, 0x03, 0x81, RequestId.INCOMING_FRAME, Reply.NONE);
        m.add(0x02, 0x03, 0xC9, RequestId.INCOMING_FRAME, Reply.NONE);
    }

    /** DCT3_ReplyGetNetworkInfo (the ID_GetNetworkInfo part). */
    static GsmError replyNetworkInfo(PhoneModule m, ProtocolMessage msg) {
        NetworkInfo n = m.netInfo;
        if (n == null) return GsmError.NONE;
        switch (msg.u(8)) {
            case 0x01: n.state = NetworkInfo.State.HOME; break;
            case 0x02: n.state = NetworkInfo.State.ROAMING; break;
            case 0x03: n.state = NetworkInfo.State.REQUESTING; break;
            case 0x04: n.state = NetworkInfo.State.NONE; break;
            default: n.state = NetworkInfo.State.UNKNOWN;
        }
        if (n.registered()) {
            int nameLen = msg.u(17);
            StringBuilder sb = new StringBuilder();
            if (msg.u(18) == 0x00) {
                for (int i = 0; i < nameLen; i++) sb.append((char) ((msg.u(18 + 2 * i) << 8) | msg.u(19 + 2 * i)));
            } else {
                // 9210 style: first 0x00 cut
                for (int i = 0; i < nameLen; i++) sb.append((char) msg.u(18 + 2 * i));
            }
            n.networkName = sb.toString().trim();
            n.networkCode = Sms.decodeNetworkCode(msg.buffer, 14);
            n.cid = String.format("%02X%02X", msg.u(10), msg.u(11));
            n.lac = String.format("%02X%02X", msg.u(12), msg.u(13));
        }
        return GsmError.NONE;
    }

    /** DCT3_GetNetworkInfo. */
    static NetworkInfo dct3NetworkInfo(Session s, PhoneModule m) throws IOException {
        m.netInfo = new NetworkInfo();
        s.log("Getting network info");
        GsmException.check(s.waitFor(req(0x70), 0x0A, 4, RequestId.GET_NETWORK_INFO));
        return m.netInfo;
    }

    /** DCT3_GetSMSC. */
    static Smsc dct3Smsc(Session s, PhoneModule m, int location) throws IOException {
        if (location == 0) throw new GsmException(GsmError.INVALIDLOCATION);
        m.smsc = new Smsc();
        m.smsc.location = location;
        s.log("Getting SMSC");
        GsmException.check(s.waitFor(req(0x33, 0x64, location), 0x02, 4, RequestId.GET_SMSC));
        return m.smsc;
    }

    /** DCT3_GetIMEI. */
    static String dct3Imei(Session s) throws IOException {
        if (!s.imei.isEmpty()) return s.imei;
        enableSecurity(s, 0x01);
        s.log("Getting IMEI");
        GsmException.check(s.waitFor(Hex.bytes(0x00, 0x01, 0x66, 0x00), 0x40, 2, RequestId.GET_IMEI));
        return s.imei;
    }

    /** DCT3_SendSMSMessage. */
    static void dct3WriteSms(Session s, Sms sms) throws IOException {
        byte[] req = new byte[256];
        req[0] = 0x00;
        req[1] = 0x01;
        req[2] = 0x00;
        req[3] = 0x01;
        req[4] = 0x02;
        req[5] = 0x00;
        int length = sms.encodeFrame(Sms.PHONE_SMS_SUBMIT, req, 6, true);
        s.log("Sending sms");
        s.protocol().writeMessage(req, 6 + length, 0x02);
    }
}
