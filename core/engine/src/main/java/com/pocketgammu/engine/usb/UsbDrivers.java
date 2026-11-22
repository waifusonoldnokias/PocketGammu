package com.pocketgammu.engine.usb;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import com.pocketgammu.engine.LogSink;
import com.pocketgammu.engine.irda.IrPort;

import java.io.IOException;

/** Matches attached USB devices to their drivers. */
public final class UsbDrivers {
    public enum Kind {
        PL2303("Prolific PL2303 serial / FBUS cable", false),
        STIR4200("SigmaTel STIr4200 IrDA dongle", true),
        NOKIA_USB("Nokia phone on USB (DKU-2 / CA-53)", false),
        SERIAL("USB serial / FBUS cable (FTDI, CH340, CP210x)", false),
        UNSUPPORTED("Unsupported USB device", false);

        public final String label;
        public final boolean infrared;

        Kind(String label, boolean infrared) {
            this.label = label;
            this.infrared = infrared;
        }
    }

    /** Vendor/product IDs from Linux drivers/usb/serial/pl2303.c. */
    private static final int[] PL2303_IDS = {
        0x067b, 0x2303, 0x067b, 0x04bb, 0x067b, 0x1234, 0x067b, 0xaaa2, 0x067b, 0xaaa8, 0x067b, 0xaaa0,
        0x067b, 0x0611, 0x067b, 0x0612, 0x067b, 0x0609, 0x067b, 0x331a, 0x067b, 0x0307, 0x067b, 0xe1f1,
        0x067b, 0x2304, 0x067b, 0x23a3, 0x067b, 0x23b3, 0x067b, 0x23c3, 0x067b, 0x23d3, 0x067b, 0x23e3,
        0x067b, 0x23f3, 0x067b, 0x2533, 0x04bb, 0x0a03, 0x04bb, 0x0a0e, 0x0557, 0x2008, 0x0557, 0x2021,
        0x0557, 0x2022, 0x0557, 0x2118, 0x0547, 0x2008, 0x056e, 0x5003, 0x056e, 0x5004, 0x0eba, 0x1080,
        0x0eba, 0x2080, 0x0df7, 0x0620, 0x0584, 0xb000, 0x2478, 0x2008, 0x1453, 0x4026, 0x0731, 0x0528,
        0x6189, 0x2068, 0x11f7, 0x02df, 0x11f5, 0x0001, 0x11f5, 0x0003, 0x11f5, 0x0004, 0x11f5, 0x0005,
        0x04a5, 0x4027, 0x0745, 0x0001, 0x078b, 0x1234, 0x10b5, 0xac70, 0x079b, 0x0027, 0x0413, 0x2101,
        0x0e55, 0x110b, 0x0731, 0x2003, 0x050d, 0x0257, 0x058f, 0x9720, 0x11f6, 0x2001, 0x07aa, 0x002a,
        0x05ad, 0x0fba, 0x5372, 0x2303, 0x03f0, 0x3524, 0x03f0, 0x4349, 0x03f0, 0x0f7f, 0x03f0, 0x0183,
        0x03f0, 0x0b39, 0x03f0, 0x4439, 0x03f0, 0x3139, 0x03f0, 0x3239, 0x03f0, 0x026b, 0x03f0, 0x0f9b,
        0x03f0, 0x5039, 0x03f0, 0x0956, 0x04b8, 0x0521, 0x04b8, 0x0522, 0x054c, 0x0437, 0x11ad, 0x0001,
        0x0b63, 0x6530, 0x0b63, 0x653a, 0x0b8c, 0x2303, 0x0caa, 0x3001, 0x04b3, 0x4016, 0x345f, 0x3020,
    };

    private UsbDrivers() {}

    public static Kind detect(UsbDevice d) {
        int v = d.getVendorId(), p = d.getProductId();
        if (v == Stir4200.VID && p == Stir4200.PID) return Kind.STIR4200;
        if (NokiaUsb.findControl(d) != null) return Kind.NOKIA_USB;
        for (int i = 0; i < PL2303_IDS.length; i += 2) {
            if (PL2303_IDS[i] == v && PL2303_IDS[i + 1] == p) return Kind.PL2303;
        }
        if (SerialPortDevice.supports(d)) return Kind.SERIAL;
        return Kind.UNSUPPORTED;
    }

    /** PL2303 cables use GammuDroid's own driver; other serial chips use usb-serial-for-android. */
    public static com.pocketgammu.engine.Device openSerial(UsbManager m, UsbDevice d) throws IOException {
        return detect(d) == Kind.PL2303 ? new Pl2303(m, d) : new SerialPortDevice(m, d);
    }

    public static NokiaUsb openNokia(UsbManager m, UsbDevice d) throws IOException {
        return new NokiaUsb(m, d);
    }

    public static IrPort openIr(UsbManager m, UsbDevice d) throws IOException {
        return openIr(m, d, LogSink.NONE);
    }

    public static IrPort openIr(UsbManager m, UsbDevice d, LogSink log) throws IOException {
        switch (detect(d)) {
            case STIR4200: return new Stir4200(m, d);
            default: throw new IOException("not an IrDA dongle this app knows");
        }
    }
}
