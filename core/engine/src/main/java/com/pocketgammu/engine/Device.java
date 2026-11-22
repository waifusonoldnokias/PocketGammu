package com.pocketgammu.engine;

import java.io.IOException;

/**
 * The equivalent of Gammu's GSM_Device_Functions: a byte pipe to the phone.
 * Implemented by the PL2303 USB serial driver, by the raw IrDA dongle pipe
 * (for "fbusirda") and by the userspace IrDA stack (for "irdaphonet").
 */
public interface Device {
    /** Reads whatever is available, waiting at most timeoutMs. Returns 0 on timeout. */
    int read(byte[] buf, int off, int len, int timeoutMs) throws IOException;

    void write(byte[] buf, int off, int len) throws IOException;

    void setSpeed(int baud) throws IOException;

    void setParity(boolean parity) throws IOException;

    void setDtrRts(boolean dtr, boolean rts) throws IOException;

    void close();

    String describe();
}
