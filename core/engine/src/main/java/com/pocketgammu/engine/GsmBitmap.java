package com.pocketgammu.engine;

/**
 * GSM_Bitmap plus the phone bitmap encoders from libgammu/service/gsmlogo.c.
 * Points are stored row-major, one bit per pixel, like Gammu's BitmapPoints.
 */
public final class GsmBitmap {
    /** GSM_Bitmap_Types (the ones this app handles). */
    public enum Type { STARTUP_LOGO, OPERATOR_LOGO, CALLER_GROUP_LOGO, PICTURE_IMAGE, WELCOME_NOTE, DEALER_NOTE }

    /** GSM_Phone_Bitmap_Types. */
    public enum PhoneType {
        NOKIA_OPERATOR_LOGO(72, 14), NOKIA_CALLER_LOGO(72, 14), NOKIA_PICTURE_IMAGE(72, 28),
        NOKIA_7110_OPERATOR_LOGO(78, 21), NOKIA_6510_OPERATOR_LOGO(78, 21),
        NOKIA_STARTUP_LOGO(84, 48), NOKIA_6210_STARTUP_LOGO(96, 60), NOKIA_7110_STARTUP_LOGO(96, 65);

        public final int width, height;

        PhoneType(int w, int h) {
            width = w;
            height = h;
        }
    }

    public Type type;
    public int width, height;
    public byte[] points;
    /** Location: startup logo slot, picture image number or caller group (1-based). */
    public int location = 1;
    /** Operator logo network code, Gammu format "MCC MNC". "000 00" clears the logo. */
    public String networkCode = "000 00";
    /** Picture image text, welcome note text or caller group name. */
    public String text = "";
    public String sender = "";
    public boolean defaultName = true, defaultRingtone = true, defaultBitmap = false, bitmapEnabled = true;
    public int ringtoneId = 0;

    public GsmBitmap(Type type, int width, int height) {
        this.type = type;
        this.width = width;
        this.height = height;
        this.points = new byte[(width * height + 7) / 8];
    }

    public GsmBitmap copy() {
        GsmBitmap b = new GsmBitmap(type, width, height);
        System.arraycopy(points, 0, b.points, 0, points.length);
        b.location = location;
        b.networkCode = networkCode;
        b.text = text;
        b.sender = sender;
        b.defaultName = defaultName;
        b.defaultRingtone = defaultRingtone;
        b.defaultBitmap = defaultBitmap;
        b.bitmapEnabled = bitmapEnabled;
        b.ringtoneId = ringtoneId;
        return b;
    }

    /** GSM_GetMaxBitmapWidthHeight. */
    public static int[] maxSize(Type t) {
        switch (t) {
            case CALLER_GROUP_LOGO: return new int[] {72, 14};
            case OPERATOR_LOGO: return new int[] {101, 21};
            case STARTUP_LOGO: return new int[] {96, 65};
            case PICTURE_IMAGE: return new int[] {72, 28};
            default: return new int[] {0, 0};
        }
    }

    public boolean isPoint(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return false;
        int bit = y * width + x;
        return (points[bit / 8] & (1 << (7 - (bit % 8)))) != 0;
    }

    public void setPoint(int x, int y, boolean on) {
        if (x < 0 || y < 0 || x >= width || y >= height) return;
        int bit = y * width + x;
        if (on) points[bit / 8] |= (byte) (1 << (7 - (bit % 8)));
        else points[bit / 8] &= (byte) ~(1 << (7 - (bit % 8)));
    }

    public void clear() {
        java.util.Arrays.fill(points, (byte) 0);
    }

    public void invert() {
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) setPoint(x, y, !isPoint(x, y));
    }

    /** GSM_ResizeBitmap: centre-crop or centre-pad to the new size. */
    public GsmBitmap resized(int w, int h) {
        int startx, endx, setx, starty, endy, sety;
        if (width <= w) {
            startx = 0;
            endx = width;
            setx = (w - width) / 2;
        } else {
            startx = (width - w) / 2;
            endx = startx + w;
            setx = 0;
        }
        if (height <= h) {
            starty = 0;
            endy = height;
            sety = (h - height) / 2;
        } else {
            starty = (height - h) / 2;
            endy = starty + h;
            sety = 0;
        }
        GsmBitmap d = new GsmBitmap(type, w, h);
        for (int x = startx; x < endx; x++)
            for (int y = starty; y < endy; y++)
                if (isPoint(x, y)) d.setPoint(setx + x - startx, sety + y - starty, true);
        return d;
    }

    /** PHONE_GetBitmapSize with width/height 0 meaning the type's default size. */
    public static int phoneSize(PhoneType t) {
        return phoneSize(t, 0, 0);
    }

    public static int phoneSize(PhoneType t, int w, int h) {
        int width = t.width, height = t.height;
        if ((t == PhoneType.NOKIA_6510_OPERATOR_LOGO || t == PhoneType.NOKIA_7110_OPERATOR_LOGO) && w != 0 && h != 0) {
            width = w;
            height = h;
        }
        switch (t) {
            case NOKIA_6510_OPERATOR_LOGO: return width * ((height + 7) / 8);
            case NOKIA_7110_OPERATOR_LOGO: return (width * height + 7) / 8;
            case NOKIA_7110_STARTUP_LOGO:
            case NOKIA_6210_STARTUP_LOGO: return (height + 7) / 8 * width;
            default: return height * width / 8;
        }
    }

    private static void setPhonePoint(PhoneType t, byte[] buf, int off, int x, int y, int width) {
        switch (t) {
            case NOKIA_STARTUP_LOGO:
            case NOKIA_6210_STARTUP_LOGO:
            case NOKIA_7110_STARTUP_LOGO:
            case NOKIA_6510_OPERATOR_LOGO:
                buf[off + (y / 8 * width) + x] |= (byte) (1 << (y % 8));
                break;
            case NOKIA_PICTURE_IMAGE:
                buf[off + (9 * y) + (x / 8)] |= (byte) (1 << (7 - (x % 8)));
                break;
            default: {
                int pixel = width * y + x;
                buf[off + pixel / 8] |= (byte) (1 << (7 - (pixel % 8)));
            }
        }
    }

    /** PHONE_EncodeBitmap: writes phoneSize(t) bytes at buf[off]. */
    public void encode(PhoneType t, byte[] buf, int off) {
        GsmBitmap dest = resized(t.width, t.height);
        int size = phoneSize(t, t.width, t.height);
        for (int i = 0; i < size; i++) buf[off + i] = 0;
        for (int x = 0; x < t.width; x++)
            for (int y = 0; y < t.height; y++)
                if (dest.isPoint(x, y)) setPhonePoint(t, buf, off, x, y, t.width);
    }

    public byte[] encode(PhoneType t) {
        byte[] b = new byte[phoneSize(t)];
        encode(t, b, 0);
        return b;
    }

    /** NOKIA_CopyBitmap: 00, width, height, 01, bitmap. Returns the new length. */
    public int copyNokia(PhoneType t, byte[] buf, int length) {
        buf[length++] = 0x00;
        buf[length++] = (byte) t.width;
        buf[length++] = (byte) t.height;
        buf[length++] = 0x01;
        encode(t, buf, length);
        return length + phoneSize(t);
    }

    /** Decodes a 1-bit BMP exactly as Gammu's BMP2Bitmap does. */
    public static GsmBitmap fromBmp(byte[] file, Type type) throws GsmException {
        if (file.length < 62) throw new GsmException(GsmError.INVALIDDATA, "not a BMP");
        int off = file[10] & 0xFF;
        int h = (file[22] & 0xFF) + 256 * (file[21] & 0xFF);
        int w = (file[18] & 0xFF) + 256 * (file[17] & 0xFF);
        if ((file[28] & 0xFF) != 1 || file[30] != 0) {
            throw new GsmException(GsmError.INVALIDDATA, "only uncompressed 1-bit BMP files");
        }
        int[] max = maxSize(type);
        GsmBitmap b = new GsmBitmap(type, Math.min(w, max[0]), Math.min(h, max[1]));
        int p0 = (file[54] & 0xFF) * (file[55] & 0xFF) * (file[56] & 0xFF);
        int p1 = (file[58] & 0xFF) * (file[59] & 0xFF) * (file[60] & 0xFF);
        boolean firstWhite = p1 > p0;
        int stride = ((w + 31) / 32) * 4;
        for (int y = h - 1; y >= 0; y--) {
            int row = off + (h - 1 - y) * stride;
            for (int x = 0; x < w; x++) {
                int idx = row + x / 8;
                if (idx >= file.length) throw new GsmException(GsmError.INVALIDDATA, "short BMP");
                boolean bit = (file[idx] & (1 << (7 - (x % 8)))) != 0;
                if (x < b.width && y < b.height) b.setPoint(x, y, firstWhite ? !bit : bit);
            }
        }
        return b;
    }

    /** Writes a 1-bit BMP (black = set pixel), as Gammu's Bitmap2BMP does. */
    public byte[] toBmp() {
        int stride = ((width + 31) / 32) * 4;
        int size = 62 + stride * height;
        byte[] f = new byte[size];
        f[0] = 'B';
        f[1] = 'M';
        le32(f, 2, size);
        le32(f, 10, 62);
        le32(f, 14, 40);
        le32(f, 18, width);
        le32(f, 22, height);
        f[26] = 1;
        f[28] = 1;
        le32(f, 34, stride * height);
        le32(f, 38, 0xB13);
        le32(f, 42, 0xB13);
        le32(f, 46, 2);
        // palette: 0 = white (light LCD green would be prettier, but stay standard), 1 = black
        f[54] = (byte) 0xFF;
        f[55] = (byte) 0xFF;
        f[56] = (byte) 0xFF;
        for (int y = 0; y < height; y++) {
            int row = 62 + (height - 1 - y) * stride;
            for (int x = 0; x < width; x++)
                if (isPoint(x, y)) f[row + x / 8] |= (byte) (1 << (7 - (x % 8)));
        }
        return f;
    }

    private static void le32(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
    }
}
