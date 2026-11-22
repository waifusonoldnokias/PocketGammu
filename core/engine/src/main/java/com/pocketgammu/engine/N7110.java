package com.pocketgammu.engine;

import java.io.IOException;

/**
 * Port of the logo, ringtone and SMS parts of libgammu/phone/nokia/dct3/n7110.c
 * (Nokia 6210, 6250, 7110, 7190). SMS, SMSC and network info are the shared
 * DCT-3 functions, as in Gammu.
 */
public final class N7110 extends PhoneModule {
    public static final String MODELS = "6210|6250|7110|7190";

    public N7110(Session s) {
        super(s);
        NokiaCommon.addDct3Replies(this);
        add(0x0A, 0x03, 0x72, RequestId.GET_NETWORK_INFO, new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session s) {
                return GsmError.NOTSUPPORTED;
            }
        });
        add(0x0A, 0x03, 0xA4, RequestId.SET_BITMAP, Reply.NONE);
        add(0x0A, 0x03, 0xB0, RequestId.SET_BITMAP, Reply.NONE);
        add(0x7A, 0x04, 0x02, RequestId.SET_BITMAP, Reply.NONE);
        add(0x7A, 0x04, 0x15, RequestId.SET_BITMAP, Reply.NONE);
        add(0x7A, 0x04, 0x17, RequestId.SET_BITMAP, Reply.NONE);
    }

    @Override
    public String name() {
        return "N7110 (DCT-3)";
    }

    @Override
    public String getImei() throws IOException {
        return NokiaCommon.dct3Imei(s);
    }

    @Override
    public NetworkInfo getNetworkInfo() throws IOException {
        return NokiaCommon.dct3NetworkInfo(s, this);
    }

    @Override
    public Smsc getSmsc(int location) throws IOException {
        return NokiaCommon.dct3Smsc(s, this, location);
    }

    @Override
    protected void writeSms(Sms sms) throws IOException {
        NokiaCommon.dct3WriteSms(s, sms);
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

    private static byte[] noteRequest(int setting, String text) {
        byte[] t = Hex.ucs2(text);
        byte[] req = new byte[5 + t.length + 3];
        System.arraycopy(NokiaCommon.FRAME_HEADER_7110, 0, req, 0, 3);
        req[3] = (byte) 0xEC;
        req[4] = (byte) setting;
        System.arraycopy(t, 0, req, 5, t.length);
        // Gammu: i = 6 + len*2, then two zero bytes, so the frame is 8 + len*2 long.
        return req;
    }

    /** N7110_SetBitmap. */
    @Override
    public void setBitmap(GsmBitmap b) throws IOException {
        switch (b.type) {
            case STARTUP_LOGO: {
                if (b.location != 1) throw new GsmException(GsmError.NOTSUPPORTED);
                GsmBitmap.PhoneType t = "NSE-5".equals(s.model)
                        ? GsmBitmap.PhoneType.NOKIA_7110_STARTUP_LOGO : GsmBitmap.PhoneType.NOKIA_6210_STARTUP_LOGO;
                byte[] req = new byte[21 + GsmBitmap.phoneSize(t)];
                byte[] head = Hex.bytes(0x00, 0x01, 0x01, 0xEC, 0x15, 0x00, 0x00, 0x00, 0x04, 0xC0, 0x02, 0x00,
                        0x00, 0xC0, 0x03, 0x00, 0x00, 0xC0, 0x04, 0x03, 0x00);
                System.arraycopy(head, 0, req, 0, head.length);
                req[12] = (byte) t.height;
                req[16] = (byte) t.width;
                b.encode(t, req, 21);
                s.log("Setting startup logo");
                GsmException.check(s.waitFor(req, req.length, 0x7A, 4, RequestId.SET_BITMAP));
                return;
            }
            case WELCOME_NOTE:
            case DEALER_NOTE: {
                byte[] req = noteRequest(b.type == GsmBitmap.Type.WELCOME_NOTE ? 0x02 : 0x17, b.text);
                GsmException.check(s.waitFor(req, req.length, 0x7A, 4, RequestId.SET_BITMAP));
                return;
            }
            case OPERATOR_LOGO: {
                byte[] reqOp = new byte[1000];
                byte[] head = Hex.bytes(0x00, 0x01, 0x01, 0xA3, 0x01, 0x00, 0x00, 0xF0, 0x00, 0x00, 0x04, 0x08,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00);
                System.arraycopy(head, 0, reqOp, 0, head.length);
                if (!"000 00".equals(b.networkCode)) {
                    GsmBitmap.PhoneType t = GsmBitmap.PhoneType.NOKIA_7110_OPERATOR_LOGO;
                    reqOp[5] = 0x01;
                    Sms.encodeNetworkCode(reqOp, 6, b.networkCode);
                    int size = GsmBitmap.phoneSize(t);
                    reqOp[11] = (byte) (8 + size);
                    reqOp[12] = (byte) t.width;
                    reqOp[13] = (byte) t.height;
                    reqOp[15] = (byte) size;
                    b.encode(t, reqOp, 18);
                    s.log("Setting operator logo");
                    GsmException.check(s.waitFor(reqOp, 18 + size, 0x0A, 4, RequestId.SET_BITMAP));
                } else {
                    s.log("Clearing operator logo");
                    for (int i = 0; i < 5; i++) {
                        GsmException.check(s.waitFor(Hex.bytes(0x00, 0x01, 0x00, 0xAF, i), 0x0A, 4,
                                RequestId.SET_BITMAP));
                    }
                    GsmException.check(s.waitFor(reqOp, 18, 0x0A, 4, RequestId.SET_BITMAP));
                }
                return;
            }
            case PICTURE_IMAGE:
                throw new GsmException(GsmError.NOTSUPPORTED,
                        "storing picture images on this phone is not ported yet; send it as a picture message instead");
            default:
                throw new GsmException(GsmError.NOTSUPPORTED,
                        "caller group logos on this phone are not ported yet; send it as a caller logo SMS instead");
        }
    }

    /** N7110_SetRingtone, preview form (location 255). */
    @Override
    public void setRingtone(Ringtone r, int location) throws IOException {
        if (location != 255) {
            throw new GsmException(GsmError.NOTSUPPORTED,
                    "this phone stores tones in Nokia binary format; use preview mode (the phone offers to save it)");
        }
        if ("NSE-5".equals(s.model)) throw new GsmException(GsmError.NOTSUPPORTED, "the 7110 has no preview mode");
        Ringtone.Encoded e = r.encodeSmartMessaging(200);
        byte[] req = new byte[11 + e.data.length];
        byte[] head = Hex.bytes(0x7C, 0x01, 0x00, 0x0D, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00);
        System.arraycopy(head, 0, req, 0, 11);
        req[10] = (byte) e.data.length;
        System.arraycopy(e.data, 0, req, 11, e.data.length);
        s.protocol().writeMessage(req, req.length, 0x00);
        Hex.sleep(1000);
        getNetworkInfo();
    }
}
