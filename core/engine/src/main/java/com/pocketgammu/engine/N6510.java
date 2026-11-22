package com.pocketgammu.engine;

import java.io.IOException;

/**
 * Port of the logo, ringtone and SMS parts of
 * libgammu/phone/nokia/dct4s40/6510/n6510.c (DCT-4: 3510, 6310i, 6610, 8310 ...).
 */
public final class N6510 extends PhoneModule {
    private int timeout = 8;
    private int ringtoneId;
    private final S40Files files;

    public N6510(final Session s) {
        super(s);
        add(0x02, 0x03, 0x03, RequestId.INCOMING_FRAME, new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session s) {
                if (msg.u(8) == 0x00) {
                    s.log("SMS sent OK, TPMR for sent sms is " + msg.u(10));
                    smsReference = msg.u(10);
                    smsStatus = 0;
                } else {
                    s.log("SMS not sent OK, error code probably " + msg.u(8));
                    smsStatus = msg.u(8);
                }
                return GsmError.NONE;
            }
        });
        add(0x02, 0x03, 0x15, RequestId.GET_SMSC, new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session s) {
                return replySmsc(msg);
            }
        });
        add(0x0A, 0x03, 0x01, RequestId.GET_NETWORK_INFO, new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session s) {
                return replyNetworkInfo(msg);
            }
        });
        add(0x0A, 0x03, 0xF0, RequestId.GET_NETWORK_INFO, N6110.err(GsmError.NOTSUPPORTED));
        add(0x0A, 0x03, 0x26, RequestId.SET_BITMAP, Reply.NONE);
        for (int sub : new int[] {0x01, 0x02, 0x16, 0x1E, 0x20, 0x2C}) {
            add(0x0A, 0x03, sub, RequestId.INCOMING_FRAME, Reply.NONE);
        }
        for (int sub : new int[] {0x01, 0x0F, 0x10, 0x25}) {
            add(0x7A, 0x04, sub, RequestId.SET_BITMAP, Reply.NONE);
        }
        add(0xDB, 0x03, 0x02, RequestId.SET_RINGTONE, new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session s) {
                ringtoneId = msg.u(15);
                s.log("Ringtone ID received: " + ringtoneId);
                return GsmError.NONE;
            }
        });
        add(0x1F, 0x03, 0x0F, RequestId.SET_RINGTONE, Reply.NONE);
        files = new S40Files(this, s);
    }

    /** The Series 40 file systems (Gammu's getfilesystem, getfiles, addfile ...). */
    public S40Files files() {
        return files;
    }

    @Override
    public String name() {
        return "N6510 (DCT-4)";
    }

    /** N6510_Initialise. */
    @Override
    public void initialise() throws IOException {
        timeout = s.connectionType().infrared ? 40 : 8;
        byte[] f = Hex.bytes(0x01, 0x02, 0x06, 0x0A, 0x14, 0x17, 0x39);
        byte[] req = new byte[5 + f.length];
        System.arraycopy(NokiaCommon.FRAME_HEADER, 0, req, 0, 3);
        req[3] = 0x10;
        req[4] = (byte) f.length;
        System.arraycopy(f, 0, req, 5, f.length);
        s.protocol().writeMessage(req, req.length, 0x10);
    }

    @Override
    public boolean supports(GsmBitmap.Type t) {
        switch (t) {
            case STARTUP_LOGO:
            case OPERATOR_LOGO:
            case WELCOME_NOTE:
            case DEALER_NOTE:
                return true;
            default:
                return false;
        }
    }

    private GsmError replySmsc(ProtocolMessage msg) {
        switch (msg.u(4)) {
            case 0x00: break;
            case 0x02:
            case 0x09: return GsmError.INVALIDLOCATION;
            default: return GsmError.UNKNOWNRESPONSE;
        }
        Smsc c = smsc;
        c.location = msg.u(8);
        c.validityRelative = msg.u(12) == 0 ? 0xFF : msg.u(12);
        int current = 14;
        for (int i = 0; i < msg.u(13); i++) {
            switch (msg.u(current)) {
                case 0x81: {
                    StringBuilder sb = new StringBuilder();
                    for (int j = current + 4; j + 1 < msg.length; j += 2) {
                        int ch = (msg.u(j) << 8) | msg.u(j + 1);
                        if (ch == 0) break;
                        sb.append((char) ch);
                    }
                    c.name = sb.toString();
                    break;
                }
                case 0x82:
                    if (msg.u(current + 2) == 0x01) c.defaultNumber = Sms.unpackSemiOctetNumber(msg.buffer, current + 4, true);
                    else if (msg.u(current + 2) == 0x02) c.number = Sms.unpackSemiOctetNumber(msg.buffer, current + 4, false);
                    else return GsmError.UNKNOWNRESPONSE;
                    break;
                default:
                    return GsmError.UNKNOWNRESPONSE;
            }
            int step = msg.u(current + 1);
            if (step == 0) break;
            current += step;
        }
        return GsmError.NONE;
    }

    private GsmError replyNetworkInfo(ProtocolMessage msg) {
        NetworkInfo n = netInfo;
        if (n == null) return GsmError.NONE;
        int current = msg.u(7) + 7;
        switch (msg.u(8)) {
            case 0x00: n.state = NetworkInfo.State.HOME; break;
            case 0x01: n.state = NetworkInfo.State.ROAMING; break;
            case 0x04:
            case 0x09: n.state = NetworkInfo.State.NONE; break;
            case 0x06: n.state = NetworkInfo.State.DENIED; break;
            default: n.state = NetworkInfo.State.UNKNOWN;
        }
        if (n.registered()) {
            // NOKIA_GetUnicodeString at pos 10: length byte (characters) then UCS-2.
            int len = msg.u(10);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) sb.append((char) ((msg.u(11 + 2 * i) << 8) | msg.u(12 + 2 * i)));
            n.networkName = sb.toString();
            n.networkCode = Sms.decodeNetworkCode(msg.buffer, current + 7);
            n.lac = String.format("%02X%02X", msg.u(current + 1), msg.u(current + 2));
            n.cid = String.format("%02X%02X", msg.u(current + 5), msg.u(current + 6));
        }
        return GsmError.NONE;
    }

    @Override
    public NetworkInfo getNetworkInfo() throws IOException {
        netInfo = new NetworkInfo();
        s.log("Getting network info");
        GsmException.check(s.waitFor(NokiaCommon.req(0x00, 0x00), 0x0A, timeout, RequestId.GET_NETWORK_INFO));
        return netInfo;
    }

    @Override
    public Smsc getSmsc(int location) throws IOException {
        if (location == 0) throw new GsmException(GsmError.INVALIDLOCATION);
        smsc = new Smsc();
        smsc.location = location;
        s.log("Getting SMSC");
        GsmException.check(s.waitFor(NokiaCommon.req(0x14, location, 0x00), 0x02, timeout, RequestId.GET_SMSC));
        return smsc;
    }

    /** N6510_EncodeSMSFrame + N6510_SendSMSMessage. */
    @Override
    protected void writeSms(Sms sms) throws IOException {
        byte[] req = new byte[300];
        byte[] head = Hex.bytes(0x00, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x55, 0x55);
        System.arraycopy(head, 0, req, 0, 9);
        int o = 9, count = 0;
        Sms.Layout l = new Sms.Layout();
        req[o + count++] = 0x01;
        req[o + count++] = 0x02;
        int pos1 = count++;
        l.firstbyte = count++;
        l.tpmr = count++;
        l.tppid = count++;
        l.tpdcs = count++;
        req[o + count++] = 0x00;
        req[o + count++] = 0x04;
        req[o + count++] = (byte) 0x82;
        req[o + count++] = 0x10;
        req[o + count++] = 0x01;
        int pos4 = count++;
        l.number = count;
        count += 12;
        req[o + count++] = (byte) 0x82;
        req[o + count++] = 0x10;
        req[o + count++] = 0x02;
        int pos5 = count++;
        l.smscNumber = count;
        count += 12;
        req[o + count++] = 0x08;
        req[o + count++] = 0x04;
        req[o + count++] = 0x01;
        l.tpvp = count++;
        req[o + count++] = (byte) 0x80;
        int pos2 = count++;
        int pos3 = count++;
        l.tpudl = count++;
        l.text = count;
        int length = sms.encodeFrame(l, req, o, false);
        req[o + pos1] = (byte) (length - 1);
        req[o + pos2] = (byte) (length - l.text + 6);
        req[o + pos3] = (byte) (length - l.text);
        int p4 = (req[o + l.number] & 0xFF) + 4;
        if (p4 % 2 != 0) p4++;
        p4 /= 2;
        req[o + pos4] = (byte) p4;
        int p5 = (req[o + l.smscNumber] & 0xFF) + 1;
        req[o + pos5] = (byte) p5;
        if (p4 > 12 || p5 > 12) throw new GsmException(GsmError.UNKNOWN, "number too long");
        s.log("Sending sms");
        s.protocol().writeMessage(req, length + 9, 0x02);
    }

    /** N6510_SetBitmap. */
    @Override
    public void setBitmap(GsmBitmap b) throws IOException {
        switch (b.type) {
            case STARTUP_LOGO: {
                GsmBitmap.PhoneType t = GsmBitmap.PhoneType.NOKIA_7110_STARTUP_LOGO;
                byte[] req = new byte[22 + GsmBitmap.phoneSize(t)];
                byte[] head = Hex.bytes(0x00, 0x01, 0x01, 0x04, 0x0F, 0x00, 0x00, 0x00, 0x04, 0xC0, 0x02, 0x00,
                        0x41, 0xC0, 0x03, 0x00, 0x60, 0xC0, 0x04);
                System.arraycopy(head, 0, req, 0, head.length);
                if (b.location == 1) b.encode(t, req, 22);
                else if (b.location == 2) java.util.Arrays.fill(req, 5, 20, (byte) 0);
                else throw new GsmException(GsmError.NOTSUPPORTED);
                s.log("Setting startup logo");
                GsmException.check(s.waitFor(req, req.length, 0x7A, timeout, RequestId.SET_BITMAP));
                return;
            }
            case WELCOME_NOTE:
            case DEALER_NOTE: {
                byte[] t = Hex.ucs2(b.text);
                byte[] req = new byte[7 + t.length];
                System.arraycopy(NokiaCommon.FRAME_HEADER, 0, req, 0, 3);
                req[3] = 0x04;
                req[4] = (byte) (b.type == GsmBitmap.Type.DEALER_NOTE ? 0x10 : 0x01);
                System.arraycopy(t, 0, req, 5, t.length);
                GsmException.check(s.waitFor(req, req.length, 0x7A, timeout, RequestId.SET_BITMAP));
                return;
            }
            case OPERATOR_LOGO: {
                byte[] reqOp = new byte[1000];
                byte[] head = Hex.bytes(0x00, 0x01, 0x01, 0x25, 0x01, 0x55, 0x00, 0x00, 0x55, 0x01, 0x0C, 0x08,
                        0x62, 0xF0, 0x10, 0x03, 0x55, 0x55);
                System.arraycopy(head, 0, reqOp, 0, head.length);
                if (!"000 00".equals(b.networkCode)) {
                    Sms.encodeNetworkCode(reqOp, 12, b.networkCode);
                    GsmBitmap.PhoneType t = GsmBitmap.PhoneType.NOKIA_6510_OPERATOR_LOGO;
                    int size = GsmBitmap.phoneSize(t);
                    if (size > 0xFF - 10) throw new GsmException(GsmError.INVALIDDATA);
                    reqOp[19] = (byte) (size + 10);
                    reqOp[22] = (byte) (size / 256);
                    reqOp[23] = (byte) (size % 256);
                    reqOp[24] = (byte) (size / 256);
                    reqOp[25] = (byte) (size % 256);
                    int len = 18 + (reqOp[19] & 0xFF);
                    reqOp[9] = 0x02;
                    reqOp[18] = 0x1A;
                    reqOp[20] = (byte) t.width;
                    reqOp[21] = (byte) t.height;
                    b.encode(t, reqOp, 26);
                    s.log("Setting operator logo");
                    GsmException.check(s.waitFor(reqOp, len, 0x0A, timeout, RequestId.SET_BITMAP));
                } else {
                    NetworkInfo n = getNetworkInfo();
                    Sms.encodeNetworkCode(reqOp, 12, n.networkCode);
                    s.log("Clearing operator logo");
                    GsmException.check(s.waitFor(reqOp, 18, 0x0A, timeout, RequestId.SET_BITMAP));
                }
                return;
            }
            default:
                throw new GsmException(GsmError.NOTSUPPORTED,
                        "on DCT-4 phones send pictures and caller logos as SMS instead");
        }
    }

    /** N6510_SetRingtone, note-tone preview (location 255). */
    @Override
    public void setRingtone(Ringtone r, int location) throws IOException {
        if (location != 255) {
            throw new GsmException(GsmError.NOTSUPPORTED,
                    "DCT-4 phones take RTTTL tones in preview mode only; the phone offers to save it");
        }
        s.log("Getting ringtone ID");
        byte[] getId = Hex.bytes(0x00, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0xFF, 0x06, 0xE1, 0x00, 0xFF, 0x06, 0xE1,
                0x01, 0x42);
        GsmException.check(s.waitFor(getId, 14, 0xDB, timeout, RequestId.SET_RINGTONE));
        Ringtone.Encoded e = r.encodeSmartMessaging(200);
        byte[] req = new byte[11 + e.data.length];
        byte[] head = Hex.bytes(0xAE, 0x01, 0x00, 0x0D, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00);
        System.arraycopy(head, 0, req, 0, 11);
        req[0] = (byte) ringtoneId;
        req[10] = (byte) e.data.length;
        System.arraycopy(e.data, 0, req, 11, e.data.length);
        s.log("Setting ringtone");
        s.protocol().writeMessage(req, req.length, 0x00);
        Hex.sleep(1000);
        getNetworkInfo();
    }
}
