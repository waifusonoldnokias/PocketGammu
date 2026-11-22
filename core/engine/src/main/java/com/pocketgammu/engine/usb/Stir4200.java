package com.pocketgammu.engine.usb;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import com.pocketgammu.engine.GsmError;
import com.pocketgammu.engine.GsmException;
import com.pocketgammu.engine.Hex;
import com.pocketgammu.engine.irda.IrPort;

import java.io.IOException;

/**
 * Userspace driver for the SigmaTel STIr4200 USB IrDA bridge (066f:4200),
 * ported from Linux drivers/staging/irda/drivers/stir4200.c, SIR speeds only.
 * The chip is half duplex: reception stops while a frame is sent, and the
 * transmit FIFO must drain before receiving again.
 */
public final class Stir4200 extends UsbIo implements IrPort {
    public static final int VID = 0x066F, PID = 0x4200;
    private static final int REQ_WRITE_SINGLE = 0x03, REQ_READ_REG = 0x01;
    private static final int REG_MODE = 1, REG_PDCLK = 2, REG_CTRL1 = 3, REG_CTRL2 = 4, REG_FIFOCTL = 5,
            REG_DPLL = 8;
    private static final int MODE_SIR = 0x20, MODE_FASTRX = 0x08, MODE_NRESET = 0x02, MODE_2400 = 0x01;
    private static final int CTRL1_SDMODE = 0x80, CTRL1_SRESET = 0x01;
    private static final int FIFOCTL_DIR = 0x10, FIFOCTL_CLR = 0x08, FIFOCTL_EMPTY = 0x04;
    private static final int FIFO_SIZE = 4096;
    private static final int TX_POWER = 0, RX_SENSITIVITY = 1;

    private int speed = 9600;
    private volatile boolean receiving = true;
    private final Object usbLock = new Object();

    public Stir4200(UsbManager mgr, UsbDevice dev) throws IOException {
        super(mgr, dev);
        if (bulkIn == null || bulkOut == null) {
            close();
            throw new IOException("STIr4200 endpoints not found");
        }
        changeSpeed(9600);
        startReader(new Runnable() {
            @Override
            public void run() {
                byte[] b = new byte[FIFO_SIZE];
                while (!closed) {
                    if (!receiving) {
                        Hex.sleep(2);
                        continue;
                    }
                    int n;
                    synchronized (usbLock) {
                        n = receiving ? conn.bulkTransfer(bulkIn, b, b.length, 20) : 0;
                    }
                    if (n > 0) deliver(b, n);
                }
            }
        }, "stir4200-rx");
    }

    private void writeReg(int reg, int value) throws IOException {
        if (ctrl(0x40, REQ_WRITE_SINGLE, value, reg, null, 0) < 0) {
            throw new GsmException(GsmError.DEVICEWRITEERROR, "STIr4200 register " + reg);
        }
    }

    private static int pdclk(int speed) throws GsmException {
        switch (speed) {
            case 2400: return 0xDF;
            case 9600: return 0x77;
            case 19200: return 0x3B;
            case 38400: return 0x1D;
            case 57600: return 0x13;
            case 115200: return 0x09;
            default: throw new GsmException(GsmError.DEVICECHANGESPEEDERROR, "STIr4200 SIR speed " + speed);
        }
    }

    /** change_speed() for SIR. */
    private void changeSpeed(int s) throws IOException {
        int clk = pdclk(s);
        writeReg(REG_CTRL1, CTRL1_SRESET);
        writeReg(REG_DPLL, 0x15);
        writeReg(REG_PDCLK, clk);
        int mode = MODE_NRESET | MODE_FASTRX | MODE_SIR;
        if (s == 2400) mode |= MODE_2400;
        writeReg(REG_MODE, mode);
        writeReg(REG_CTRL1, CTRL1_SDMODE | (TX_POWER & 3) << 1);
        writeReg(REG_CTRL1, (TX_POWER & 3) << 1);
        writeReg(REG_CTRL2, (RX_SENSITIVITY & 7) << 5);
        speed = s;
    }

    /** fifo_txwait(): wait for room (space >= 0) or for the FIFO to drain (space < 0). */
    private void fifoTxWait(int space) throws IOException {
        byte[] st = new byte[3];
        int prev = 0x1FFF;
        for (int guard = 0; guard < 2000; guard++) {
            if (ctrl(0xC0, REQ_READ_REG, 0, REG_FIFOCTL, st, 3) != 3) {
                throw new GsmException(GsmError.DEVICEREADERROR, "STIr4200 FIFO status");
            }
            int status = st[0] & 0xFF;
            int count = ((st[2] & 0x1F) << 8) | (st[1] & 0xFF);
            if ((status & FIFOCTL_DIR) == 0 || (status & FIFOCTL_EMPTY) != 0) return;
            if (space >= 0 && FIFO_SIZE - 4 > space + count) return;
            if (prev < count) break; // queue confused
            prev = count;
            Hex.sleep(Math.max(1, (count * 8000L) / speed));
        }
        writeReg(REG_FIFOCTL, FIFOCTL_CLR);
        writeReg(REG_FIFOCTL, 0);
    }

    @Override
    public void setSpeed(int baud) throws IOException {
        synchronized (usbLock) {
            fifoTxWait(-1);
            changeSpeed(baud);
        }
    }

    /** stir_send() with wrap_sir_skb()'s 4-byte header; the data is already SIR-wrapped (or raw FBUS). */
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        receiving = false;
        synchronized (usbLock) {
            try {
                int pos = 0;
                boolean first = true;
                while (pos < len) {
                    int n = Math.min(FIFO_SIZE - 4, len - pos);
                    byte[] f = new byte[4 + n];
                    f[0] = 0x55;
                    f[1] = (byte) 0xAA;
                    f[2] = (byte) n;
                    f[3] = (byte) (n >> 8);
                    System.arraycopy(b, off + pos, f, 4, n);
                    if (!first) fifoTxWait(f.length);
                    if (conn.bulkTransfer(bulkOut, f, f.length, 2000) < 0) {
                        throw new GsmException(GsmError.DEVICEWRITEERROR, "STIr4200");
                    }
                    first = false;
                    pos += n;
                }
                // "Wait otherwise chip gets confused" before receiving again.
                fifoTxWait(-1);
            } finally {
                receiving = true;
            }
        }
    }

    @Override
    public int read(byte[] b, int off, int len, int timeoutMs) throws IOException {
        return take(b, off, len, timeoutMs);
    }

    @Override
    public int maxSpeed() {
        return 115200;
    }

    @Override
    public String describe() {
        return "SigmaTel STIr4200 " + id();
    }
}
