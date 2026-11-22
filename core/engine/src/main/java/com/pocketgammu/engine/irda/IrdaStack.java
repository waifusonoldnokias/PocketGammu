package com.pocketgammu.engine.irda;

import com.pocketgammu.engine.Device;
import com.pocketgammu.engine.GsmError;
import com.pocketgammu.engine.GsmException;
import com.pocketgammu.engine.Hex;
import com.pocketgammu.engine.LogSink;
import com.pocketgammu.engine.Models;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * IrLMP (with an IAS client and a minimal IAS server) and Tiny TP on top of
 * {@link IrLap}, presented as a {@link Device}. Opening it does what Gammu's
 * irda_open() did with an AF_IRDA SOCK_STREAM socket: discover the phone,
 * look up "Nokia:PhoNet" in its IAS and open a TinyTP connection to it.
 */
public final class IrdaStack implements Device {
    private static final int IAS_LSAP = 0x00;
    private static final int OUR_IAS_CLIENT = 0x41;
    private static final int OUR_DATA_LSAP = 0x42;
    private static final int INITIAL_CREDIT = 7;

    private final IrPort port;
    private final LogSink log;
    private final IrLap lap;
    private final ArrayDeque<byte[]> outQ = new ArrayDeque<>();
    private final ByteArrayOutputStream rxStream = new ByteArrayOutputStream();
    private String peerName = "";

    // LMP / TTP state for the data connection
    private int dataPeerLsap = -1;
    private boolean useTtp = true;
    private boolean dataConnected;
    private int sendCredit, remoteCredit, availCredit;

    // pending request results
    private byte[] lmConfirm;
    private int lmConfirmFrom = -1;
    private byte[] iasReply;
    private boolean lmRefused;

    private volatile boolean closed;
    private long lastActivity;
    private Thread keepAlive;

    public IrdaStack(IrPort port, LogSink log) {
        this.port = port;
        this.log = log == null ? LogSink.NONE : log;
        this.lap = new IrLap(port, this.log);
    }

    public IrLap lap() {
        return lap;
    }

    public String peerName() {
        return peerName;
    }

    // ---- LMP dispatch --------------------------------------------------------------

    private void handle(byte[] f) {
        if (f.length < 2) return;
        boolean ctrl = (f[0] & 0x80) != 0;
        int dl = f[0] & 0x7F, sl = f[1] & 0x7F;
        if (ctrl) {
            int op = f.length > 2 ? f[2] & 0xFF : 0;
            if (op == 0x81) { // connect confirm
                lmConfirm = f;
                lmConfirmFrom = sl;
            } else if (op == 0x01) { // connect request from the phone
                if (dl == IAS_LSAP) {
                    outQ.add(new byte[] {(byte) (0x80 | sl), (byte) dl, (byte) 0x81, 0x00});
                } else {
                    outQ.add(new byte[] {(byte) (0x80 | sl), (byte) dl, 0x02, 0x08});
                }
            } else if (op == 0x02) { // disconnect
                if (dl == OUR_DATA_LSAP && dataConnected) {
                    log.log("Phone closed the PhoNet connection (reason " + (f.length > 3 ? f[3] & 0xFF : 0) + ")");
                    dataConnected = false;
                }
                if (dl == OUR_IAS_CLIENT || dl == OUR_DATA_LSAP) lmRefused = true;
            }
            return;
        }
        if (dl == OUR_IAS_CLIENT) {
            iasReply = f;
        } else if (dl == IAS_LSAP) {
            // The phone queries our IAS: we offer nothing.
            outQ.add(new byte[] {(byte) sl, (byte) dl, (byte) 0x84, 0x01});
        } else if (dl == OUR_DATA_LSAP && dataConnected) {
            int o = 2;
            if (useTtp) {
                sendCredit += f[2] & 0x7F;
                o = 3;
            }
            if (f.length > o) {
                rxStream.write(f, o, f.length - o);
                if (useTtp) {
                    remoteCredit--;
                    availCredit++;
                }
            }
            if (useTtp && remoteCredit <= 2 && availCredit > 0) {
                int give = Math.min(availCredit, 127);
                availCredit -= give;
                remoteCredit += give;
                outQ.add(new byte[] {(byte) dataPeerLsap, (byte) OUR_DATA_LSAP, (byte) give});
            }
        }
    }

    /** Sends the next queued frame (or polls) and dispatches whatever comes back. */
    private synchronized void step() throws IOException {
        List<byte[]> got = new ArrayList<>();
        byte[] next = outQ.peek();
        lap.exchange(next, got);
        if (next != null) outQ.poll();
        lastActivity = System.currentTimeMillis();
        for (byte[] f : got) handle(f);
    }

    private interface Cond {
        boolean met();
    }

    private void until(Cond c, int timeoutMs, String what) throws IOException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!c.met()) {
            if (System.currentTimeMillis() > end) throw new GsmException(GsmError.TIMEOUT, what);
            step();
        }
    }

    // ---- IrLMP connect / IAS ------------------------------------------------------

    private byte[] lmConnect(final int dlsap, int slsap, byte[] userData) throws IOException {
        lmConfirm = null;
        lmRefused = false;
        byte[] f = new byte[4 + userData.length];
        f[0] = (byte) (0x80 | dlsap);
        f[1] = (byte) slsap;
        f[2] = 0x01;
        f[3] = 0x00;
        System.arraycopy(userData, 0, f, 4, userData.length);
        outQ.add(f);
        until(new Cond() {
            @Override
            public boolean met() {
                return (lmConfirm != null && lmConfirmFrom == dlsap) || lmRefused;
            }
        }, 5000, "IrLMP connect");
        if (lmConfirm == null) throw new GsmException(GsmError.IRDA_SERVICE, "LSAP connection refused");
        return lmConfirm;
    }

    private void lmDisconnect(int dlsap, int slsap) {
        outQ.add(new byte[] {(byte) (0x80 | dlsap), (byte) slsap, 0x02, 0x01});
    }

    /** IAS GetValueByClass for an integer attribute; returns -1 if absent. */
    public int iasQueryInt(String cls, String attr) throws IOException {
        lmConnect(IAS_LSAP, OUR_IAS_CLIENT, new byte[0]);
        byte[] c = Hex.latin1(cls), a = Hex.latin1(attr);
        byte[] q = new byte[3 + 1 + c.length + 1 + a.length];
        q[0] = IAS_LSAP;
        q[1] = OUR_IAS_CLIENT;
        q[2] = (byte) 0x84; // Lst=1, GetValueByClass
        q[3] = (byte) c.length;
        System.arraycopy(c, 0, q, 4, c.length);
        q[4 + c.length] = (byte) a.length;
        System.arraycopy(a, 0, q, 5 + c.length, a.length);
        iasReply = null;
        outQ.add(q);
        until(new Cond() {
            @Override
            public boolean met() {
                return iasReply != null || lmRefused;
            }
        }, 5000, "IAS query");
        byte[] r = iasReply;
        lmDisconnect(IAS_LSAP, OUR_IAS_CLIENT);
        until(new Cond() {
            @Override
            public boolean met() {
                return outQ.isEmpty();
            }
        }, 3000, "IrLMP disconnect");
        if (r == null || r.length < 4) return -1;
        // r: dl, sl, ctrl, return code, list len(2), object id(2), type, value(4)
        if ((r[3] & 0xFF) != 0x00 || r.length < 13) return -1;
        if ((r[8] & 0xFF) != 0x01) return -1;
        return ((r[9] & 0xFF) << 24) | ((r[10] & 0xFF) << 16) | ((r[11] & 0xFF) << 8) | (r[12] & 0xFF);
    }

    // ---- open: what irda_open() did ------------------------------------------------

    public void open(String wantedService) throws IOException {
        IrLap.Discovered target = null;
        List<IrLap.Discovered> all = new ArrayList<>();
        long end = System.currentTimeMillis() + 8000;
        while (target == null && System.currentTimeMillis() < end) {
            List<IrLap.Discovered> found = lap.discover("PocketGammu");
            for (IrLap.Discovered d : found) {
                log.log("IrDA: found device " + d);
                boolean known = false;
                for (IrLap.Discovered x : all) if (x.address == d.address) known = true;
                if (!known) all.add(d);
                if (target == null && Models.byIrdaName(d.nickname) != null) target = d;
            }
            if (target == null && !all.isEmpty()) target = all.get(0);
        }
        if (target == null) {
            log.log("IrDA: no phone answered; " + lap.hearingSummary());
            throw new GsmException(GsmError.IRDA_NODEVICE);
        }
        peerName = target.nickname;
        lap.connect(target);
        int lsap = iasQueryInt(wantedService, "IrDA:TinyTP:LsapSel");
        useTtp = true;
        if (lsap < 0) {
            lsap = iasQueryInt(wantedService, "IrDA:IrLMP:LsapSel");
            useTtp = false;
        }
        if (lsap < 0) throw new GsmException(GsmError.IRDA_SERVICE, wantedService);
        log.log("IrDA: " + wantedService + " is at LSAP " + lsap + (useTtp ? " (TinyTP)" : " (IrLMP)"));
        dataPeerLsap = lsap;
        byte[] conf = lmConnect(lsap, OUR_DATA_LSAP,
                useTtp ? new byte[] {(byte) INITIAL_CREDIT} : new byte[0]);
        if (useTtp) {
            sendCredit = conf.length > 4 ? conf[4] & 0x7F : 1;
            remoteCredit = INITIAL_CREDIT;
            availCredit = 0;
        }
        dataConnected = true;
        startKeepAlive();
    }

    private void startKeepAlive() {
        keepAlive = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!closed) {
                    Hex.sleep(200);
                    if (closed) break;
                    if (System.currentTimeMillis() - lastActivity < 200) continue;
                    try {
                        synchronized (IrdaStack.this) {
                            if (!closed && lap.isConnected()) step();
                        }
                    } catch (IOException e) {
                        log.log("IrDA keep-alive: " + e.getMessage());
                        break;
                    }
                }
            }
        }, "irda-keepalive");
        keepAlive.setDaemon(true);
        keepAlive.start();
    }

    // ---- Device --------------------------------------------------------------------

    @Override
    public synchronized int read(byte[] buf, int off, int len, int timeoutMs) throws IOException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (rxStream.size() == 0) {
            if (!dataConnected) throw new GsmException(GsmError.IRDA_LINK);
            step();
            if (System.currentTimeMillis() >= end) break;
        }
        byte[] all = rxStream.toByteArray();
        int n = Math.min(len, all.length);
        System.arraycopy(all, 0, buf, off, n);
        rxStream.reset();
        rxStream.write(all, n, all.length - n);
        return n;
    }

    @Override
    public synchronized void write(byte[] buf, int off, int len) throws IOException {
        if (!dataConnected) throw new GsmException(GsmError.IRDA_LINK);
        int hdr = useTtp ? 3 : 2;
        int max = Math.max(16, lap.peerDataSize() - hdr);
        int pos = 0;
        while (pos < len) {
            if (useTtp && sendCredit == 0) {
                until(new Cond() {
                    @Override
                    public boolean met() {
                        return sendCredit > 0 || !dataConnected;
                    }
                }, 10000, "TinyTP credit");
            }
            int n = Math.min(max, len - pos);
            byte[] f = new byte[hdr + n];
            f[0] = (byte) dataPeerLsap;
            f[1] = (byte) OUR_DATA_LSAP;
            if (useTtp) {
                int give = Math.min(availCredit, 127);
                availCredit -= give;
                remoteCredit += give;
                f[2] = (byte) give;
                sendCredit--;
            }
            System.arraycopy(buf, off + pos, f, hdr, n);
            outQ.add(f);
            pos += n;
            until(new Cond() {
                @Override
                public boolean met() {
                    return outQ.isEmpty();
                }
            }, 10000, "IrDA send");
        }
    }

    @Override
    public void setSpeed(int baud) {}

    @Override
    public void setParity(boolean parity) {}

    @Override
    public void setDtrRts(boolean dtr, boolean rts) {}

    @Override
    public void close() {
        closed = true;
        synchronized (this) {
            try {
                if (dataConnected && lap.isConnected()) {
                    lmDisconnect(dataPeerLsap, OUR_DATA_LSAP);
                    step();
                }
            } catch (IOException ignored) {
                // link already down
            }
            lap.disconnect();
        }
        port.close();
    }

    @Override
    public String describe() {
        return port.describe() + (peerName.isEmpty() ? "" : " -> " + peerName);
    }
}
