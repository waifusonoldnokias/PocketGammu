package com.pocketgammu.engine;

import java.io.IOException;

/** Port of libgammu/protocol/nokia/phonet.c for the "irdaphonet" connection. */
public final class Phonet implements Protocol {
    static final int FRAME_ID = 0x14;      // IrDA
    static final int DKU2_FRAME_ID = 0x1B; // Nokia USB
    static final int DEVICE_PHONE = 0x00;
    static final int DEVICE_PC = 0x0C;

    private enum Rx { SYNC, DEST, SRC, TYPE, LEN1, LEN2, MSG }

    private final Session s;
    private final int frameId;
    private Rx state = Rx.SYNC;
    private int type, length, count;
    private byte[] buf;

    public Phonet(Session s, ConnectionType ct) {
        this.s = s;
        this.frameId = ct == ConnectionType.DKU2 ? DKU2_FRAME_ID : FRAME_ID;
    }

    @Override
    public void initialise() {
        state = Rx.SYNC;
    }

    @Override
    public void writeMessage(byte[] msg, int len, int msgType) throws IOException {
        s.dumpSent(msg, len, msgType);
        byte[] b = new byte[len + 6];
        b[0] = (byte) frameId;
        b[1] = DEVICE_PHONE;
        b[2] = DEVICE_PC;
        b[3] = (byte) msgType;
        b[4] = (byte) (len / 256);
        b[5] = (byte) (len % 256);
        System.arraycopy(msg, 0, b, 6, len);
        s.device().write(b, 0, b.length);
    }

    @Override
    public void stateMachine(int rx) throws IOException {
        rx &= 0xFF;
        switch (state) {
            case MSG:
                buf[count++] = (byte) rx;
                if (count != length) return;
                state = Rx.SYNC;
                s.dispatch(new ProtocolMessage(type, buf, length));
                return;
            case LEN2:
                length += rx;
                buf = new byte[length];
                state = Rx.MSG;
                if (length == 0) {
                    state = Rx.SYNC;
                    s.dispatch(new ProtocolMessage(type, buf, 0));
                }
                return;
            case LEN1:
                length = rx * 256;
                state = Rx.LEN2;
                return;
            case TYPE:
                type = rx;
                state = Rx.LEN1;
                return;
            case SRC:
                if (rx != DEVICE_PHONE) {
                    s.log(String.format("[ERROR: incorrect source ID - 0x%02x, not 0x%02x]", rx, DEVICE_PHONE));
                    state = Rx.SYNC;
                    return;
                }
                state = Rx.TYPE;
                return;
            case DEST:
                if (rx != DEVICE_PC) {
                    s.log(String.format("[ERROR: incorrect destination ID - 0x%02x, not 0x%02x]", rx, DEVICE_PC));
                    state = Rx.SYNC;
                    return;
                }
                state = Rx.SRC;
                return;
            case SYNC:
            default:
                if (rx != frameId) return;
                count = 0;
                state = Rx.DEST;
        }
    }

    @Override
    public void terminate() {}
}
