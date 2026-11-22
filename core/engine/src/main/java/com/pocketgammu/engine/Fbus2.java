package com.pocketgammu.engine;

import java.io.IOException;

/**
 * Port of libgammu/protocol/nokia/fbus2.c (FBUS version 2), including the
 * DLR-3, DKU-5, PL2303 and IrDA-physical variants.
 */
public final class Fbus2 implements Protocol {
    static final int FRAME_ID = 0x1E;
    static final int IRDA_FRAME_ID = 0x1C;
    static final int DEVICE_PHONE = 0x00;
    static final int DEVICE_PC = 0x0C;
    static final int ACK_BYTE = 0x7F;
    static final int MAX_TRANSMIT_LENGTH = 120;

    private enum Rx { SYNC, DEST, SRC, TYPE, LEN1, LEN2, MSG }

    private final Session s;
    private final ConnectionType ct;

    private Rx state = Rx.SYNC;
    private int msgType, msgLength, msgCount;
    private byte[] msgBuf;
    private final int[] checksum = new int[2];
    private int seqNum;
    private int framesToGo;
    private int multiType;
    private byte[] multiBuf = new byte[0];
    private int multiLen;

    public Fbus2(Session s, ConnectionType ct) {
        this.s = s;
        this.ct = ct;
    }

    private void writeFrame(byte[] msg, int len, int type) throws IOException {
        byte[] buf = new byte[len + 10];
        buf[0] = (byte) (ct == ConnectionType.FBUSIRDA ? IRDA_FRAME_ID : FRAME_ID);
        buf[1] = DEVICE_PHONE;
        buf[2] = DEVICE_PC;
        buf[3] = (byte) type;
        buf[4] = (byte) (len / 256);
        buf[5] = (byte) (len % 256);
        System.arraycopy(msg, 0, buf, 6, len);
        int length = len + 6;
        if ((len % 2) != 0) buf[length++] = 0;
        int c = 0;
        for (int i = 0; i < length; i += 2) c ^= buf[i];
        buf[length++] = (byte) c;
        c = 0;
        for (int i = 1; i < length; i += 2) c ^= buf[i];
        buf[length++] = (byte) c;
        s.device().write(buf, 0, length);
    }

    @Override
    public void writeMessage(byte[] msg, int length, int type) throws IOException {
        s.dumpSent(msg, length, type);
        int nom = (length + MAX_TRANSMIT_LENGTH - 1) / MAX_TRANSMIT_LENGTH;
        if (nom == 0) nom = 1;
        int togo = length;
        byte[] buf = new byte[MAX_TRANSMIT_LENGTH + 2];
        for (int i = 0; i < nom; i++) {
            int seq = seqNum;
            if (i == 0) seq += 0x40;
            seqNum = (seqNum + 1) & 0x07;
            int thislength = Math.min(togo, MAX_TRANSMIT_LENGTH);
            System.arraycopy(msg, length - togo, buf, 0, thislength);
            buf[thislength] = (byte) (nom - i);
            buf[thislength + 1] = (byte) seq;
            togo -= thislength;
            writeFrame(buf, thislength + 2, type);
        }
    }

    private void sendAck(int type, int seq) throws IOException {
        s.log("[Sending Ack of type " + String.format("%02x", type) + ", seq " + Integer.toHexString(seq) + "]");
        writeFrame(Hex.bytes(type, seq), 2, ACK_BYTE);
    }

    @Override
    public void stateMachine(int rx) throws IOException {
        rx &= 0xFF;
        checksum[msgCount & 1] ^= rx;
        switch (state) {
            case MSG: {
                msgBuf[msgCount++] = (byte) rx;
                if (msgCount != msgLength + (msgLength % 2) + 2) return;
                state = Rx.SYNC;
                if (checksum[0] != checksum[1]) {
                    s.log("[ERROR: checksum]");
                    return;
                }
                int seq = msgBuf[msgLength - 1] & 0xFF;
                if (msgType == ACK_BYTE) {
                    s.log(String.format("[Received Ack of type %02x, seq %02x]", msgBuf[0] & 0xFF, seq));
                    return;
                }
                int frm = msgBuf[msgLength - 2] & 0xFF;
                if ((seq & 0x40) == 0x40) {
                    framesToGo = frm;
                    multiLen = 0;
                    multiType = msgType;
                }
                if ((seq & 0x40) != 0x40 && framesToGo != frm) {
                    s.log("[ERROR: Missed part of multiframe msg]");
                    return;
                }
                if ((seq & 0x40) != 0x40 && msgType != multiType) {
                    s.log("[ERROR: Multiframe msg in multiframe msg]");
                    return;
                }
                int add = msgLength - 2;
                if (multiBuf.length < multiLen + add) {
                    byte[] n = new byte[multiLen + add];
                    System.arraycopy(multiBuf, 0, n, 0, multiLen);
                    multiBuf = n;
                }
                System.arraycopy(msgBuf, 0, multiBuf, multiLen, add);
                multiLen += add;
                framesToGo--;
                if (msgType != 0) sendAck(msgType, seq & 0x0F);
                if (framesToGo == 0) {
                    byte[] whole = new byte[multiLen];
                    System.arraycopy(multiBuf, 0, whole, 0, multiLen);
                    s.dispatch(new ProtocolMessage(multiType, whole, multiLen));
                }
                return;
            }
            case LEN2:
                msgLength += rx;
                msgBuf = new byte[msgLength + 3];
                state = Rx.MSG;
                return;
            case LEN1:
                msgLength = rx * 256;
                state = Rx.LEN2;
                return;
            case TYPE:
                msgType = rx;
                state = Rx.LEN1;
                return;
            case SRC:
                if (rx != DEVICE_PHONE) {
                    s.log(String.format("[ERROR: incorrect char - %02x, not %02x]", rx, DEVICE_PHONE));
                    state = Rx.SYNC;
                    return;
                }
                state = Rx.TYPE;
                return;
            case DEST:
                if (rx != DEVICE_PC) {
                    s.log(String.format("[ERROR: incorrect char - %02x, not %02x]", rx, DEVICE_PC));
                    state = Rx.SYNC;
                    return;
                }
                state = Rx.SRC;
                return;
            case SYNC:
            default: {
                int want = ct == ConnectionType.FBUSIRDA ? IRDA_FRAME_ID : FRAME_ID;
                if (rx != want) {
                    if (rx == 0x20) {
                        s.log("0x20 reply detected... Trying to reinit connection...");
                        initialise();
                    }
                    return;
                }
                checksum[0] = rx;
                checksum[1] = 0;
                msgCount = 0;
                state = Rx.DEST;
            }
        }
    }

    /** FBUS2_WriteDLR3: send an AT command and drain the reply. */
    private void writeDlr3(String cmd, int timeout) throws IOException {
        byte[] c = Hex.latin1(cmd);
        s.log("AT> " + cmd.trim());
        s.device().write(c, 0, c.length);
        byte[] buf = new byte[300];
        boolean was = false;
        StringBuilder reply = new StringBuilder();
        for (int w = 0; w < timeout; w++) {
            int n = s.device().read(buf, 0, buf.length, 0);
            if (was && n == 0) break;
            if (n > 0) {
                was = true;
                for (int i = 0; i < n; i++) {
                    int ch = buf[i] & 0xFF;
                    reply.append(ch >= 32 && ch < 127 ? (char) ch : ' ');
                }
            }
            Hex.sleep(50);
        }
        if (reply.length() > 0) s.log("AT< " + reply.toString().trim());
    }

    private void atSwitch() throws IOException {
        s.log("Switching to FBUS using AT commands");
        writeDlr3("AT\r\n", 100);
        writeDlr3("AT&F\r\n", 100);
        writeDlr3("AT*NOKIAFBUS\r\n", 100);
    }

    private void initSequence(int repeats, int delayUs, boolean terminate) throws IOException {
        // Gammu writes one 0x55 at a time; a USB cable gets them in one transfer,
        // which puts the same bytes on the wire.
        byte[] b = new byte[repeats + (terminate ? 1 : 0)];
        for (int i = 0; i < repeats; i++) b[i] = 0x55;
        if (terminate) b[repeats] = (byte) 0xC1;
        s.device().write(b, 0, b.length);
        long wire = (long) repeats * Math.max(delayUs, 87) / 1000;
        Hex.sleep(wire + 1000);
    }

    @Override
    public void initialise() throws IOException {
        Device d = s.device();
        state = Rx.SYNC;
        seqNum = 0;
        framesToGo = 0;
        multiLen = 0;
        d.setParity(false);
        switch (ct) {
            case DKU5:
            case FBUSPL2303:
            case DLR3:
                d.setDtrRts(false, false);
                Hex.sleep(1000);
                if (!s.noPowerCable) {
                    d.setDtrRts(true, true);
                    Hex.sleep(1000);
                }
                d.setSpeed(19200);
                atSwitch();
                d.setSpeed(115200);
                initSequence(32, 0, true);
                break;
            case FBUS:
                d.setSpeed(115200);
                d.setDtrRts(!s.noPowerCable, false);
                initSequence(32, 0, true);
                break;
            case FBUSIRDA:
                d.setSpeed(9600);
                initSequence(32, 0, true);
                d.setSpeed(115200);
                break;
            default:
                break;
        }
        initSequence(250, 100, false);
        byte[] junk = new byte[300];
        while (d.read(junk, 0, junk.length, 20) > 0) {
            Hex.sleep(1);
        }
    }

    @Override
    public void terminate() {
        Hex.sleep(200);
    }
}
