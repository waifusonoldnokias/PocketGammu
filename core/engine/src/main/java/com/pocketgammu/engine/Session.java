package com.pocketgammu.engine;

import java.io.IOException;

/**
 * The Gammu state machine (libgammu/gsmstate.c): owns the device, the protocol
 * and the phone module; GSM_WaitFor, GSM_ReadDevice and GSM_DispatchMessage
 * live here.
 */
public final class Session {
    private final Device device;
    private final ConnectionType ct;
    private final LogSink logSink;
    private Protocol protocol;
    private PhoneModule phone;

    public boolean noPowerCable = false;
    public int replyNum = 3;
    private volatile boolean abort;

    // GSM_Phone_Data
    public String model = "", version = "", verDate = "", imei = "";
    public double verNum;
    public Models.Info modelInfo;

    private RequestId requestId = RequestId.NONE;
    private GsmError dispatchError = GsmError.TIMEOUT;
    private final byte[] readBuf = new byte[4096];

    public Session(Device device, ConnectionType ct, LogSink log) {
        this.device = device;
        this.ct = ct;
        this.logSink = log == null ? LogSink.NONE : log;
    }

    public Device device() {
        return device;
    }

    public Protocol protocol() {
        return protocol;
    }

    public PhoneModule phone() {
        return phone;
    }

    public ConnectionType connectionType() {
        return ct;
    }

    public void log(String line) {
        logSink.log(line);
    }

    boolean requestIdIs(RequestId id) {
        return requestId == id;
    }

    public void abort() {
        abort = true;
    }

    void checkAbort() throws GsmException {
        if (abort) {
            abort = false;
            throw new GsmException(GsmError.ABORTED);
        }
    }

    void dumpSent(byte[] msg, int len, int type) {
        log(String.format("SENDING frametype 0x%02X/length 0x%02X/%d", type & 0xFF, len, len));
        log("  " + Hex.of(msg, 0, len));
    }

    /**
     * GSM_InitConnection for Nokia phones: start the protocol, ask the phone
     * which model it is, then switch to the matching module.
     *
     * @param forcedModule "N6110", "N7110", "N6510" or null for automatic.
     */
    public void connect(String forcedModule) throws IOException {
        protocol = ct.isPhonet() ? new Phonet(this, ct) : new Fbus2(this, ct);
        log("[Connection - \"" + ct.gammuName + "\"]");
        log("[Device     - \"" + device.describe() + "\"]");
        protocol.initialise();
        phone = new PhoneModule(this) {
            // NAUTO: only knows how to ask for the model.
            @Override public String name() { return "NAUTO"; }
            @Override public NetworkInfo getNetworkInfo() throws GsmException { throw new GsmException(GsmError.NOTSUPPORTED); }
            @Override public Smsc getSmsc(int l) throws GsmException { throw new GsmException(GsmError.NOTSUPPORTED); }
            @Override public void setBitmap(GsmBitmap b) throws GsmException { throw new GsmException(GsmError.NOTSUPPORTED); }
            @Override public void setRingtone(Ringtone r, int l) throws GsmException { throw new GsmException(GsmError.NOTSUPPORTED); }
            @Override protected void writeSms(Sms sms) throws GsmException { throw new GsmException(GsmError.NOTSUPPORTED); }
            @Override public boolean supports(GsmBitmap.Type t) { return false; }
        };
        NokiaCommon.getModel(this);
        String module = forcedModule;
        if (module == null) {
            if (modelInfo == null) {
                throw new GsmException(GsmError.UNKNOWNMODELSTRING,
                        "\"" + model + "\" is not in Gammu's Nokia table; pick the phone family by hand");
            }
            module = modelInfo.module;
        }
        log("[Model type - \"" + (modelInfo == null ? model : modelInfo.model) + "\", module " + module + "]");
        switch (module) {
            case "N6110": phone = new N6110(this); break;
            case "N7110": phone = new N7110(this); break;
            case "N6510": phone = new N6510(this); break;
            default: throw new GsmException(GsmError.UNKNOWNMODELSTRING, module);
        }
        phone.initialise();
    }

    public void close() {
        if (protocol != null) protocol.terminate();
        device.close();
    }

    /** GSM_ReadDevice: one read, every byte through the protocol state machine. */
    public int readDevice() throws IOException {
        int n = device.read(readBuf, 0, readBuf.length, 50);
        for (int i = 0; i < n; i++) protocol.stateMachine(readBuf[i]);
        return n;
    }

    public GsmError waitFor(byte[] req, int type, int timeoutSec, RequestId id) throws IOException {
        return waitFor(req, req.length, type, timeoutSec, id);
    }

    /** GSM_WaitFor: send, wait for the reply that completes the request, retry. */
    public GsmError waitFor(byte[] req, int length, int type, int timeoutSec, RequestId id) throws IOException {
        try {
            for (int reply = 0; reply < replyNum; reply++) {
                if (reply != 0) log(String.format("[Retrying %d type 0x%02X]", reply, type));
                requestId = id;
                dispatchError = GsmError.TIMEOUT;
                protocol.writeMessage(req, length, type);
                if (id == RequestId.NONE) return GsmError.NONE;
                long start = System.currentTimeMillis();
                do {
                    if (readDevice() <= 0) Hex.sleep(10);
                    checkAbort();
                    if (requestId == RequestId.NONE) return dispatchError;
                } while (System.currentTimeMillis() - start < timeoutSec * 1000L);
            }
            return GsmError.TIMEOUT;
        } finally {
            requestId = RequestId.NONE;
        }
    }

    /** GSM_DispatchMessage + CheckReplyFunctions. */
    void dispatch(ProtocolMessage msg) {
        log(String.format("RECEIVED frametype 0x%02X/length 0x%02X/%d", msg.type, msg.length, msg.length));
        log("  " + Hex.of(msg.buffer, 0, msg.length));
        Reply found = null;
        boolean available = false;
        for (Reply r : phone.replies()) {
            if (!r.matches(msg)) continue;
            if (r.requestId == RequestId.INCOMING_FRAME || r.requestId == requestId || requestId == RequestId.EACH_FRAME) {
                found = r;
                break;
            }
            available = true;
        }
        if (found == null) {
            log(available ? "[Frame not requested right now]" : "[UNKNOWN frame]");
            return;
        }
        GsmError err = found.handler.handle(msg, this);
        if (found.requestId == requestId) {
            dispatchError = err;
            requestId = RequestId.NONE;
        }
        if (err == GsmError.UNKNOWNRESPONSE) log("[UNKNOWN response]");
    }
}
