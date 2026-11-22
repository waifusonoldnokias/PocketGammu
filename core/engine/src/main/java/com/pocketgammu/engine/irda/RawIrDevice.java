package com.pocketgammu.engine.irda;

import com.pocketgammu.engine.Device;

import java.io.IOException;

/**
 * Gammu's "fbusirda": FBUS frames sent as plain bytes through the infrared
 * transceiver, without IrDA framing. DCT-3 phones such as the 6210, 7110 and
 * 8210 answer this when their infrared port is switched on.
 */
public final class RawIrDevice implements Device {
    private final IrPort port;

    public RawIrDevice(IrPort port) {
        this.port = port;
    }

    @Override
    public int read(byte[] buf, int off, int len, int timeoutMs) throws IOException {
        return port.read(buf, off, len, timeoutMs);
    }

    @Override
    public void write(byte[] buf, int off, int len) throws IOException {
        port.write(buf, off, len);
    }

    @Override
    public void setSpeed(int baud) throws IOException {
        port.setSpeed(baud);
    }

    @Override
    public void setParity(boolean parity) {}

    @Override
    public void setDtrRts(boolean dtr, boolean rts) {}

    @Override
    public void close() {
        port.close();
    }

    @Override
    public String describe() {
        return port.describe() + " (raw infrared)";
    }
}
