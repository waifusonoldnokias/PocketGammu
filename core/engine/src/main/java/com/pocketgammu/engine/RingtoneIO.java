package com.pocketgammu.engine;

import java.util.Arrays;

/**
 * Ringtone file readers from libgammu/service/gsmring.c: GSM_ReadRingtoneFile's
 * format detection, loadott / GSM_DecodeNokiaRTTLRingtone (Smart Messaging
 * .ott), loadcommunicator, loadbin and loadre (Nokia binary .nrt and the
 * DCT-3 "re" dumps) with Binary2RTTL, plus RTTTL text through Ringtone.
 * MIDI files are handled by {@link Midi}.
 */
public final class RingtoneIO {
    private RingtoneIO() {}

    public enum Kind { RTTTL, OTT, COMMUNICATOR, NOKIA_BINARY, NOKIA_RE, MIDI, MMF, UNKNOWN }

    /** GSM_ReadRingtoneFile's header checks. */
    public static Kind detect(byte[] b) {
        if (b.length >= 4) {
            if (b[0] == 0 && b[1] == 0 && b[2] == 0x0C && b[3] == 0x01) return Kind.NOKIA_BINARY;
            if (b[0] == 0 && b[1] == 0 && b[2] == 0) return Kind.NOKIA_RE;
            if (b[0] == 'M' && b[1] == 'T' && b[2] == 'h' && b[3] == 'd') return Kind.MIDI;
            if (b[0] == 'M' && b[1] == 'M' && b[2] == 'M' && b[3] == 'D') return Kind.MMF;
            if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F') return Kind.UNKNOWN;
        }
        if (b.length >= 2 && b[0] == 0x02 && b[1] == 0x4A) return Kind.OTT;
        if (b.length >= 2 && (b[0] & 0xFF) == 0xC7 && b[1] == 0x45) return Kind.COMMUNICATOR;
        return Kind.RTTTL;
    }

    /** Reads any non-MIDI ringtone file into note form. */
    public static Ringtone load(byte[] data, String fileName) throws GsmException {
        Kind k = detect(data);
        Ringtone r;
        switch (k) {
            case OTT:
                r = decodeSmartMessaging(data, 0, Math.min(data.length, 2000));
                break;
            case COMMUNICATOR: {
                int n = Math.min(data.length, 4000), j = 0;
                while (true) {
                    if (j + 3 < n && data[j] == 0 && data[j + 1] == 0x02 && data[j + 2] == 0x4A && data[j + 3] == 0x3A) break;
                    if (j >= n - 4) throw new GsmException(GsmError.UNKNOWN, "no ringtone inside this Communicator file");
                    j++;
                }
                j++;
                r = decodeSmartMessaging(data, j, n - j);
                break;
            }
            case NOKIA_BINARY:
            case NOKIA_RE: {
                String[] name = new String[1];
                byte[] frame = k == Kind.NOKIA_BINARY ? loadBin(data, name) : loadRe(data, name);
                r = binaryToNotes(frame);
                if (r.commands.isEmpty()) {
                    throw new GsmException(GsmError.NOTSUPPORTED, "Gammu could not turn this binary tone into notes");
                }
                r.name = name[0];
                break;
            }
            case MIDI:
                throw new GsmException(GsmError.NOTSUPPORTED, "MIDI files need a channel; use Midi.parse");
            case MMF:
                throw new GsmException(GsmError.NOTSUPPORTED, "SMAF (.mmf) tones are polyphonic and cannot be converted");
            case UNKNOWN:
                throw new GsmException(GsmError.NOTSUPPORTED, "RIFF/WAV files are not supported for loading");
            default: {
                String t;
                try {
                    t = new String(data, "ISO-8859-1").trim();
                } catch (java.io.UnsupportedEncodingException e) {
                    throw new GsmException(GsmError.UNKNOWN, e.getMessage());
                }
                r = Ringtone.parseRtttl(t + "\n");
            }
        }
        if (r.name == null || r.name.isEmpty()) r.name = baseName(fileName);
        return r;
    }

    static String baseName(String fileName) {
        if (fileName == null) return "Tone";
        String n = fileName;
        int s = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (s >= 0) n = n.substring(s + 1);
        int d = n.lastIndexOf('.');
        if (d > 0) n = n.substring(0, d);
        return n.isEmpty() ? "Tone" : n;
    }

    // ---- Smart Messaging (.ott) -----------------------------------------------------------

    /** GetBufferInt: reads bits MSB first into the top of a byte, as Gammu does. */
    private static final class BitReader {
        final byte[] b;
        final int base;
        int bit;

        BitReader(byte[] b, int base) {
            this.b = b;
            this.base = base;
        }

        int get(int bits) {
            int l = 0, z = 128;
            for (int i = 0; i < bits; i++) {
                int pos = bit + i;
                int idx = base + pos / 8;
                if (idx < b.length && (b[idx] & (1 << (7 - pos % 8))) != 0) l += z;
                z /= 2;
            }
            bit += bits;
            return l;
        }

        void align() {
            while (bit % 8 != 0) bit++;
        }
    }

    private static final int[] SM_BPM = {
        25, 28, 31, 35, 40, 45, 50, 56, 63, 70, 80, 90, 100, 112, 125, 140, 160, 180, 200, 225,
        250, 285, 320, 355, 400, 450, 500, 565, 635, 715, 800, 900
    };

    /** GSM_DecodeNokiaRTTLRingtone. */
    public static Ringtone decodeSmartMessaging(byte[] pkg, int off, int len) throws GsmException {
        BitReader r = new BitReader(pkg, off);
        Ringtone t = new Ringtone();
        int defScale = Ringtone.SCALE_880, defStyle = Ringtone.NATURAL, defTempo = 63;
        if (r.get(8) != 0x02) throw new GsmException(GsmError.NOTSUPPORTED, "not a Smart Messaging ringtone");
        if (r.get(7) != (0x25 << 1)) throw new GsmException(GsmError.NOTSUPPORTED, "not RingingToneProgramming");
        r.align();
        if (r.get(7) != (0x1d << 1)) throw new GsmException(GsmError.NOTSUPPORTED, "not a Sound block");
        if (r.get(3) != (0x01 << 5)) throw new GsmException(GsmError.NOTSUPPORTED, "not BasicSongType");
        int l = r.get(4) >> 4;
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < l; i++) {
            int c = r.get(8);
            if (c == 0) break;
            name.append((char) c);
        }
        t.name = name.toString().replace("\u0001", "~");
        if (r.get(8) != 1) throw new GsmException(GsmError.NOTSUPPORTED, "only one song pattern is supported");
        if (r.get(3) != 0) throw new GsmException(GsmError.NOTSUPPORTED, "not PatternHeaderId");
        r.bit += 2;
        r.get(4); // loop value
        int howMany = r.get(8);
        for (int i = 0; i < howMany; i++) {
            if (r.bit / 8 > len + 2) break;
            int q = r.get(3);
            switch (q) {
                case 0x05 << 5: // volume
                    r.bit += 4;
                    break;
                case 0x03 << 5: { // style
                    int s = r.get(2);
                    if (s >= Ringtone.NATURAL && s <= Ringtone.STACCATO) defStyle = s;
                    break;
                }
                case 0x04 << 5: // tempo
                    defTempo = SM_BPM[r.get(5) >> 3];
                    break;
                case 0x02 << 5: // scale
                    defScale = (r.get(2) >> 6) + 4;
                    break;
                case 0x01 << 5: { // note
                    Ringtone.Note n = new Ringtone.Note();
                    int v = r.get(4);
                    n.note = v >= Ringtone.NOTE_C && v <= Ringtone.NOTE_H ? v : Ringtone.PAUSE;
                    v = r.get(3);
                    if (v <= Ringtone.DUR_1_32) n.duration = v;
                    n.durationSpec = r.get(2);
                    n.scale = defScale;
                    n.style = defStyle;
                    n.tempo = defTempo;
                    if (t.commands.size() == Ringtone.MAX_NOTES) break;
                    t.commands.add(Ringtone.note(n));
                    break;
                }
                default:
                    throw new GsmException(GsmError.NOTSUPPORTED, "unsupported ringtone block " + q);
            }
        }
        return t;
    }

    // ---- Nokia binary ---------------------------------------------------------------------

    /** loadbin: header 00 00 0C 01, name from offset 5, frame starts at 02 FC 09. */
    static byte[] loadBin(byte[] data, String[] nameOut) throws GsmException {
        int len = Math.min(data.length, 500);
        int i = 5;
        while (i < len && data[i] != 0) i++;
        nameOut[0] = latin1(data, 5, i - 5);
        // Gammu's loop condition uses && (stops at the first byte that is 0x02, or 0xFC after, ...).
        while (i + 2 < len && data[i] != 0x02 && (data[i + 1] & 0xFF) != 0xFC && data[i + 2] != 0x09) i++;
        if (i >= len) throw new GsmException(GsmError.INVALIDDATA, "no ringtone data in this .nrt file");
        return Arrays.copyOfRange(data, i, len);
    }

    /** loadre: DCT-3 ringtone dumps, normal and Unicode name variants. */
    static byte[] loadRe(byte[] data, String[] nameOut) throws GsmException {
        int len = Math.min(data.length, 500);
        if (len < 22) throw new GsmException(GsmError.INVALIDDATA, "file too short");
        if (data[18] == 0 && data[21] != 0x02) {
            StringBuilder sb = new StringBuilder();
            int p = 18;
            while (p + 1 < len && (data[p] != 0 || data[p + 1] != 0)) {
                sb.append((char) (((data[p] & 0xFF) << 8) | (data[p + 1] & 0xFF)));
                p += 2;
            }
            nameOut[0] = sb.toString();
            int start = 21 + sb.length() * 2;
            if (start > len) throw new GsmException(GsmError.INVALIDDATA, "bad ringtone file");
            return Arrays.copyOfRange(data, start, len);
        }
        int nl = data[16] & 0xFF;
        if (17 + nl > len) throw new GsmException(GsmError.INVALIDDATA, "bad ringtone file");
        nameOut[0] = latin1(data, 17, nl);
        int start = 19 + nameOut[0].length();
        return Arrays.copyOfRange(data, Math.min(start, len), len);
    }

    private static String latin1(byte[] b, int off, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n && off + i < b.length; i++) {
            if (b[off + i] == 0) break;
            sb.append((char) (b[off + i] & 0xFF));
        }
        return sb.toString();
    }

    /** Binary2RTTL. */
    public static Ringtone binaryToNotes(byte[] frame) {
        final int MAXN = 500;
        int[] notesLen = new int[MAXN + 64];
        int[] notesScale = new int[MAXN + 64];
        int[] notes = new int[MAXN + 64];
        int nr = 0, i = 3, startRepeat = 0, repeat = 0;
        int length = 0;
        int n = frame.length;
        while (i < n) {
            int command = frame[i] & 0xFF;
            i++;
            if (command != 0x06 && command != 0x00 && command != 0x09) {
                length = i < n ? frame[i] & 0xFF : 0;
                i++;
            }
            if (nr >= MAXN) break;
            if (command >= 114 && command <= 161) {
                int sc;
                if (command <= 124) { sc = Ringtone.SCALE_440; command -= 114; }
                else if (command <= 137) { sc = Ringtone.SCALE_880; command -= 126; }
                else if (command <= 149) { sc = Ringtone.SCALE_1760; command -= 138; }
                else { sc = Ringtone.SCALE_3520; command -= 150; }
                notesScale[nr] = sc;
                notes[nr] = command >= 0 && command <= 11 ? (command + 1) << 4 : Ringtone.PAUSE;
                if (nr > 0 && notes[nr - 1] == notes[nr] && notesScale[nr - 1] == notesScale[nr]) {
                    notesLen[nr - 1] += length;
                } else {
                    notesLen[nr] = length;
                    nr++;
                }
            } else {
                switch (command) {
                    case 0x05:
                        repeat = length;
                        startRepeat = nr;
                        break;
                    case 0x06: {
                        int end = nr;
                        for (int z = 0; z < repeat - 1; z++) {
                            for (int j = startRepeat; j < end && nr < MAXN; j++) {
                                notes[nr] = notes[j];
                                notesScale[nr] = notesScale[j];
                                notesLen[nr] = notesLen[j];
                                nr++;
                            }
                        }
                        break;
                    }
                    case 0x07:
                        if (length == 0x0B) i = n + 1;
                        break;
                    case 0x40:
                        notes[nr] = Ringtone.PAUSE;
                        if (nr > 0 && notes[nr - 1] == Ringtone.PAUSE) {
                            notesLen[nr - 1] += length;
                        } else {
                            notesLen[nr] = length;
                            notesScale[nr] = 0;
                            nr++;
                        }
                        break;
                    default:
                        break;
                }
            }
        }
        while (nr > 0 && notes[nr - 1] == Ringtone.PAUSE) nr--;

        Ringtone out = new Ringtone();
        int[] lengths = new int[24];
        int[] dur = new int[24], spec = new int[24];
        int[] base = {Ringtone.DUR_FULL, Ringtone.DUR_1_2, Ringtone.DUR_1_4, Ringtone.DUR_1_8, Ringtone.DUR_1_16,
            Ringtone.DUR_1_32};
        final int DOUBLE_DOTTED = 0x02 << 6, LENGTH_2_3 = 0x03 << 6;
        for (int k = 0; k < 6; k++) {
            dur[k] = dur[k + 6] = dur[k + 12] = dur[k + 18] = base[k];
            spec[k] = Ringtone.NO_SPECIAL;
            spec[k + 6] = Ringtone.DOTTED;
            spec[k + 12] = DOUBLE_DOTTED;
            spec[k + 18] = LENGTH_2_3;
        }
        boolean found = false;
        for (int accuracy = 1; accuracy < 5; accuracy++) {
            int sp = 1;
            while (sp < 1000) {
                lengths[0] = 30000 / sp;
                for (int j = 0; j < 5; j++) lengths[j + 1] = lengths[j] / 2;
                for (int j = 0; j < 6; j++) lengths[6 + j] = lengths[j] * 3 / 2;
                for (int j = 0; j < 6; j++) lengths[12 + j] = lengths[j] * 9 / 4;
                for (int j = 0; j < 6; j++) lengths[18 + j] = lengths[j] * 2 / 3;
                found = false;
                for (int j = 0; j < nr; j++) {
                    found = false;
                    for (int z = 0; z < 24; z++) {
                        if (notesLen[j] - lengths[z] > -accuracy && notesLen[j] - lengths[z] < accuracy) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) break;
                }
                if (found) break;
                sp++;
            }
            if (found) {
                for (int j = 0; j < nr; j++) {
                    Ringtone.Note note = new Ringtone.Note();
                    note.note = notes[j];
                    note.tempo = sp;
                    note.style = Ringtone.CONTINUOUS;
                    note.scale = notes[j] != Ringtone.PAUSE ? notesScale[j] : Ringtone.SCALE_880;
                    for (int z = 0; z < 24; z++) {
                        if (notesLen[j] - lengths[z] > -accuracy && notesLen[j] - lengths[z] < accuracy) {
                            note.duration = dur[z];
                            note.durationSpec = spec[z];
                            if (note.durationSpec == DOUBLE_DOTTED) { // "Trick from PPM Edit"
                                if (note.duration != Ringtone.DUR_FULL) note.duration -= 0x20;
                                note.durationSpec = Ringtone.NO_SPECIAL;
                            }
                            if (note.durationSpec == LENGTH_2_3) note.durationSpec = Ringtone.NO_SPECIAL;
                            break;
                        }
                    }
                    out.commands.add(Ringtone.note(note));
                }
                break;
            }
        }
        if (!found) out.commands.clear();
        return out;
    }
}
