package com.pocketgammu.engine;

import java.io.IOException;

/**
 * Port of the logo, ringtone and SMS parts of libgammu/phone/nokia/dct3/n6110.c
 * (Nokia 3210, 3310, 3330, 3410, 5110, 5510, 6110, 6150, 8210, 8850 ...).
 */
public final class N6110 extends PhoneModule {
    public static final String MODELS =
            "2100|3210|3310|3330|3390|3390b|3410|3610|5110|5110i|5130|5190|5210|5510|6110|6130|6150|6190|8210|8250|8290|8850|8855|8890";

    public N6110(final Session s) {
        super(s);
        NokiaCommon.addDct3Replies(this);
        add(0x05, 0x03, 0x11, RequestId.SET_PROFILE, Reply.NONE);
        add(0x05, 0x03, 0x12, RequestId.SET_PROFILE, err(GsmError.NOTSUPPORTED));
        add(0x05, 0x03, 0x14, RequestId.GET_LANGUAGE, Reply.NONE);
        add(0x05, 0x03, 0x15, RequestId.GET_LANGUAGE, Reply.NONE);
        add(0x05, 0x03, 0x19, RequestId.SET_BITMAP, Reply.NONE);              // startup logo / notes set
        add(0x05, 0x03, 0x31, RequestId.SET_BITMAP, Reply.NONE);              // operator logo set
        add(0x05, 0x03, 0x32, RequestId.SET_BITMAP, new Reply.Handler() {     // operator logo error
            @Override
            public GsmError handle(ProtocolMessage msg, Session s) {
                return msg.u(4) == 0x7D ? GsmError.INVALIDLOCATION : GsmError.UNKNOWNRESPONSE;
            }
        });
        add(0x05, 0x03, 0x37, RequestId.SET_RINGTONE, Reply.NONE);
        add(0x05, 0x03, 0x38, RequestId.SET_RINGTONE, new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session s) {
                return msg.u(4) == 0x7D ? GsmError.INVALIDLOCATION : GsmError.UNKNOWNRESPONSE;
            }
        });
        add(0x03, 0x03, 0x14, RequestId.SET_BITMAP, Reply.NONE);              // caller group set
        add(0x03, 0x03, 0x15, RequestId.SET_BITMAP, err(GsmError.INVALIDLOCATION));
        add(0x47, 0x03, 0x04, RequestId.SET_BITMAP, Reply.NONE);              // picture image set
        add(0x47, 0x03, 0x05, RequestId.SET_BITMAP, err(GsmError.INVALIDLOCATION));
        add(0x01, 0x03, 0x40, RequestId.INCOMING_FRAME, Reply.NONE);
    }

    static Reply.Handler err(final GsmError e) {
        return new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session s) {
                return e;
            }
        };
    }

    @Override
    public String name() {
        return "N6110 (DCT-3)";
    }

    private boolean has(String f) {
        return s.modelInfo != null && s.modelInfo.has(f);
    }

    @Override
    public void initialise() throws IOException {
        // N6110_GetPhoneLanguage; Gammu ignores its result.
        byte[] req = NokiaCommon.req(0x13, 0x01, 0x00, has("PROFILES33") ? 0x1E : 0x21);
        s.log("Getting profile feature");
        s.waitFor(req, 0x05, 4, RequestId.GET_LANGUAGE);
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
            case STARTUP_LOGO: return !has("NOSTARTUP");
            case CALLER_GROUP_LOGO: return !has("NOCALLER");
            case PICTURE_IMAGE: return !has("NOPICTURE");
            default: return true;
        }
    }

    private void setProfileFeature(int profile, int feature, int value) throws IOException {
        s.log("Setting profile feature");
        GsmException.check(s.waitFor(NokiaCommon.req(0x10, 0x01, profile, feature, value), 0x05, 4,
                RequestId.SET_PROFILE));
    }

    /** N6110_SetBitmap. */
    @Override
    public void setBitmap(GsmBitmap b) throws IOException {
        byte[] req = new byte[600];
        System.arraycopy(NokiaCommon.FRAME_HEADER, 0, req, 0, 3);
        int count = 3;
        switch (b.type) {
            case STARTUP_LOGO: {
                if (has("NOSTARTUP")) throw new GsmException(GsmError.NOTSUPPORTED);
                if (b.location != 1 && has("NOSTARTANI")) throw new GsmException(GsmError.NOTSUPPORTED);
                if (!has("NOSTARTANI")) {
                    try {
                        boolean is3210 = s.modelInfo != null && s.modelInfo.model.equals("3210");
                        setProfileFeature(0, is3210 ? 0x2E : 0x29, b.location - 1);
                    } catch (GsmException e) {
                        if (e.error == GsmError.NOTSUPPORTED) throw new GsmException(GsmError.SECURITYERROR);
                        throw e;
                    }
                    if (b.location != 1) return;
                }
                GsmBitmap.PhoneType t = GsmBitmap.PhoneType.NOKIA_STARTUP_LOGO;
                req[count++] = 0x18;
                req[count++] = 0x01;
                req[count++] = 0x01;
                req[count++] = (byte) t.height;
                req[count++] = (byte) t.width;
                b.encode(t, req, count);
                count += GsmBitmap.phoneSize(t);
                s.log("Setting startup logo");
                GsmException.check(s.waitFor(req, count, 0x05, 4, RequestId.SET_BITMAP));
                return;
            }
            case WELCOME_NOTE:
            case DEALER_NOTE: {
                req[count++] = 0x18;
                req[count++] = 0x01;
                req[count++] = (byte) (b.type == GsmBitmap.Type.WELCOME_NOTE ? 0x02 : 0x03);
                byte[] t = Hex.latin1(b.text);
                req[count++] = (byte) t.length;
                System.arraycopy(t, 0, req, count, t.length);
                count += t.length;
                GsmException.check(s.waitFor(req, count, 0x05, 4, RequestId.SET_BITMAP));
                return;
            }
            case CALLER_GROUP_LOGO: {
                if (has("NOCALLER")) throw new GsmException(GsmError.NOTSUPPORTED);
                req[count++] = 0x13;
                req[count++] = (byte) (b.location - 1);
                if (b.defaultName) {
                    req[count++] = 0;
                } else {
                    byte[] t = Hex.latin1(b.text);
                    req[count++] = (byte) t.length;
                    System.arraycopy(t, 0, req, count, t.length);
                    count += t.length;
                }
                req[count++] = (byte) (b.defaultRingtone ? 16 : b.ringtoneId);
                GsmBitmap logo = b;
                if (b.defaultBitmap) {
                    logo = new GsmBitmap(b.type, 72, 14);
                    req[count++] = 0;
                } else {
                    req[count++] = (byte) (b.bitmapEnabled ? 0x01 : 0x00);
                }
                int size = GsmBitmap.phoneSize(GsmBitmap.PhoneType.NOKIA_CALLER_LOGO) + 4;
                req[count++] = (byte) (size / 256);
                req[count++] = (byte) (size % 256);
                count = logo.copyNokia(GsmBitmap.PhoneType.NOKIA_CALLER_LOGO, req, count);
                GsmException.check(s.waitFor(req, count, 0x03, 4, RequestId.SET_BITMAP));
                return;
            }
            case OPERATOR_LOGO: {
                req[count++] = 0x30;
                req[count++] = 0x01;
                Sms.encodeNetworkCode(req, count, b.networkCode);
                count += 3;
                int size = GsmBitmap.phoneSize(GsmBitmap.PhoneType.NOKIA_OPERATOR_LOGO) + 4;
                req[count++] = (byte) (size / 256);
                req[count++] = (byte) (size % 256);
                count = b.copyNokia(GsmBitmap.PhoneType.NOKIA_OPERATOR_LOGO, req, count);
                GsmException.check(s.waitFor(req, count, 0x05, 4, RequestId.SET_BITMAP));
                return;
            }
            case PICTURE_IMAGE: {
                if (has("NOPICTURE")) throw new GsmException(GsmError.NOTSUPPORTED);
                req[count++] = 0x03;
                req[count++] = (byte) (b.location - 1);
                if (!b.sender.isEmpty()) {
                    int n = Sms.packSemiOctetNumber(b.sender, req, count + 1, true);
                    req[count] = (byte) n;
                    int textlen = n;
                    if (textlen % 2 != 0) textlen++;
                    count += textlen / 2 + 1;
                    count++;
                } else {
                    req[count++] = 0x00;
                    req[count++] = 0x00;
                }
                req[count++] = 0x00;
                boolean nhm5 = "NHM-5".equals(s.model);
                if (has("NOPICTUREUNI") || (nhm5 && s.verNum < 5.79)) {
                    byte[] t = Hex.latin1(b.text);
                    req[count++] = (byte) t.length;
                    System.arraycopy(t, 0, req, count, t.length);
                    count += t.length;
                } else {
                    byte[] t = Hex.ucs2(b.text);
                    if (nhm5) {
                        req[count++] = (byte) t.length;
                    } else {
                        req[count++] = (byte) (t.length + 2);
                        req[count++] = 0x00;
                        req[count++] = 0x1E;
                    }
                    System.arraycopy(t, 0, req, count, t.length);
                    count += t.length;
                }
                count = b.copyNokia(GsmBitmap.PhoneType.NOKIA_PICTURE_IMAGE, req, count);
                GsmException.check(s.waitFor(req, count, 0x47, 4, RequestId.SET_BITMAP));
                return;
            }
            default:
                throw new GsmException(GsmError.NOTSUPPORTED);
        }
    }

    /** N6110_SetRingtone (note-tone format). */
    @Override
    public void setRingtone(Ringtone r, int location) throws IOException {
        if (has("NORING")) throw new GsmException(GsmError.NOTSUPPORTED);
        if (location == 0) throw new GsmException(GsmError.INVALIDLOCATION);
        if (location == 255) {
            // Only 6110, 6130 and 6150 accept a ringtone "preview" frame.
            if (!"NSE-3".equals(s.model) && !"NSK-3".equals(s.model) && !"NSM-1".equals(s.model)) {
                throw new GsmException(GsmError.NOTSUPPORTED,
                        "this phone needs a ringtone location (composer slot), not preview mode");
            }
            byte[] udh = Sms.Udh.NOKIA_RINGTONE.text;
            Ringtone.Encoded e = r.encodeSmartMessaging(200);
            byte[] req = new byte[2 + 1 + udh.length + e.data.length];
            req[0] = 0x0C;
            req[1] = 0x01;
            req[2] = (byte) udh.length;
            System.arraycopy(udh, 0, req, 3, udh.length);
            System.arraycopy(e.data, 0, req, 3 + udh.length, e.data.length);
            s.protocol().writeMessage(req, req.length, 0x12);
            Hex.sleep(1000);
            getNetworkInfo();
            return;
        }
        Ringtone.Encoded e = r.encodeSmartMessaging(200);
        byte[] req = new byte[7 + e.data.length];
        System.arraycopy(NokiaCommon.FRAME_HEADER, 0, req, 0, 3);
        req[3] = 0x36;
        req[4] = (byte) (location - 1);
        req[5] = 0x00;
        req[6] = 0x78;
        System.arraycopy(e.data, 0, req, 7, e.data.length);
        s.log("Setting ringtone");
        GsmException.check(s.waitFor(req, req.length, 0x05, 4, RequestId.SET_RINGTONE));
    }
}
