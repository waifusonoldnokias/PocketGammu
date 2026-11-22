package com.pocketgammu.engine.usb;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.pocketgammu.engine.Device;
import com.pocketgammu.engine.GsmError;
import com.pocketgammu.engine.GsmException;

import java.io.IOException;

/**
 * Serial cables GammuDroid's own PL2303 driver doesn't cover (FTDI, CH340, CP210x,
 * CDC ACM), through the usb-serial-for-android library. Same {@link Device} contract
 * as {@link Pl2303}.
 */
public final class SerialPortDevice implements Device {
    private final UsbDevice device;
    private final UsbSerialPort port;
    private final String chip;
    private int baud = 9600;
    private int parity = UsbSerialPort.PARITY_NONE;
    private final byte[] tmp = new byte[4096];

    public static boolean supports(UsbDevice d) {
        return UsbSerialProber.getDefaultProber().probeDevice(d) != null;
    }

    public SerialPortDevice(UsbManager mgr, UsbDevice d) throws IOException {
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(d);
        if (driver == null) throw new IOException("No serial driver for this USB device");
        UsbDeviceConnection conn = mgr.openDevice(d);
        if (conn == null) throw new IOException("Android refused to open the USB device (no permission?)");
        device = d;
        chip = driver.getClass().getSimpleName().replace("SerialDriver", "");
        port = driver.getPorts().get(0);
        port.open(conn);
        port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, parity);
    }

    @Override
    public int read(byte[] buf, int off, int len, int timeoutMs) throws IOException {
        int n = port.read(tmp, Math.max(1, timeoutMs));
        n = Math.min(n, len);
        if (n > 0) System.arraycopy(tmp, 0, buf, off, n);
        return Math.max(n, 0);
    }

    @Override
    public void write(byte[] buf, int off, int len) throws IOException {
        byte[] b = new byte[len];
        System.arraycopy(buf, off, b, 0, len);
        port.write(b, 2000);
    }

    @Override
    public void setSpeed(int baud) throws IOException {
        this.baud = baud;
        apply(GsmError.DEVICECHANGESPEEDERROR);
    }

    @Override
    public void setParity(boolean on) throws IOException {
        parity = on ? UsbSerialPort.PARITY_ODD : UsbSerialPort.PARITY_NONE;
        apply(GsmError.DEVICECHANGESPEEDERROR);
    }

    private void apply(GsmError err) throws GsmException {
        try {
            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, parity);
        } catch (IOException | UnsupportedOperationException e) {
            throw new GsmException(err, e.getMessage());
        }
    }

    @Override
    public void setDtrRts(boolean dtr, boolean rts) throws IOException {
        try {
            port.setDTR(dtr);
            port.setRTS(rts);
        } catch (IOException | UnsupportedOperationException e) {
            throw new GsmException(GsmError.DEVICEDTRRTSERROR, e.getMessage());
        }
    }

    @Override
    public void close() {
        try {
            port.close();
        } catch (IOException ignored) {
            // already gone
        }
    }

    @Override
    public String describe() {
        return chip + " serial " + String.format("%04x:%04x", device.getVendorId(), device.getProductId());
    }
}
