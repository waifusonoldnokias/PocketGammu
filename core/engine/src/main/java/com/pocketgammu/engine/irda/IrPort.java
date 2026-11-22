package com.pocketgammu.engine.irda;

import java.io.IOException;

/**
 * A raw infrared byte pipe through a SIR dongle (SigmaTel STIr4200). The dongle only turns UART bytes into IR pulses and back; IrDA
 * framing, when wanted, is done above this interface.
 */
public interface IrPort {
    void setSpeed(int baud) throws IOException;

    void write(byte[] b, int off, int len) throws IOException;

    /** Returns 0 on timeout. */
    int read(byte[] b, int off, int len, int timeoutMs) throws IOException;

    /** Highest SIR speed the dongle can receive reliably. */
    int maxSpeed();

    void close();

    String describe();
}
