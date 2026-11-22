package com.pocketgammu.engine.usb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Shared plumbing for the userspace USB drivers: open/claim, endpoints, a receive queue. */
abstract class UsbIo {
    protected final UsbDevice device;
    protected final UsbDeviceConnection conn;
    protected final UsbInterface intf;
    protected UsbEndpoint bulkIn, bulkOut, intIn;
    private final ArrayBlockingQueue<byte[]> rx = new ArrayBlockingQueue<>(1024);
    private byte[] rxPart;
    private int rxPartOff;
    protected volatile boolean closed;
    protected Thread reader;

    UsbIo(UsbManager mgr, UsbDevice device) throws IOException {
        this(mgr, device, device.getInterface(0));
    }

    UsbIo(UsbManager mgr, UsbDevice device, UsbInterface claim) throws IOException {
        this.device = device;
        this.intf = claim;
        this.conn = mgr.openDevice(device);
        if (conn == null) throw new IOException("Android refused to open the USB device (no permission?)");
        if (!conn.claimInterface(intf, true)) {
            conn.close();
            throw new IOException("Could not claim the USB interface");
        }
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint e = intf.getEndpoint(i);
            boolean in = e.getDirection() == UsbConstants.USB_DIR_IN;
            if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (in) bulkIn = e;
                else bulkOut = e;
            } else if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_INT && in) {
                intIn = e;
            }
        }
    }

    protected void deliver(byte[] b, int n) {
        if (n <= 0) return;
        byte[] c = new byte[n];
        System.arraycopy(b, 0, c, 0, n);
        if (!rx.offer(c)) {
            rx.poll(); // overrun: drop the oldest chunk rather than block the USB thread
            rx.offer(c);
        }
    }

    protected int take(byte[] buf, int off, int len, int timeoutMs) throws IOException {
        int n = 0;
        try {
            if (rxPart == null) {
                rxPart = timeoutMs <= 0 ? rx.poll() : rx.poll(timeoutMs, TimeUnit.MILLISECONDS);
                rxPartOff = 0;
            }
            while (rxPart != null && n < len) {
                int c = Math.min(len - n, rxPart.length - rxPartOff);
                System.arraycopy(rxPart, rxPartOff, buf, off + n, c);
                n += c;
                rxPartOff += c;
                if (rxPartOff == rxPart.length) {
                    rxPart = rx.poll();
                    rxPartOff = 0;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        if (closed && n == 0) throw new IOException("USB device closed");
        return n;
    }

    protected int ctrl(int type, int request, int value, int index, byte[] data, int len) {
        return conn.controlTransfer(type, request, value, index, data, len, 300);
    }

    protected void startReader(Runnable loop, String name) {
        reader = new Thread(loop, name);
        reader.setDaemon(true);
        reader.start();
    }

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
            conn.releaseInterface(intf);
        } catch (RuntimeException ignored) {
            // device already detached
        }
        conn.close();
    }

    protected String id() {
        return String.format("%04x:%04x", device.getVendorId(), device.getProductId());
    }
}
