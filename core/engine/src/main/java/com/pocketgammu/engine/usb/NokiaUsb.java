package com.pocketgammu.engine.usb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import com.pocketgammu.engine.Device;
import com.pocketgammu.engine.GsmError;
import com.pocketgammu.engine.GsmException;

import java.io.IOException;

/**
 * Nokia's own USB interface (DKU-2 and CA-53 cables, and phones with a
 * built-in USB port such as the 6020, 6230 and 6021), ported from Gammu's
 * libgammu/device/usb/usb.c ("fbususb"/"dku2"). The phone exposes a CDC
 * interface with subclass 0xFE; its CDC union descriptor names a data
 * interface whose two-endpoint alternate setting carries PhoNet frames.
 */
public final class NokiaUsb extends UsbIo implements Device {
    public static final int NOKIA_VENDOR_ID = 0x0421;
    private static final int CDC_CLASS = 0x02, FBUS_SUBCLASS = 0xFE;

    private final UsbInterface dataActive, dataIdle;

    /** The control interface of a Nokia phone in PC Suite mode, or null. */
    static UsbInterface findControl(UsbDevice d) {
        if (d.getVendorId() != NOKIA_VENDOR_ID) return null;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface u = d.getInterface(i);
            if (u.getInterfaceClass() == CDC_CLASS && u.getInterfaceSubclass() == FBUS_SUBCLASS) return u;
        }
        return null;
    }

    private static UsbInterface controlOf(UsbDevice d) throws IOException {
        UsbInterface c = findControl(d);
        if (c == null) {
            throw new IOException("This Nokia has no PhoNet USB interface. Choose \"PC Suite\" mode on the phone.");
        }
        return c;
    }

    public NokiaUsb(UsbManager mgr, UsbDevice dev) throws IOException {
        super(mgr, dev, controlOf(dev));
        int dataId = unionSlave();
        if (dataId < 0) dataId = intf.getId() + 1; // fall back to the usual layout
        UsbInterface active = null, idle = null;
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface u = dev.getInterface(i);
            if (u.getId() != dataId) continue;
            if (u.getEndpointCount() == 2 && u.getEndpoint(0).getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && u.getEndpoint(1).getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                active = u;
            } else if (u.getEndpointCount() == 0) {
                idle = u;
            }
        }
        if (active == null) {
            close();
            throw new GsmException(GsmError.DEVICEOPENERROR, "Nokia USB data interface not found");
        }
        dataActive = active;
        dataIdle = idle;
        if (!conn.claimInterface(active, true) || !conn.setInterface(active)) {
            close();
            throw new GsmException(GsmError.DEVICEOPENERROR, "could not select the Nokia USB data interface");
        }
        for (int i = 0; i < 2; i++) {
            UsbEndpoint e = active.getEndpoint(i);
            if (e.getDirection() == UsbConstants.USB_DIR_IN) bulkIn = e;
            else bulkOut = e;
        }
        startReader(new Runnable() {
            @Override
            public void run() {
                byte[] b = new byte[Math.max(512, bulkIn.getMaxPacketSize()) * 8];
                while (!closed) {
                    int n = conn.bulkTransfer(bulkIn, b, b.length, 200);
                    if (n > 0) deliver(b, n);
                }
            }
        }, "nokia-usb-rx");
    }

    /** bSlaveInterface0 of the CDC union descriptor that follows the control interface. */
    private int unionSlave() {
        byte[] d = conn.getRawDescriptors();
        if (d == null) return -1;
        boolean inControl = false;
        for (int i = 0; i + 1 < d.length; ) {
            int len = d[i] & 0xFF, type = d[i + 1] & 0xFF;
            if (len < 2) break;
            if (type == 0x04 && i + 6 < d.length) { // interface descriptor
                inControl = (d[i + 2] & 0xFF) == intf.getId() && (d[i + 3] & 0xFF) == intf.getAlternateSetting();
            } else if (inControl && type == 0x24 && i + 4 < d.length && (d[i + 2] & 0xFF) == 0x06) {
                return d[i + 4] & 0xFF; // CDC union: master, slave
            }
            i += len;
        }
        return -1;
    }

    @Override
    public int read(byte[] buf, int off, int len, int timeoutMs) throws IOException {
        return take(buf, off, len, timeoutMs);
    }

    @Override
    public void write(byte[] buf, int off, int len) throws IOException {
        byte[] c = new byte[len];
        System.arraycopy(buf, off, c, 0, len);
        int pos = 0;
        while (pos < len) {
            byte[] part = pos == 0 ? c : java.util.Arrays.copyOfRange(c, pos, len);
            int w = conn.bulkTransfer(bulkOut, part, part.length, 1000);
            if (w < 0) throw new GsmException(GsmError.DEVICEWRITEERROR);
            pos += w;
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
        if (reader != null) {
            try {
                reader.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            if (dataIdle != null) conn.setInterface(dataIdle);
            if (dataActive != null) conn.releaseInterface(dataActive);
        } catch (RuntimeException ignored) {
            // phone unplugged
        }
        super.close();
    }

    @Override
    public String describe() {
        return "Nokia USB " + id();
    }
}
