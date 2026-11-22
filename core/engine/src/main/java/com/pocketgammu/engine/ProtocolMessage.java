package com.pocketgammu.engine;

/** GSM_Protocol_Message: one complete message from the phone. */
public final class ProtocolMessage {
    public final int type;
    public final byte[] buffer;
    public final int length;

    public ProtocolMessage(int type, byte[] buffer, int length) {
        this.type = type;
        this.buffer = buffer;
        this.length = length;
    }

    /** Unsigned byte at index i, or 0 past the end (Gammu reads past ends silently). */
    public int u(int i) {
        return i >= 0 && i < buffer.length ? buffer[i] & 0xFF : 0;
    }
}
