package com.pocketgammu.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Port of libgammu/phone/nokia/dct4s40/6510/6510file.c: the two Series 40 file
 * systems.
 *
 * Filesystem 1 ("c:/NN" IDs) is on every Series 40 v1/v2 phone (3510i, 6100,
 * 3200, 6020...). Filesystem 2 (paths under "d:" phone memory and "a:" memory
 * card) is on phones with Gammu's F_FILES2 flag (6020, 6230, 3220...).
 */
public final class S40Files {
    public interface Progress {
        void onProgress(long done, long total);
    }

    private final Session s;
    private final List<PhoneFile> cache = new ArrayList<>();

    // reply targets (Gammu keeps these in s->Phone.Data / Priv.N6510)
    private PhoneFile fileInfo, file;
    private long fsUsed, fsFree;
    private int fileToken, parentId, fileCheckSum, fileHandle;
    private boolean filesEnd;
    private GsmError fs2error = GsmError.NONE;

    S40Files(final N6510 m, Session session) {
        this.s = session;
        Reply.Handler info1 = new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session x) {
                return replyInfo1(msg, x);
            }
        };
        Reply.Handler part = new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session x) {
                long n = be32(msg, 6);
                byte[] old = file.buffer == null ? new byte[0] : file.buffer;
                int add = (int) Math.max(0, Math.min(n, msg.length - 10));
                byte[] b = new byte[old.length + add];
                System.arraycopy(old, 0, b, 0, old.length);
                System.arraycopy(msg.buffer, 10, b, old.length, add);
                file.buffer = b;
                file.used += n;
                return GsmError.NONE;
            }
        };
        Reply.Handler crc = new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session x) {
                fileCheckSum = msg.u(6) * 256 + msg.u(7);
                x.log(String.format("File checksum from phone is %04X", fileCheckSum));
                return GsmError.NONE;
            }
        };
        Reply.Handler addHeader1 = new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session x) {
                if (msg.u(3) == 0x03) {
                    file.id = String.valueOf(msg.u(8) * 256 + msg.u(9));
                    return GsmError.NONE;
                }
                return msg.u(3) == 0x13 ? GsmError.NONE : GsmError.UNKNOWNRESPONSE;
            }
        };
        Reply.Handler status1 = new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session x) {
                if (msg.u(3) == 0x23) {
                    if (x.modelInfo != null && x.modelInfo.model.equals("6310i")) {
                        fsFree = 3 * 256 * 256 + msg.u(8) * 256 + msg.u(9) - fsUsed;
                    } else {
                        fsFree = be32(msg, 6);
                    }
                    return GsmError.NONE;
                }
                if (msg.u(3) == 0x2F) {
                    fsUsed = be32(msg, 6);
                    return GsmError.NONE;
                }
                return GsmError.UNKNOWNRESPONSE;
            }
        };
        Reply.Handler info2 = new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session x) {
                return replyInfo2(msg);
            }
        };
        m.add(0x6D, 0x03, 0x03, RequestId.ADD_FILE, addHeader1);
        m.add(0x6D, 0x03, 0x05, RequestId.ADD_FOLDER, new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session x) {
                file.id = String.valueOf(msg.u(8) * 256 + msg.u(9));
                return GsmError.NONE;
            }
        });
        m.add(0x6D, 0x03, 0x0F, RequestId.GET_FILE, part);
        m.add(0x6D, 0x03, 0x13, RequestId.ADD_FILE, addHeader1);
        m.add(0x6D, 0x03, 0x15, RequestId.GET_FILE_INFO, info1);
        m.add(0x6D, 0x03, 0x19, RequestId.SET_ATTRIB, Reply.NONE);
        m.add(0x6D, 0x03, 0x1F, RequestId.DELETE_FILE, new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session x) {
                if (msg.u(4) == 0x01) return GsmError.NONE;
                if (msg.u(4) == 0x04) return GsmError.FILENOTEXIST;
                return GsmError.UNKNOWNRESPONSE;
            }
        });
        m.add(0x6D, 0x03, 0x23, RequestId.FILE_SYSTEM_STATUS, status1);
        m.add(0x6D, 0x03, 0x2F, RequestId.GET_FILE_INFO, info1);
        m.add(0x6D, 0x03, 0x2F, RequestId.FILE_SYSTEM_STATUS, status1);
        m.add(0x6D, 0x03, 0x33, RequestId.GET_FILE_INFO, info1);
        m.add(0x6D, 0x03, 0xF0, RequestId.GET_FILE_INFO, info1);
        m.add(0x6D, 0x03, 0x41, RequestId.ADD_FILE, Reply.NONE);
        m.add(0x6D, 0x03, 0x43, RequestId.GET_CRC, crc);
        m.add(0x6D, 0x03, 0x59, RequestId.ADD_FILE, Reply.NONE);
        m.add(0x6D, 0x03, 0x5F, RequestId.GET_FILE, part);
        m.add(0x6D, 0x03, 0x63, RequestId.DELETE_FILE, new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session x) {
                switch (msg.u(4)) {
                    case 0x00: return GsmError.NONE;
                    case 0x03: return GsmError.UNKNOWN; // read only
                    case 0x06: return GsmError.FILENOTEXIST;
                    default: return GsmError.UNKNOWNRESPONSE;
                }
            }
        });
        m.add(0x6D, 0x03, 0x65, RequestId.ADD_FOLDER, new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session x) {
                switch (msg.u(4)) {
                    case 0x00: return GsmError.NONE;
                    case 0x04: return GsmError.FILEALREADYEXIST;
                    case 0x06: return GsmError.FILENOTEXIST;
                    case 0x0C: return GsmError.MEMORY;
                    default: return GsmError.UNKNOWNRESPONSE;
                }
            }
        });
        m.add(0x6D, 0x03, 0x67, RequestId.GET_CRC, crc);
        m.add(0x6D, 0x03, 0x69, RequestId.INCOMING_FRAME, info2);
        m.add(0x6D, 0x03, 0x6B, RequestId.DELETE_FOLDER, new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session x) {
                switch (msg.u(4)) {
                    case 0x00: return GsmError.NONE;
                    case 0x03: return GsmError.SHOULDBEFOLDER;
                    case 0x06: return GsmError.FILENOTEXIST;
                    case 0x0C: return GsmError.MEMORY;
                    default: return GsmError.UNKNOWNRESPONSE;
                }
            }
        });
        m.add(0x6D, 0x03, 0x6D, RequestId.GET_FILE_INFO, info2);
        m.add(0x6D, 0x03, 0x6F, RequestId.SET_ATTRIB, new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session x) {
                if (msg.u(4) == 0x00) return GsmError.NONE;
                if (msg.u(4) == 0x06) return GsmError.FILENOTEXIST;
                return GsmError.UNKNOWNRESPONSE;
            }
        });
        m.add(0x6D, 0x03, 0x73, RequestId.OPEN_FILE, new Reply.Handler() {
            @Override
            public GsmError handle(ProtocolMessage msg, Session x) {
                switch (msg.u(4)) {
                    case 0x00:
                        fileHandle = (int) be32(msg, 6);
                        return GsmError.NONE;
                    case 0x03: return GsmError.FILEALREADYEXIST;
                    case 0x06: return GsmError.FILENOTEXIST;
                    default: return GsmError.UNKNOWNRESPONSE;
                }
            }
        });
        m.add(0x6D, 0x03, 0x75, RequestId.CLOSE_FILE, Reply.NONE);
        m.add(0x6D, 0x03, 0x87, RequestId.ADD_FILE, Reply.NONE);
    }

    // ---- helpers ---------------------------------------------------------------------

    private static long be32(ProtocolMessage m, int o) {
        return ((long) m.u(o) << 24) | (m.u(o + 1) << 16) | (m.u(o + 2) << 8) | m.u(o + 3);
    }

    private static String ucs2At(ProtocolMessage m, int o) {
        StringBuilder sb = new StringBuilder();
        for (int i = o; i + 1 < m.length; i += 2) {
            int c = (m.u(i) << 8) | m.u(i + 1);
            if (c == 0) break;
            sb.append((char) c);
        }
        return sb.toString();
    }

    private boolean has(String f) {
        return s.modelInfo != null && s.modelInfo.has(f);
    }

    private static int num(String id) {
        // atoi()
        int v = 0, i = 0;
        while (i < id.length() && Character.isDigit(id.charAt(i))) v = v * 10 + (id.charAt(i++) - '0');
        return v;
    }

    private static void putName(byte[] req, int off, String name) {
        byte[] n = Hex.ucs2(name);
        System.arraycopy(n, 0, req, off, n.length);
    }

    /** Copies a filesystem-2 path and applies Gammu's drive letter mapping (a:->b:, d:->a:). */
    private static int putPath(byte[] req, int off, String path) {
        byte[] n = Hex.ucs2(path);
        System.arraycopy(n, 0, req, off, n.length);
        if (n.length >= 2) {
            if (req[off + 1] == 'a' || req[off + 1] == 'A') req[off + 1] = 'b';
            if (req[off + 1] == 'd' || req[off + 1] == 'D') req[off + 1] = 'a';
        }
        return n.length;
    }

    private static byte[] req7110(int... tail) {
        byte[] b = new byte[3 + tail.length];
        System.arraycopy(NokiaCommon.FRAME_HEADER_7110, 0, b, 0, 3);
        for (int i = 0; i < tail.length; i++) b[3 + i] = (byte) tail[i];
        return b;
    }

    /** N6510_FindFileCheckSum12. */
    public static int checksum(byte[] b, int len) {
        int acc = 0xFFFF, accx = 0;
        for (int p = 0; p < len; p++) {
            accx = (accx & 0xFFFF00FF) | (acc & 0xFF00);
            acc = (acc & 0xFFFF00FF) | ((b[p] & 0xFF) << 8);
            for (int i = 0; i < 8; i++) {
                acc <<= 1;
                if ((acc & 0x10000) != 0) acc ^= 0x1021;
                if ((accx & 0x80000000) != 0) acc ^= 0x1021;
                accx <<= 1;
            }
        }
        return acc & 0xFFFF;
    }

    private static int typeMajor(PhoneFile.Type t) {
        switch (t) {
            case JPG: case BMP: case PNG: case GIF: case WBMP: return 0x02;
            case AMR: case MIDI: case NRT: return 0x04;
            case VIDEO_3GP: return 0x08;
            case JAR: return 0x10;
            case MMS: return 0x00;
            default: return 0x01;
        }
    }

    private static int typeMinor(PhoneFile.Type t) {
        switch (t) {
            case JPG: return 0x01;
            case BMP: return 0x02;
            case PNG: return 0x03;
            case GIF: return 0x05;
            case WBMP: return 0x09;
            case AMR: return 0x01;
            case MIDI: return 0x05;
            case NRT: return 0x06;
            case VIDEO_3GP: return 0x05;
            case JAR: return 0x01;
            case MMS: return 0x01;
            default: return 0x05;
        }
    }

    private static void putType(byte[] h, PhoneFile f) {
        if (f.type == PhoneFile.Type.MMS) {
            byte[] mms = Hex.bytes(0x07, 0xD3, 0x06, 0x01, 0x12, 0x13, 0x29);
            System.arraycopy(mms, 0, h, 214, 7);
            h[233] = 0x01;
        } else {
            h[231] = (byte) typeMajor(f.type);
            h[233] = (byte) typeMinor(f.type);
        }
    }

    // ---- filesystem 1 ------------------------------------------------------------------

    private GsmError replyInfo1(ProtocolMessage msg, Session x) {
        PhoneFile f = fileInfo;
        switch (msg.u(3)) {
            case 0x15: {
                f.name = ucs2At(msg, 10);
                if (msg.length == 14) return GsmError.FILENOTEXIST;
                if (f.name.startsWith("GMSTemp")) return GsmError.EMPTY;
                if (f.name.isEmpty()) return GsmError.UNKNOWN;
                int i = msg.u(8) * 256 + msg.u(9);
                f.folder = msg.u(i - 5) == 0x00 && msg.u(i - 3) == 0x02;
                f.protect = msg.u(i + 2) == 0x01;
                f.readOnly = msg.u(i + 4) == 0x01;
                f.hidden = msg.u(i + 5) == 0x01;
                f.system = msg.u(i + 6) == 0x01;
                int o = i - 22;
                int year = msg.u(o) * 256 + msg.u(o + 1);
                if (year > 3000) year = msg.u(o + 1) * 256 + msg.u(o);
                f.modified = (year == 0 || year == 0xFFFF) ? null
                        : new int[] {year, msg.u(o + 2), msg.u(o + 3), msg.u(o + 4), msg.u(o + 5), msg.u(o + 6)};
                fileToken = msg.u(i - 10) * 256 + msg.u(i - 9);
                parentId = msg.u(i) * 256 + msg.u(i + 1);
                f.type = PhoneFile.Type.OTHER;
                if (msg.length > 240) {
                    int a = msg.u(227), b = msg.u(229);
                    if (a == 0x02 && b == 0x01) f.type = PhoneFile.Type.JPG;
                    else if (a == 0x02 && (b == 0x02 || b == 0x07)) f.type = PhoneFile.Type.BMP;
                    else if (a == 0x02 && b == 0x03) f.type = PhoneFile.Type.PNG;
                    else if (a == 0x02 && b == 0x05) f.type = PhoneFile.Type.GIF;
                    else if (a == 0x02 && b == 0x09) f.type = PhoneFile.Type.WBMP;
                    else if (a == 0x04 && b == 0x01) f.type = PhoneFile.Type.AMR;
                    else if (a == 0x04 && b == 0x02) f.type = PhoneFile.Type.MIDI;
                    else if (a == 0x08 && b == 0x05) f.type = PhoneFile.Type.VIDEO_3GP;
                    else if (a == 0x10 && b == 0x01) f.type = PhoneFile.Type.JAR;
                    else if (a == 0x00 && b == 0x01) f.type = PhoneFile.Type.MMS;
                }
                return GsmError.NONE;
            }
            case 0x2F:
                f.used = be32(msg, 6);
                return GsmError.NONE;
            case 0x33: {
                int n = msg.u(8) * 256 + msg.u(9);
                if (x.requestIdIs(RequestId.GET_FILE_INFO)) {
                    List<PhoneFile> kids = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        PhoneFile k = new PhoneFile(String.valueOf(msg.u(13 + i * 4 - 1) * 256 + msg.u(13 + i * 4)));
                        k.level = f.level + 1;
                        kids.add(k);
                    }
                    cache.addAll(0, kids);
                }
                if (n != 0) f.folder = true;
                return GsmError.NONE;
            }
            case 0xF0:
                return GsmError.NOTSUPPORTED;
            default:
                return GsmError.UNKNOWNRESPONSE;
        }
    }

    /** N6510_GetFileFolderInfo1. */
    private GsmError info1(PhoneFile f, boolean full) throws IOException {
        fileInfo = f;
        int id = num(f.id);
        byte[] req = req7110(0x14, 0x01, 0x00, 0x00, 0x01, id / 256, id % 256);
        s.log("Getting info for file in filesystem");
        GsmError e = s.waitFor(req, 10, 0x6D, 4, RequestId.GET_FILE_INFO);
        if (e != GsmError.NONE) return e;
        if (full) {
            req[3] = 0x32;
            req[4] = 0x00;
            s.log("Getting subfolders for filesystem");
            e = s.waitFor(req, 10, 0x6D, 4, RequestId.GET_FILE_INFO);
            if (e != GsmError.NONE) return e;
        }
        if (!f.folder) {
            req[3] = 0x2E;
            req[4] = 0x01;
            s.log("Getting used memory for file in filesystem");
            return s.waitFor(req, 10, 0x6D, 4, RequestId.GET_FILE_INFO);
        }
        return e;
    }

    private GsmError getCrc1(String id) throws IOException {
        int n = num(id);
        s.log("Getting CRC for file in filesystem");
        return s.waitFor(req7110(0x42, 0x00, 0x00, 0x00, 0x01, n / 256, n % 256), 10, 0x6D, 4, RequestId.GET_CRC);
    }

    private GsmError setReadOnly1(String id, boolean enable) throws IOException {
        int n = num(id);
        s.log("Setting readonly attribute");
        return s.waitFor(req7110(0x18, enable ? 0x00 : 0x06, 0x00, 0x00, 0x01, n / 256, n % 256), 10, 0x6D, 4,
                RequestId.SET_ATTRIB);
    }

    /** N6510_GetFolderListing1: lists one folder (IDs without the "c:/" prefix). */
    private List<PhoneFile> listing1(PhoneFile folder) throws IOException {
        cache.clear();
        GsmException.check(info1(folder, true));
        if (!folder.folder) throw new GsmException(GsmError.SHOULDBEFOLDER);
        List<PhoneFile> out = new ArrayList<>();
        while (!cache.isEmpty()) {
            PhoneFile f = cache.get(0).copy();
            GsmError e = info1(f, false);
            cache.remove(0);
            if (e == GsmError.EMPTY) continue; // GMSTemp entries; Gammu stops listing here
            if (e == GsmError.FILENOTEXIST) continue; // stale IDs, like GetNextFileFolder1
            GsmException.check(e);
            out.add(f);
        }
        return out;
    }

    private GsmError download1(PhoneFile f, Progress p) throws IOException {
        GsmError e = info1(f, false);
        if (e != GsmError.NONE) return e;
        if (f.folder) return GsmError.SHOULDBEFILE;
        long size = f.used;
        f.used = 0;
        f.buffer = new byte[0];
        file = f;
        int id = num(f.id);
        while (true) {
            long old = f.used;
            byte[] req = req7110(0x0E, 0x00, 0x00, 0x00, 0x01, id / 256, id % 256,
                    (int) (old >>> 24), (int) (old >>> 16), (int) (old >>> 8), (int) old, 0x00, 0x00, 0x03, 0xE8);
            s.log("Getting file part from filesystem");
            e = s.waitFor(req, 18, 0x6D, 4, RequestId.GET_FILE);
            if (e != GsmError.NONE) return e;
            if (p != null) p.onProgress(f.used, size);
            // PocketGammu: Gammu stops at the first part shorter than 1000 bytes. Some firmware
            // sends smaller parts, so when the size is known, read until it is reached.
            boolean done = f.used - old == 0
                    || (size > 0 && f.used >= size)
                    || (size == 0 && f.used - old != 1000);
            if (done) {
                e = getCrc1(f.id);
                if (e != GsmError.NONE) {
                    // PocketGammu: some firmware doesn't answer the checksum request; if every
                    // byte announced by the file info arrived, keep the file.
                    if (size > 0 && f.used == size && e != GsmError.DEVICEREADERROR && e != GsmError.DEVICEWRITEERROR) {
                        s.log("[Phone gave no checksum (" + e + "); all " + size + " bytes arrived, keeping the file]");
                        return GsmError.NONE;
                    }
                    return e;
                }
                return checksum(f.buffer, (int) f.used) != fileCheckSum ? GsmError.WRONGCRC : GsmError.NONE;
            }
        }
    }

    /** N6510_SearchForFileName1: is there an entry called f.name in folder f.id? */
    private GsmError searchName1(PhoneFile f) throws IOException {
        List<PhoneFile> backup = new ArrayList<>(cache);
        cache.clear();
        PhoneFile dir = new PhoneFile(f.id);
        dir.level = 1;
        // Gammu's cache holds the folder itself; its children are inserted before it,
        // so the folder is checked by name too.
        cache.add(dir);
        GsmError e = info1(dir, true);
        List<PhoneFile> kids = new ArrayList<>(cache);
        cache.clear();
        cache.addAll(backup);
        if (e != GsmError.NONE) return e;
        for (PhoneFile k : kids) {
            e = info1(k, false);
            if (e == GsmError.EMPTY) continue;
            if (e != GsmError.NONE) return e;
            if (k.name.equalsIgnoreCase(f.name)) {
                f.folder = k.folder;
                return GsmError.NONE;
            }
        }
        return GsmError.EMPTY;
    }

    private byte[] header1(int cmd, String folderId, PhoneFile f) {
        byte[] h = new byte[246];
        byte[] start = req7110(cmd, 0x00, 0x00, 0x00, 0x01, 0x00, 0x0C, 0x00, 0x00, 0x00, 0xE8);
        System.arraycopy(start, 0, h, 0, start.length);
        int id = num(folderId);
        h[8] = (byte) (id / 256);
        h[9] = (byte) (id % 256);
        putName(h, 14, f.name);
        return h;
    }

    /** N6510_AddFilePart1, all parts. f.id is the target folder; on return it is the new file. */
    private GsmError upload1(PhoneFile f, Progress p) throws IOException {
        file = f;
        GsmError e = searchName1(f);
        if (e == GsmError.NONE) return GsmError.FILEALREADYEXIST;
        if (e != GsmError.EMPTY) return e;
        long used = f.used;
        byte[] h = header1(0x02, f.id, f);
        h[222] = (byte) (used >>> 24);
        h[223] = (byte) (used >>> 16);
        h[224] = (byte) (used >>> 8);
        h[225] = (byte) used;
        putType(h, f);
        h[235] = 0x01;
        int folder = num(f.id);
        h[236] = (byte) (folder / 256);
        h[237] = (byte) (folder % 256);
        if (f.protect) h[238] = 0x01;
        if (f.hidden) h[241] = 0x01;
        if (f.system) h[242] = 0x01;
        s.log("Adding file header");
        e = s.waitFor(h, 246, 0x6D, 4, RequestId.ADD_FILE);
        if (e != GsmError.NONE) return e;
        int pos = 0;
        while (true) {
            int j = (int) Math.min(1000, used - pos);
            int id = num(f.id);
            byte[] add = new byte[14 + j];
            byte[] a0 = req7110(0x40, 0x00, 0x00, 0x00, 0x01, id / 256, id % 256, 0x00, 0x00, j / 256, j % 256);
            System.arraycopy(a0, 0, add, 0, a0.length);
            System.arraycopy(f.buffer, pos, add, 14, j);
            s.log("Adding file part " + pos + " " + j);
            e = s.waitFor(add, 14 + j, 0x6D, 4, RequestId.ADD_FILE);
            if (e != GsmError.NONE) return e;
            pos += j;
            if (p != null) p.onProgress(pos, used);
            if (j < 1000) break;
        }
        int id = num(f.id);
        s.log("Frame for ending adding file");
        e = s.waitFor(req7110(0x40, 0x00, 0x00, 0x00, 0x01, id / 256, id % 256, 0x00, 0x00, 0x00, 0x00), 14, 0x6D, 4,
                RequestId.ADD_FILE);
        if (e != GsmError.NONE) return e;
        PhoneFile f2 = new PhoneFile(f.id);
        e = info1(f2, false);
        if (e != GsmError.NONE) return e;
        if (f.modified != null) {
            byte[] u = header1(0x12, f.id, f);
            u[4] = 0x01;
            u[214] = (byte) (f.modified[0] / 256);
            u[215] = (byte) (f.modified[0] % 256);
            u[216] = (byte) f.modified[1];
            u[217] = (byte) f.modified[2];
            u[218] = (byte) f.modified[3];
            u[219] = (byte) f.modified[4];
            if (used < 1000) {
                // Gammu reuses the first header's buffer when the whole file fits one part.
                u[222] = (byte) (used >>> 24);
                u[223] = (byte) (used >>> 16);
                if (f.protect) u[238] = 0x01;
                if (f.hidden) u[241] = 0x01;
                if (f.system) u[242] = 0x01;
            }
            u[224] = (byte) (f2.used / 256);
            u[225] = (byte) (f2.used % 256);
            u[226] = (byte) (fileToken / 256);
            u[227] = (byte) (fileToken % 256);
            putType(u, f);
            u[235] = 0x01;
            u[236] = (byte) (parentId / 256);
            u[237] = (byte) (parentId % 256);
            s.log("Adding file header");
            e = s.waitFor(u, 246, 0x6D, 4, RequestId.ADD_FILE);
            if (e != GsmError.NONE) return e;
        }
        if (f.readOnly) {
            e = setReadOnly1(f.id, true);
            if (e != GsmError.NONE) return e;
        }
        e = getCrc1(f.id);
        if (e != GsmError.NONE) return e;
        return checksum(f.buffer, (int) used) != fileCheckSum ? GsmError.WRONGCRC : GsmError.NONE;
    }

    private GsmError delete1(String id, boolean isFile) throws IOException {
        cache.clear();
        PhoneFile f = new PhoneFile(id);
        GsmError e = info1(f, true);
        if (e != GsmError.NONE) return e;
        if (isFile) {
            if (f.folder) return GsmError.SHOULDBEFILE;
        } else {
            if (!f.folder) return GsmError.SHOULDBEFOLDER;
            if (!cache.isEmpty()) return GsmError.FOLDERNOTEMPTY;
        }
        e = setReadOnly1(id, false);
        if (e != GsmError.NONE) return e;
        int n = num(id);
        return s.waitFor(req7110(0x1E, 0x00, 0x00, 0x00, 0x01, n / 256, n % 256), 10, 0x6D, 4, RequestId.DELETE_FILE);
    }

    private GsmError addFolder1(PhoneFile f) throws IOException {
        PhoneFile f2 = new PhoneFile(f.id);
        GsmError e = info1(f2, false);
        if (e != GsmError.NONE) return e;
        if (!f2.folder) return GsmError.SHOULDBEFOLDER;
        byte[] h = header1(0x04, f.id, f);
        h[233] = 0x02;
        h[235] = 0x01;
        int n = num(f.id);
        h[236] = (byte) (n / 256);
        h[237] = (byte) (n % 256);
        file = f;
        s.log("Adding folder");
        e = s.waitFor(h, 246, 0x6D, 4, RequestId.ADD_FOLDER);
        if (e != GsmError.NONE) return e;
        if (f.id.equals("0")) return GsmError.FILEALREADYEXIST;
        if (f.readOnly) return setReadOnly1(f.id, true);
        return GsmError.NONE;
    }

    // ---- filesystem 2 ------------------------------------------------------------------

    private GsmError replyInfo2(ProtocolMessage msg) {
        int t = msg.u(3);
        switch (msg.u(4)) {
            case 0x0C:
                fs2error = GsmError.MEMORY;
                filesEnd = true;
                return GsmError.MEMORY;
            case 0x00:
            case 0x0D: {
                switch (msg.u(5)) {
                    case 0x00: break;
                    case 0x06: return GsmError.FILENOTEXIST;
                    case 0x0C: return GsmError.MEMORY;
                    default: return GsmError.UNKNOWNRESPONSE;
                }
                PhoneFile f;
                if (t == 0x69) {
                    String name = ucs2At(msg, 32);
                    if (name.isEmpty()) return GsmError.NONE;
                    f = new PhoneFile(fileInfo.id + "/" + name);
                    f.name = name;
                    f.level = (cache.isEmpty() ? 0 : cache.get(0).level) + 1;
                    if (cache.isEmpty()) cache.add(new PhoneFile());
                    cache.add(1, f);
                } else {
                    f = fileInfo;
                }
                int attr = msg.u(29);
                f.folder = (attr & 0x10) == 0x10;
                if (!f.folder) f.used = be32(msg, 10);
                f.readOnly = (attr & 1) == 1;
                f.hidden = (attr & 2) == 2;
                f.system = (attr & 4) == 4;
                f.protect = (attr & 0x40) == 0x40;
                int year = msg.u(14) * 256 + msg.u(15);
                if (year > 3000) year = msg.u(15) * 256 + msg.u(14);
                f.modified = (year == 0 || year == 0xFFFF) ? null
                        : new int[] {year, msg.u(16), msg.u(17), msg.u(18), msg.u(19), msg.u(20)};
                if (t == 0x69 && msg.u(4) == 0) filesEnd = true;
                return GsmError.NONE;
            }
            case 0x06:
                fs2error = GsmError.FILENOTEXIST;
                filesEnd = true;
                return GsmError.FILENOTEXIST;
            case 0x0E:
                filesEnd = true;
                return GsmError.NONE;
            default:
                return GsmError.UNKNOWNRESPONSE;
        }
    }

    private GsmError info2(PhoneFile f) throws IOException {
        fileInfo = f;
        byte[] req = new byte[8 + f.id.length() * 2];
        System.arraycopy(req7110(0x6C), 0, req, 0, 4);
        int len = f.id.length() * 2 + 2;
        req[4] = (byte) (len / 256);
        req[5] = (byte) (len % 256);
        putPath(req, 6, f.id);
        s.log("Getting info for file in filesystem");
        return s.waitFor(req, req.length, 0x6D, 4, RequestId.GET_FILE_INFO);
    }

    private GsmError open2(String name, boolean create) throws IOException {
        byte[] req = new byte[10 + name.length() * 2];
        byte[] h = NokiaCommon.req(0x72, create ? 0x11 : 0x00, 0x02, 0, 0);
        System.arraycopy(h, 0, req, 0, h.length);
        int len = name.length() * 2 + 2;
        req[6] = (byte) (len / 256);
        req[7] = (byte) (len % 256);
        putPath(req, 8, name);
        s.log("Opening file");
        return s.waitFor(req, req.length, 0x6D, 4, RequestId.OPEN_FILE);
    }

    private GsmError close2(int handle) throws IOException {
        s.log("Closing file");
        return s.waitFor(NokiaCommon.req(0x74, 0, 0, handle >>> 24, handle >>> 16, handle >>> 8, handle), 10, 0x6D, 4,
                RequestId.CLOSE_FILE);
    }

    private GsmError crc2(int handle) throws IOException {
        return s.waitFor(req7110(0x66, 0, 0, handle >>> 24, handle >>> 16, handle >>> 8, handle), 10, 0x6D, 8,
                RequestId.GET_CRC);
    }

    /** N6510_PrivGetFolderListing2: entries arrive as unsolicited 0x69 frames. */
    private void privListing2(PhoneFile f) throws IOException {
        byte[] req = new byte[12 + f.id.length() * 2];
        System.arraycopy(NokiaCommon.req(0x68), 0, req, 0, 4);
        int len = f.id.length() * 2 + 6;
        req[4] = (byte) (len / 256);
        req[5] = (byte) (len % 256);
        int pos = 6 + putPath(req, 6, f.id);
        req[pos + 1] = '/';
        req[pos + 3] = '*';
        s.log("Getting folder info " + f.id);
        fs2error = GsmError.NONE;
        fileInfo = f;
        filesEnd = false;
        s.protocol().writeMessage(req, req.length, 0x6D);
        int idle = 0;
        while (!filesEnd) {
            Hex.sleep(1);
            // GSM_ReadDevice(s, TRUE) waits up to a second for data
            long end = System.currentTimeMillis() + 1000;
            int n = 0;
            while (n == 0 && System.currentTimeMillis() < end && !filesEnd) n = s.readDevice();
            if (n <= 0) idle++;
            else idle = 0;
            s.checkAbort();
            if (idle == 3) {
                s.log("Connection broken or WELL KNOWN phone firmware problem (which makes, that not all files are reported)");
                fs2error = GsmError.FOLDERPART;
                return;
            }
        }
    }

    private static boolean isRoot2(String id) {
        return id.equalsIgnoreCase("a:") || id.equalsIgnoreCase("a:\\") || id.equalsIgnoreCase("d:")
                || id.equalsIgnoreCase("d:\\");
    }

    /** N6510_GetFolderListing2 (start + every following call). */
    private List<PhoneFile> listing2(PhoneFile f, boolean[] partial) throws IOException {
        if (!isRoot2(f.id)) {
            GsmException.check(info2(f));
            if (!f.folder) throw new GsmException(GsmError.SHOULDBEFOLDER);
        }
        cache.clear();
        cache.add(new PhoneFile()); // FilesLocationsUsed = 1
        privListing2(f);
        cache.remove(0);
        List<PhoneFile> out = new ArrayList<>(cache);
        cache.clear();
        if (partial != null) partial[0] = fs2error == GsmError.FOLDERPART;
        return out;
    }

    private GsmError download2(PhoneFile f, Progress p) throws IOException {
        GsmError e = info2(f);
        if (e != GsmError.NONE) return e;
        if (f.folder) return GsmError.SHOULDBEFILE;
        e = open2(f.id, false);
        if (e != GsmError.NONE) return e;
        int handle = fileHandle;
        int slash = Math.max(f.id.lastIndexOf('/'), f.id.lastIndexOf('\\'));
        f.name = slash > 0 ? f.id.substring(slash + 1) : f.id;
        long size = f.used;
        f.used = 0;
        f.buffer = new byte[0];
        file = f;
        while (true) {
            long old = f.used;
            byte[] req = req7110(0x5E, 0, 0, handle >>> 24, handle >>> 16, handle >>> 8, handle,
                    (int) (old >>> 24), (int) (old >>> 16), (int) (old >>> 8), (int) old,
                    0x00, 0x00, 0x03, 0xE8, 0x00, 0x00, 0x03, 0xE8);
            s.log("Getting file part from filesystem");
            e = s.waitFor(req, 22, 0x6D, 4, RequestId.GET_FILE);
            if (e != GsmError.NONE) return e;
            if (p != null) p.onProgress(f.used, size);
            if (f.used - old != 1000) {
                e = crc2(handle);
                if (e != GsmError.NONE) return e;
                e = close2(handle);
                if (e != GsmError.NONE) return e;
                return checksum(f.buffer, (int) f.used) != fileCheckSum ? GsmError.WRONGCRC : GsmError.NONE;
            }
        }
    }

    /** N6510_SetFileAttributes2: sets want's attributes on the file want.id. */
    private GsmError setAttributes2(PhoneFile want) throws IOException {
        PhoneFile f = new PhoneFile(want.id);
        GsmError e = info2(f);
        if (e != GsmError.NONE) return e;
        if (f.folder) return GsmError.SHOULDBEFILE;
        byte[] h = new byte[12 + want.id.length() * 2];
        System.arraycopy(req7110(0x6E), 0, h, 0, 4);
        h[4] = (byte) ((want.id.length() + 1) / 256);
        h[5] = (byte) ((want.id.length() + 1) % 256);
        int a = 0;
        if (want.readOnly) a += 1;
        if (want.hidden) a += 2;
        if (want.system) a += 4;
        if (want.protect) a += 0x40;
        h[9] = (byte) a;
        putPath(h, 10, want.id);
        e = s.waitFor(h, h.length, 0x6D, 4, RequestId.SET_ATTRIB);
        if (e != GsmError.NONE) return e;
        e = info2(f);
        if (e != GsmError.NONE) return e;
        if (want.system != f.system || want.readOnly != f.readOnly || want.hidden != f.hidden) {
            return GsmError.NOTSUPPORTED;
        }
        return GsmError.NONE;
    }

    /** N6510_AddFilePart2, all parts. f.id is the target folder; on return it is the new file's path. */
    private GsmError upload2(PhoneFile f, Progress p) throws IOException {
        file = f;
        f.id = f.id + "/" + f.name;
        PhoneFile probe = new PhoneFile(f.id);
        GsmError e = info2(probe);
        if (e == GsmError.NONE) return GsmError.FILEALREADYEXIST;
        if (e != GsmError.FILENOTEXIST) return e;
        e = open2(f.id, true);
        if (e != GsmError.NONE) return e;
        int handle = fileHandle;
        long used = f.used;
        int pos = 0;
        while (true) {
            int j = (int) Math.min(2000, used - pos);
            byte[] req = new byte[14 + j];
            byte[] h = req7110(0x58, 0, 0, handle >>> 24, handle >>> 16, handle >>> 8, handle, 0, 0, j / 256, j % 256);
            System.arraycopy(h, 0, req, 0, h.length);
            System.arraycopy(f.buffer, pos, req, 14, j);
            s.log("Adding file part " + pos + " " + j);
            e = s.waitFor(req, 14 + j, 0x6D, 4, RequestId.ADD_FILE);
            if (e != GsmError.NONE) return e;
            pos += j;
            if (p != null) p.onProgress(pos, used);
            if (j < 2000) break;
        }
        e = close2(handle);
        if (e != GsmError.NONE) return e;
        int[] d = f.modified == null ? new int[6] : f.modified;
        byte[] h = new byte[16 + f.id.length() * 2];
        System.arraycopy(req7110(0x86), 0, h, 0, 4);
        h[4] = (byte) ((f.id.length() + 1) / 256);
        h[5] = (byte) ((f.id.length() + 1) % 256);
        h[6] = (byte) (d[0] / 256);
        h[7] = (byte) (d[0] % 256);
        h[8] = (byte) d[1];
        h[9] = (byte) d[2];
        h[10] = 0;
        h[11] = (byte) d[3];
        h[12] = (byte) d[4];
        h[13] = (byte) d[5];
        putPath(h, 14, f.id);
        s.log("Setting file date");
        e = s.waitFor(h, h.length, 0x6D, 4, RequestId.ADD_FILE);
        if (e != GsmError.NONE) return e;
        return setAttributes2(f);
    }

    private GsmError deleteFile2(String id) throws IOException {
        PhoneFile clear = new PhoneFile(id);
        GsmError e = setAttributes2(clear);
        if (e != GsmError.NONE) return e;
        byte[] req = new byte[8 + id.length() * 2];
        System.arraycopy(req7110(0x62), 0, req, 0, 4);
        int len = id.length() * 2 + 2;
        req[4] = (byte) (len / 256);
        req[5] = (byte) (len % 256);
        putPath(req, 6, id);
        s.log("Deleting file");
        return s.waitFor(req, req.length, 0x6D, 4, RequestId.DELETE_FILE);
    }

    private GsmError addFolder2(PhoneFile f) throws IOException {
        byte[] req = new byte[12 + (f.id.length() + f.name.length()) * 2];
        System.arraycopy(req7110(0x64), 0, req, 0, 4);
        int len = f.id.length() * 2 + 2;
        byte[] idb = Hex.ucs2(f.id);
        System.arraycopy(idb, 0, req, 6, idb.length);
        int pos = 6 + idb.length;
        if (!f.id.endsWith("\\") && !f.id.endsWith("/")) {
            req[pos++] = 0;
            req[pos++] = '/';
            len += 2;
        }
        byte[] nb = Hex.ucs2(f.name);
        System.arraycopy(nb, 0, req, pos, nb.length);
        if (nb.length >= 2) {
            if (req[pos + 1] == 'a' || req[pos + 1] == 'A') req[pos + 1] = 'b';
            if (req[pos + 1] == 'd' || req[pos + 1] == 'D') req[pos + 1] = 'a';
        }
        pos += nb.length;
        len += nb.length;
        pos += 2;
        req[4] = (byte) (len / 256);
        req[5] = (byte) (len % 256);
        s.log("Adding folder");
        GsmError e = s.waitFor(req, pos, 0x6D, 4, RequestId.ADD_FOLDER);
        if (e == GsmError.NONE) {
            StringBuilder sb = new StringBuilder();
            for (int i = 6; i + 1 < pos; i += 2) {
                int c = ((req[i] & 0xFF) << 8) | (req[i + 1] & 0xFF);
                if (c == 0) break;
                sb.append((char) c);
            }
            f.id = sb.toString();
        }
        return e;
    }

    private GsmError deleteFolder2(String id) throws IOException {
        PhoneFile f = new PhoneFile(id);
        List<PhoneFile> kids;
        try {
            kids = listing2(f, null);
        } catch (GsmException ex) {
            return ex.error;
        }
        if (!kids.isEmpty()) return GsmError.FOLDERNOTEMPTY;
        byte[] req = new byte[8 + id.length() * 2];
        System.arraycopy(req7110(0x6A), 0, req, 0, 4);
        int len = id.length() * 2 + 2;
        req[4] = (byte) (len / 256);
        req[5] = (byte) (len % 256);
        putPath(req, 6, id);
        s.log("Deleting folder");
        return s.waitFor(req, req.length, 0x6D, 4, RequestId.DELETE_FOLDER);
    }

    // ---- public API: the N6510_* dispatchers --------------------------------------------

    private void checkFs() throws GsmException {
        if (has("NOFILESYSTEM")) throw new GsmException(GsmError.NOTSUPPORTED, "this phone has no file system");
    }

    private boolean isFs1(String id) throws GsmException {
        if (id.startsWith("c") || id.startsWith("C")) {
            if (has("SERIES40_30") || has("NOFILE1")) throw new GsmException(GsmError.NOTSUPPORTED);
            return true;
        }
        if (!has("FILES2")) throw new GsmException(GsmError.NOTSUPPORTED, "this phone has only the c: file system");
        return false;
    }

    private static String fs1Id(String id) {
        return id.length() > 3 ? id.substring(3) : "";
    }

    public boolean hasFiles2() {
        return has("FILES2");
    }

    /** N6510_GetNextRootFolder, all of them. */
    public List<PhoneFile> roots() throws IOException {
        checkFs();
        List<PhoneFile> out = new ArrayList<>();
        if (has("SERIES40_30") || has("NOFILE1")) {
            PhoneFile d = new PhoneFile("d:");
            d.name = "D (Permanent_memory 2)";
            d.folder = true;
            out.add(d);
            addCard(out);
            return out;
        }
        PhoneFile f2 = new PhoneFile("1");
        f2.level = 1;
        GsmException.check(info1(f2, false));
        PhoneFile c = f2.copy();
        c.name = "C (" + f2.name + ")";
        c.id = "c:\\1";
        c.folder = true;
        out.add(c);
        if (has("FILES2")) {
            PhoneFile d = new PhoneFile("d:");
            d.name = "D (Permanent_memory 2)";
            d.folder = true;
            out.add(d);
            addCard(out);
        }
        return out;
    }

    private void addCard(List<PhoneFile> out) throws IOException {
        PhoneFile a = new PhoneFile("a:");
        try {
            listing2(a, null);
        } catch (GsmException e) {
            return;
        }
        a = new PhoneFile("a:");
        a.name = "A (Memory card)";
        a.folder = true;
        out.add(a);
    }

    /** N6510_GetFolderListing until ERR_EMPTY. partial[0] reports ERR_FOLDERPART. */
    public List<PhoneFile> list(String id, boolean[] partial) throws IOException {
        checkFs();
        PhoneFile f = new PhoneFile(id);
        if (isFs1(id)) {
            f.id = fs1Id(id);
            List<PhoneFile> l = listing1(f);
            for (PhoneFile x : l) x.id = "c:/" + x.id;
            return l;
        }
        return listing2(f, partial);
    }

    /** GSM_GetFilePart until done. Returns the file with its contents in buffer. */
    public PhoneFile download(String id, Progress p) throws IOException {
        checkFs();
        PhoneFile f = new PhoneFile(id);
        GsmError e;
        if (isFs1(id)) {
            f.id = fs1Id(id);
            e = download1(f, p);
            f.id = id;
        } else {
            e = download2(f, p);
        }
        if (e == GsmError.WRONGCRC) {
            // Like gammu --getfiles: warn and keep what was read.
            s.log("File checksum calculated by phone doesn't match with value calculated by Gammu.");
            return f;
        }
        GsmException.check(e);
        return f;
    }

    /** GSM_AddFilePart until done. Returns the new file's ID. */
    public String upload(String folderId, String name, byte[] data, int[] modified, Progress p) throws IOException {
        checkFs();
        PhoneFile f = new PhoneFile(folderId);
        f.name = name;
        f.buffer = data;
        f.used = data.length;
        f.type = PhoneFile.identify(data);
        f.modified = modified;
        GsmError e;
        if (isFs1(folderId)) {
            f.id = fs1Id(folderId);
            e = upload1(f, p);
            f.id = "c:/" + f.id;
        } else {
            e = upload2(f, p);
        }
        if (e == GsmError.WRONGCRC) {
            s.log("File checksum calculated by phone doesn't match with value calculated by Gammu.");
        }
        GsmException.check(e);
        return f.id;
    }

    public String addFolder(String parentId, String name) throws IOException {
        checkFs();
        PhoneFile f = new PhoneFile(parentId);
        f.name = name;
        if (isFs1(parentId)) {
            f.id = fs1Id(parentId);
            GsmException.check(addFolder1(f));
            return "c:/" + f.id;
        }
        GsmException.check(addFolder2(f));
        return f.id;
    }

    public void deleteFile(String id) throws IOException {
        checkFs();
        if (isFs1(id)) GsmException.check(delete1(fs1Id(id), true));
        else GsmException.check(deleteFile2(id));
    }

    public void deleteFolder(String id) throws IOException {
        checkFs();
        if (isFs1(id)) GsmException.check(delete1(fs1Id(id), false));
        else GsmException.check(deleteFolder2(id));
    }

    /** N6510_GetFileSystemStatus: {used, free} bytes; filesystem 1 phones only. */
    public long[] status() throws IOException {
        checkFs();
        if (has("FILES2") || has("SERIES40_30") || has("NOFILE1")) {
            throw new GsmException(GsmError.NOTSUPPORTED, "Gammu reads free space only on filesystem 1 phones");
        }
        fsUsed = fsFree = 0;
        byte[] req = req7110(0x2E, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01);
        s.log("Getting used/total memory in filesystem");
        GsmException.check(s.waitFor(req, 10, 0x6D, 4, RequestId.FILE_SYSTEM_STATUS));
        req[3] = 0x22;
        s.log("Getting free memory in filesystem");
        GsmException.check(s.waitFor(req, 10, 0x6D, 4, RequestId.FILE_SYSTEM_STATUS));
        return new long[] {fsUsed, fsFree};
    }
}
