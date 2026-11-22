package com.pocketgammu.engine.irda;

import com.pocketgammu.engine.GsmError;
import com.pocketgammu.engine.GsmException;
import com.pocketgammu.engine.Hex;
import com.pocketgammu.engine.LogSink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * IrLAP primary station (IrDA Link Access Protocol 1.1) over a SIR dongle:
 * XID discovery, SNRM/UA connection with QoS negotiation, and a stop-and-wait
 * data phase (window size 1) with RR polling. This replaces the Linux kernel
 * IrDA stack that Gammu's "irdaphonet" connection used through AF_IRDA sockets.
 */
public final class IrLap {
    public static final class Discovered {
        public final int address;
        public final String nickname;
        public final int hints;

        Discovered(int address, String nickname, int hints) {
            this.address = address;
            this.nickname = nickname;
            this.hints = hints;
        }

        @Override
        public String toString() {
            return "\"" + nickname + "\" (" + String.format("%08x", address) + ")";
        }
    }

    static final int CMD_BROADCAST = 0xFF, RSP_BROADCAST = 0xFE;
    static final int PF = 0x10;
    static final int XID_CMD = 0x2F, XID_RSP = 0xAF, SNRM_CMD = 0x83, UA_RSP = 0x63, DISC_CMD = 0x43,
            DM_RSP = 0x0F, RD_RSP = 0x43, FRMR_RSP = 0x87;
    static final int RR = 0x01, RNR = 0x05, REJ = 0x09, SREJ = 0x0D;

    private static final int[] BAUD = {2400, 9600, 19200, 38400, 57600, 115200, 576000, 1152000};
    private static final int[] ADD_BOFS = {48, 24, 12, 5, 3, 2, 1, 0};
    private static final int[] MTT_US = {10000, 5000, 1000, 500, 100, 50, 10, 0};
    private static final int[] TURN_MS = {500, 250, 100, 50};
    private static final int[] DATA_SIZE = {64, 128, 256, 512, 1024, 2048};
    private static final int[] DISC_S = {3, 8, 12, 16, 20, 25, 30, 40};

    private final IrPort port;
    private final LogSink log;
    private final int saddr;
    private int daddr;
    private int caddr; // 7-bit connection address << 1
    private int xbofs = 10;
    private int speed = 9600;
    private int vs, vr;
    private boolean remoteBusy;
    private boolean connected;
    private long lastRx;
    private int peerMttUs = 10000;
    private int peerDataSize = 64;
    private int linkTimeoutMs = 3000;
    private final Sir.Unwrapper unwrap = new Sir.Unwrapper();
    private final List<byte[]> rxFrames = new ArrayList<>();
    private final byte[] rbuf = new byte[4096];
    public boolean verbose = false;

    // PocketGammu: what the dongle heard, to tell a deaf dongle from a silent phone.
    private long heardBytes;
    private int heardFrames, heardEchoes;

    /** One-line summary of everything received since this link was created. */
    public String hearingSummary() {
        if (heardBytes == 0) return "the dongle received nothing at all";
        return "the dongle received " + heardBytes + " bytes: " + heardFrames + " frames (" + heardEchoes
                + " echoes of our own), " + unwrap.badFrames + " damaged";
    }

    public IrLap(IrPort port, LogSink log) {
        this.port = port;
        this.log = log == null ? LogSink.NONE : log;
        Random r = new Random();
        int a;
        do {
            a = r.nextInt();
        } while (a == 0 || a == -1);
        saddr = a;
    }

    public int peerDataSize() {
        return peerDataSize;
    }

    public boolean isConnected() {
        return connected;
    }

    public int speed() {
        return speed;
    }

    // ---- frame I/O -----------------------------------------------------------------

    private void send(byte[] frame) throws IOException {
        long wait = lastRx + peerMttUs * 1000L - System.nanoTime();
        if (wait > 0) Hex.sleep(Math.max(1, wait / 1_000_000L));
        if (verbose) log.log("IrLAP > " + Hex.of(frame));
        byte[] w = Sir.wrap(frame, frame.length, xbofs);
        port.write(w, 0, w.length);
    }

    /** Reads frames addressed to us (responses only; our own echoed commands are dropped). */
    private void pump(int timeoutMs, int addrWanted) throws IOException {
        int n = port.read(rbuf, 0, rbuf.length, timeoutMs);
        if (n <= 0) return;
        heardBytes += n;
        for (byte[] f : unwrap.feed(rbuf, 0, n)) {
            heardFrames++;
            if (f.length < 2) continue;
            int a = f[0] & 0xFF;
            if ((a & 1) != 0) { heardEchoes++; continue; } // command frame: our echo, or another primary
            if (addrWanted >= 0 && (a & 0xFE) != addrWanted) continue;
            lastRx = System.nanoTime();
            if (verbose) log.log("IrLAP < " + Hex.of(f));
            rxFrames.add(f);
        }
    }

    private static void le32(ByteArrayOutputStream o, int v) {
        o.write(v);
        o.write(v >> 8);
        o.write(v >> 16);
        o.write(v >> 24);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | (b[o + 3] & 0xFF) << 24;
    }

    // ---- discovery -----------------------------------------------------------------

    private byte[] xid(int slot, int slotsCode, String nickname) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(CMD_BROADCAST);
        o.write(XID_CMD | PF);
        o.write(0x01); // XID format identifier
        le32(o, saddr);
        le32(o, 0xFFFFFFFF);
        o.write(slotsCode);
        o.write(slot);
        o.write(0x00); // IrLAP version
        if (slot == 0xFF) {
            o.write(0x04); // hint: computer
            o.write(0x00); // charset ASCII
            byte[] n = Hex.latin1(nickname);
            o.write(n, 0, n.length);
        }
        return o.toByteArray();
    }

    /** One discovery round with 6 slots, as the Linux stack does by default. */
    public List<Discovered> discover(String nickname) throws IOException {
        port.setSpeed(9600);
        speed = 9600;
        xbofs = 10;
        connected = false;
        rxFrames.clear();
        List<Discovered> found = new ArrayList<>();
        final int slots = 6, code = 0x01, slotMs = 90;
        for (int s = 0; s <= slots; s++) {
            int slot = s == slots ? 0xFF : s;
            send(xid(slot, code, nickname));
            long end = System.currentTimeMillis() + (slot == 0xFF ? 20 : slotMs);
            while (System.currentTimeMillis() < end) pump(10, RSP_BROADCAST);
        }
        for (byte[] f : rxFrames) {
            if (f.length < 14 || (f[1] & 0xEF & 0xFF) != XID_RSP || f[2] != 0x01) continue;
            int src = le32(f, 3), dst = le32(f, 7);
            if (dst != saddr) continue;
            int i = 14, hints = 0, shift = 0;
            while (i < f.length) {
                int h = f[i++] & 0xFF;
                hints |= (h & 0x7F) << shift;
                shift += 8;
                if ((h & 0x80) == 0) break;
            }
            if (i < f.length) i++; // charset
            StringBuilder nick = new StringBuilder();
            for (; i < f.length; i++) {
                int c = f[i] & 0xFF;
                if (c == 0) break;
                nick.append((char) c);
            }
            boolean dup = false;
            for (Discovered d : found) if (d.address == src) dup = true;
            if (!dup) found.add(new Discovered(src, nick.toString(), hints));
        }
        rxFrames.clear();
        return found;
    }

    // ---- connection ----------------------------------------------------------------

    private static int msb(int bits) {
        int i = -1;
        for (int b = 0; b < 8; b++) if ((bits & (1 << b)) != 0) i = b;
        return i;
    }

    private static int speedBits(int max) {
        int bits = 0;
        for (int i = 0; i < 6; i++) if (BAUD[i] <= max) bits |= 1 << i;
        return bits;
    }

    public void connect(Discovered d) throws IOException {
        daddr = d.address;
        caddr = (1 + new Random().nextInt(0x7D)) << 1;
        int ourBaud = speedBits(Math.min(115200, port.maxSpeed()));
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(CMD_BROADCAST);
        o.write(SNRM_CMD | PF);
        le32(o, saddr);
        le32(o, daddr);
        o.write(caddr);
        int[][] params = {
            {0x01, ourBaud},   // baud rates we accept
            {0x82, 0x01},      // max turn around time 500 ms
            {0x83, 0x0F},      // data size: up to 512 bytes
            {0x84, 0x01},      // window size 1
            {0x85, 0x80},      // we need no additional BOFs
            {0x86, 0x01},      // we need 10 ms minimum turn around (USB latency)
            {0x08, 0x07},      // link disconnect threshold 3/8/12 s
        };
        for (int[] p : params) {
            o.write(p[0]);
            o.write(1);
            o.write(p[1]);
        }
        byte[] snrm = o.toByteArray();
        byte[] ua = null;
        for (int attempt = 0; attempt < 4 && ua == null; attempt++) {
            rxFrames.clear();
            send(snrm);
            long end = System.currentTimeMillis() + 600;
            while (ua == null && System.currentTimeMillis() < end) {
                pump(20, caddr);
                for (byte[] f : rxFrames) {
                    int c = f[1] & 0xFF;
                    if ((c & ~PF) == UA_RSP) ua = f;
                    else if ((c & ~PF) == DM_RSP) throw new GsmException(GsmError.IRDA_LINK, "phone refused the connection");
                }
                rxFrames.clear();
            }
        }
        if (ua == null) throw new GsmException(GsmError.TIMEOUT, "no UA answer to IrLAP SNRM");
        int baudBits = 0x02, addBofs = 0x80, mtt = 0x01, dataSize = 0x01, disc = 0x01;
        for (int i = 10; i + 1 < ua.length; ) {
            int pi = ua[i] & 0xFF, pl = ua[i + 1] & 0xFF;
            if (i + 2 + pl > ua.length) break;
            int pv = pl > 0 ? ua[i + 2] & 0xFF : 0;
            switch (pi) {
                case 0x01: baudBits = pv; break;
                case 0x85: addBofs = pv; break;
                case 0x86: mtt = pv; break;
                case 0x83: dataSize = pv; break;
                case 0x08: disc = pv; break;
                default: break;
            }
            i += 2 + pl;
        }
        int b = msb(baudBits & ourBaud);
        int newSpeed = b < 0 ? 9600 : BAUD[b];
        int bo = msb(addBofs);
        int addB = bo < 0 ? 0 : ADD_BOFS[bo];
        xbofs = Math.max(1, addB * newSpeed / 115200);
        int m = msb(mtt);
        peerMttUs = Math.max(1000, m < 0 ? 10000 : MTT_US[m]);
        int ds = msb(dataSize);
        peerDataSize = ds < 0 ? 64 : DATA_SIZE[Math.min(ds, DATA_SIZE.length - 1)];
        int dt = msb(disc & 0x07);
        linkTimeoutMs = (dt < 0 ? 3 : DISC_S[dt]) * 1000;
        log.log(String.format("IrLAP connected to %08x at %d bps (peer data size %d, MTT %d us, link timeout %d s)",
                daddr, newSpeed, peerDataSize, peerMttUs, linkTimeoutMs / 1000));
        Hex.sleep(peerMttUs / 1000 + 1);
        port.setSpeed(newSpeed);
        speed = newSpeed;
        vs = vr = 0;
        remoteBusy = false;
        connected = true;
        rxFrames.clear();
        // First poll at the new speed.
        exchange(null, new ArrayList<byte[]>());
    }

    /**
     * Sends one I-frame (or an RR poll when info is null) with the P bit and
     * waits for the secondary's final frame; received information fields are
     * appended to delivered. Retransmits until acknowledged.
     */
    public synchronized void exchange(byte[] info, List<byte[]> delivered) throws IOException {
        if (!connected) throw new GsmException(GsmError.IRDA_LINK);
        long deadline = System.currentTimeMillis() + linkTimeoutMs;
        int sentNs = vs;
        while (true) {
            boolean sendI = info != null && !remoteBusy;
            byte[] frame;
            if (sendI) {
                frame = new byte[2 + info.length];
                frame[0] = (byte) (caddr | 1);
                frame[1] = (byte) ((vr << 5) | PF | (sentNs << 1));
                System.arraycopy(info, 0, frame, 2, info.length);
            } else {
                frame = new byte[] {(byte) (caddr | 1), (byte) ((vr << 5) | PF | RR)};
            }
            rxFrames.clear();
            send(frame);
            boolean gotFinal = false;
            boolean acked = false;
            long fEnd = System.currentTimeMillis() + 500;
            while (!gotFinal && System.currentTimeMillis() < fEnd) {
                pump(20, caddr);
                for (byte[] f : rxFrames) {
                    int c = f[1] & 0xFF;
                    if ((c & 1) == 0) {
                        int ns = (c >> 1) & 7, nr = (c >> 5) & 7;
                        if (sendI && nr == ((sentNs + 1) & 7)) acked = true;
                        if (ns == vr) {
                            vr = (vr + 1) & 7;
                            byte[] data = new byte[f.length - 2];
                            System.arraycopy(f, 2, data, 0, data.length);
                            delivered.add(data);
                        }
                    } else if ((c & 3) == 1) {
                        int nr = (c >> 5) & 7, type = c & 0x0F;
                        if (sendI && nr == ((sentNs + 1) & 7)) acked = true;
                        remoteBusy = type == RNR;
                    } else {
                        int u = c & ~PF;
                        if (u == RD_RSP || u == DM_RSP || u == FRMR_RSP) {
                            connected = false;
                            throw new GsmException(GsmError.IRDA_LINK, "phone closed the IrDA link");
                        }
                    }
                    if ((c & PF) != 0) gotFinal = true;
                }
                rxFrames.clear();
            }
            if (gotFinal) deadline = System.currentTimeMillis() + linkTimeoutMs;
            if (info == null && gotFinal) return;
            if (acked) {
                vs = (sentNs + 1) & 7;
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                connected = false;
                throw new GsmException(GsmError.IRDA_LINK, "no answer from the phone for " + linkTimeoutMs / 1000 + " s");
            }
            if (remoteBusy) Hex.sleep(50);
        }
    }

    public void disconnect() {
        if (connected) {
            try {
                rxFrames.clear();
                send(new byte[] {(byte) (caddr | 1), (byte) (DISC_CMD | PF)});
                long end = System.currentTimeMillis() + 300;
                while (System.currentTimeMillis() < end) pump(20, caddr);
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        connected = false;
        try {
            port.setSpeed(9600);
        } catch (IOException ignored) {
            // dongle may already be gone
        }
    }
}
