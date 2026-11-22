package com.pocketgammu.engine.usb;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import com.pocketgammu.engine.Device;
import com.pocketgammu.engine.GsmError;
import com.pocketgammu.engine.GsmException;

import java.io.IOException;

/**
 * Userspace driver for Prolific PL2303 USB-serial chips (H, HX, HXD, TA, TB
 * and the HXN "G" series), ported from Linux drivers/usb/serial/pl2303.c.
 * Used for DAU-9P/FBUS serial cables behind a PL2303 adapter and for CA-42
 * and DKU-5 clone cables.
 */
public final class Pl2303 extends UsbIo implements Device {
    enum Type { H, HX, TA, TB, HXD, HXN }

    private static final int VENDOR_WRITE_TYPE = 0x40, VENDOR_READ_TYPE = 0xC0;
    private static final int SET_LINE = 0x20, SET_CONTROL = 0x22;
    private static final int[] DIRECT_BAUDS = {75, 150, 300, 600, 1200, 1800, 2400, 3600, 4800, 7200, 9600, 14400,
        19200, 28800, 38400, 57600, 115200, 230400, 460800, 614400, 921600, 1228800, 2457600, 3000000, 6000000};

    private final Type type;
    private final byte[] line = new byte[7];
    private int baud = 9600;
    private boolean parity = false;

    public Pl2303(UsbManager mgr, UsbDevice dev) throws IOException {
        super(mgr, dev);
        if (bulkIn == null || bulkOut == null) {
            close();
            throw new IOException("PL2303 endpoints not found");
        }
        type = detectType();
        startup();
        open();
        startReader(new Runnable() {
            @Override
            public void run() {
                byte[] b = new byte[Math.max(64, bulkIn.getMaxPacketSize()) * 16];
                while (!closed) {
                    int n = conn.bulkTransfer(bulkIn, b, b.length, 100);
                    if (n > 0) deliver(b, n);
                }
            }
        }, "pl2303-rx");
    }

    private Type detectType() {
        byte[] d = conn.getRawDescriptors();
        if (d == null || d.length < 18) return Type.HX;
        int devClass = d[4] & 0xFF;
        int maxPacket0 = d[7] & 0xFF;
        int bcdUsb = (d[2] & 0xFF) | (d[3] & 0xFF) << 8;
        int bcdDevice = (d[12] & 0xFF) | (d[13] & 0xFF) << 8;
        if (devClass == 0x02) return Type.H;
        if (maxPacket0 != 0x40) return Type.H;
        if (bcdUsb == 0x101 || bcdUsb == 0x110) return bcdDevice == 0x400 ? Type.HXD : Type.HX;
        if (bcdUsb == 0x200) {
            switch (bcdDevice) {
                case 0x300:
                    return supportsHxStatus() ? Type.TA : Type.HXN;
                case 0x500:
                    return supportsHxStatus() ? Type.TB : Type.HXN;
                default:
                    return Type.HXN;
            }
        }
        return Type.HX;
    }

    private boolean supportsHxStatus() {
        byte[] b = new byte[1];
        return ctrl(VENDOR_READ_TYPE, 0x01, 0x8080, 0, b, 1) == 1;
    }

    private int vendorRead(int value) {
        byte[] b = new byte[1];
        int req = type == Type.HXN ? 0x81 : 0x01;
        int r = ctrl(VENDOR_READ_TYPE, req, value, 0, b, 1);
        return r == 1 ? b[0] & 0xFF : -1;
    }

    private void vendorWrite(int value, int index) {
        int req = type == Type.HXN ? 0x80 : 0x01;
        ctrl(VENDOR_WRITE_TYPE, req, value, index, null, 0);
    }

    private void updateReg(int reg, int mask, int val) {
        int cur = vendorRead(type == Type.HXN ? reg : reg | 0x80);
        if (cur < 0) cur = 0;
        vendorWrite(reg, (cur & ~mask) | (val & mask));
    }

    /** pl2303_startup: the vendor init sequence for everything but HXN. */
    private void startup() {
        if (type == Type.HXN) return;
        vendorRead(0x8484);
        vendorWrite(0x0404, 0);
        vendorRead(0x8484);
        vendorRead(0x8383);
        vendorRead(0x8484);
        vendorWrite(0x0404, 1);
        vendorRead(0x8484);
        vendorRead(0x8383);
        vendorWrite(0, 1);
        vendorWrite(1, 0);
        vendorWrite(2, type == Type.H ? 0x24 : 0x44);
    }

    /** pl2303_open: reset the data pipes, then set the line. */
    private void open() throws IOException {
        if (type == Type.HXN) {
            vendorWrite(0x07, 0x03);
        } else if (type != Type.H) {
            vendorWrite(8, 0);
            vendorWrite(9, 0);
        }
        setLine();
    }

    private void encodeBaud(int b) {
        int max = type == Type.H ? 1228800 : (type == Type.HX || type == Type.TA) ? 6000000 : 12000000;
        b = Math.min(b, max);
        boolean direct = type == Type.HXN;
        if (!direct) {
            for (int s : DIRECT_BAUDS) if (s == b) direct = true;
        }
        if (direct) {
            line[0] = (byte) b;
            line[1] = (byte) (b >> 8);
            line[2] = (byte) (b >> 16);
            line[3] = (byte) (b >> 24);
            return;
        }
        int baseline = 12000000 * 32;
        int mantissa = Math.max(1, baseline / b);
        int exponent = 0;
        if (type == Type.TA || type == Type.TB) {
            while (mantissa >= 2048) {
                if (exponent < 15) {
                    mantissa >>= 1;
                    exponent++;
                } else {
                    mantissa = 2047;
                    break;
                }
            }
            line[3] = (byte) 0x80;
            line[2] = (byte) (exponent & 0x01);
            line[1] = (byte) (((exponent & ~0x01) << 4) | (mantissa >> 8));
            line[0] = (byte) mantissa;
        } else {
            while (mantissa >= 512) {
                if (exponent < 7) {
                    mantissa >>= 2;
                    exponent++;
                } else {
                    mantissa = 511;
                    break;
                }
            }
            line[3] = (byte) 0x80;
            line[2] = 0;
            line[1] = (byte) ((exponent << 1) | (mantissa >> 8));
            line[0] = (byte) mantissa;
        }
    }

    private void setLine() throws IOException {
        encodeBaud(baud);
        line[4] = 0;                       // 1 stop bit
        line[5] = (byte) (parity ? 1 : 0); // odd parity (MBUS) or none
        line[6] = 8;
        if (ctrl(0x21, SET_LINE, 0, intf.getId(), line, 7) < 0) {
            throw new GsmException(GsmError.DEVICECHANGESPEEDERROR, "PL2303 SET_LINE_REQUEST failed");
        }
        // no flow control
        if (type == Type.HXN) updateReg(0x0A, 0x1C, 0x1C);
        else updateReg(0, 0xF0, 0);
    }

    @Override
    public int read(byte[] buf, int off, int len, int timeoutMs) throws IOException {
        return take(buf, off, len, timeoutMs);
    }

    @Override
    public void write(byte[] buf, int off, int len) throws IOException {
        int pos = 0;
        byte[] chunk = new byte[4096];
        while (pos < len) {
            int n = Math.min(chunk.length, len - pos);
            System.arraycopy(buf, off + pos, chunk, 0, n);
            int w = conn.bulkTransfer(bulkOut, chunk, n, 2000);
            if (w < 0) throw new GsmException(GsmError.DEVICEWRITEERROR);
            pos += w;
        }
    }

    @Override
    public void setSpeed(int b) throws IOException {
        baud = b;
        setLine();
    }

    @Override
    public void setParity(boolean p) throws IOException {
        parity = p;
        setLine();
    }

    @Override
    public void setDtrRts(boolean dtr, boolean rts) throws IOException {
        int v = (dtr ? 0x01 : 0) | (rts ? 0x02 : 0);
        if (ctrl(0x21, SET_CONTROL, v, intf.getId(), null, 0) < 0) {
            throw new GsmException(GsmError.DEVICEDTRRTSERROR);
        }
    }

    @Override
    public String describe() {
        return "PL2303 " + type + " " + id();
    }
}
