package com.pocketgammu.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** A Gammu phone module (GSM_Phone_Functions), reduced to what this app uses. */
public abstract class PhoneModule {
    protected final Session s;
    protected final List<Reply> replies = new ArrayList<>();

    /** Fields the reply handlers fill in (Gammu keeps these in s->Phone.Data). */
    protected NetworkInfo netInfo;
    protected Smsc smsc;
    protected int smsStatus = -1; // -1 = waiting, 0 = sent, >0 = error code
    protected int smsReference = -1;

    protected PhoneModule(Session s) {
        this.s = s;
        add(0xD2, 0x02, 0x00, RequestId.GET_MODEL, NokiaCommon.MODEL_FIRMWARE);
        add(0xD2, 0x02, 0x00, RequestId.GET_FIRMWARE, NokiaCommon.MODEL_FIRMWARE);
    }

    protected final void add(int type, int subChar, int sub, RequestId id, Reply.Handler h) {
        replies.add(new Reply(type, subChar, sub, id, h));
    }

    public final List<Reply> replies() {
        return replies;
    }

    public abstract String name();

    public void initialise() throws IOException {}

    public abstract NetworkInfo getNetworkInfo() throws IOException;

    public abstract Smsc getSmsc(int location) throws IOException;

    public abstract void setBitmap(GsmBitmap b) throws IOException;

    /** location 255 = Gammu's default "send as preview/incoming tone" mode. */
    public abstract void setRingtone(Ringtone r, int location) throws IOException;

    /** Writes the submit frame for one SMS; the phone answers asynchronously. */
    protected abstract void writeSms(Sms sms) throws IOException;

    public String getImei() throws IOException {
        throw new GsmException(GsmError.NOTSUPPORTED);
    }

    /** Which logo kinds this module can write straight into the phone. */
    public abstract boolean supports(GsmBitmap.Type t);

    /**
     * What gammu's "sendsms" does: read the SMSC from location 1 once (unless
     * smscNumber is given), then send every part and wait for its status.
     */
    public void sendSmsParts(java.util.List<Sms> parts, String smscNumber, int timeoutSec, Runnable afterEach)
            throws IOException {
        String number = smscNumber == null ? "" : smscNumber.trim();
        if (number.isEmpty()) {
            try {
                number = getSmsc(1).number;
                s.log("Using SMSC " + number + " from the phone");
            } catch (GsmException e) {
                throw new GsmException(GsmError.GETTING_SMSC, e.getMessage());
            }
        }
        for (Sms sms : parts) {
            sms.smsc.number = number;
            sendSms(sms, timeoutSec);
            if (afterEach != null) afterEach.run();
        }
    }

    /** GSM_SendSMS plus the wait for the phone's network status report. */
    public void sendSms(Sms sms, int timeoutSec) throws IOException {
        if (sms.smsc.number.isEmpty()) throw new GsmException(GsmError.EMPTYSMSC);
        smsStatus = -1;
        writeSms(sms);
        long end = System.currentTimeMillis() + timeoutSec * 1000L;
        while (smsStatus == -1 && System.currentTimeMillis() < end) {
            if (s.readDevice() <= 0) Hex.sleep(10);
            s.checkAbort();
        }
        if (smsStatus == -1) throw new GsmException(GsmError.TIMEOUT, "no SMS status from phone");
        if (smsStatus != 0) throw new GsmException(GsmError.SMS_FAILED, "error " + smsStatus);
    }
}
