package com.pocketgammu.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * GSM_Ringtone in note-tone form, with Gammu's RTTTL reader (loadrttl) and
 * Smart Messaging encoder (GSM_EncodeNokiaRTTLRingtone) from
 * libgammu/service/gsmring.c.
 */
public final class Ringtone {
    // Values are the already-shifted enum values from gammu-ringtone.h.
    public static final int NATURAL = 0x00 << 6, CONTINUOUS = 0x01 << 6, STACCATO = 0x02 << 6;
    public static final int PAUSE = 0, NOTE_C = 0x10, NOTE_CIS = 0x20, NOTE_D = 0x30, NOTE_DIS = 0x40,
            NOTE_E = 0x50, NOTE_F = 0x60, NOTE_FIS = 0x70, NOTE_G = 0x80, NOTE_GIS = 0x90,
            NOTE_A = 0xA0, NOTE_AIS = 0xB0, NOTE_H = 0xC0;
    public static final int DUR_FULL = 0x00, DUR_1_2 = 0x20, DUR_1_4 = 0x40, DUR_1_8 = 0x60,
            DUR_1_16 = 0x80, DUR_1_32 = 0xA0;
    public static final int NO_SPECIAL = 0x00, DOTTED = 0x40;
    public static final int SCALE_440 = 4, SCALE_880 = 5, SCALE_1760 = 6, SCALE_3520 = 7;

    public enum CommandType { NOTE, ENABLE_LED, DISABLE_LED, ENABLE_VIBRA, DISABLE_VIBRA, ENABLE_LIGHT, DISABLE_LIGHT }

    public static final class Note {
        public int duration, durationSpec, note, style, scale, tempo;
    }

    public static final class Command {
        public final CommandType type;
        public final Note note;

        Command(CommandType type, Note note) {
            this.type = type;
            this.note = note;
        }
    }

    public static final int MAX_NOTES = 255; // GSM_MAX_RINGTONE_NOTES

    public String name = "";
    public final List<Command> commands = new ArrayList<>();
    public boolean allNotesScale = false;

    private static int atoi(String s, int i) {
        int n = s.length();
        while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        boolean neg = false;
        if (i < n && (s.charAt(i) == '-' || s.charAt(i) == '+')) neg = s.charAt(i++) == '-';
        int v = 0;
        while (i < n && Character.isDigit(s.charAt(i))) v = v * 10 + (s.charAt(i++) - '0');
        return neg ? -v : v;
    }

    private static char at(String s, int i) {
        return i < s.length() ? s.charAt(i) : 0;
    }

    /** loadrttl(). The quirks (e.g. "o=" between notes being ignored) are Gammu's own. */
    public static Ringtone parseRtttl(String text) throws GsmException {
        Ringtone r = new Ringtone();
        String b = text.length() > 2000 ? text.substring(0, 2000) : text;
        int n = b.length();
        int defScale = SCALE_880, defDuration = DUR_1_4, defStyle = NATURAL, defTempo = 63;
        int i = 0;
        while (i < n && b.charAt(i) != ':') i++;
        if (i == 0) r.name = "Gammu";
        else if (i >= n) throw new GsmException(GsmError.INVALIDDATA, "RTTTL needs name:settings:notes");
        else {
            if (i > 20) throw new GsmException(GsmError.INVALIDDATA, "ringtone name is longer than 20 characters");
            r.name = b.substring(0, i);
        }
        i++;
        while (true) {
            if (i >= n) throw new GsmException(GsmError.INVALIDDATA, "RTTTL settings section is not closed with ':'");
            switch (b.charAt(i)) {
                case ':':
                    break;
                case 'd': case 'D':
                    switch (atoi(b, i + 2)) {
                        case 1: defDuration = DUR_FULL; break;
                        case 2: defDuration = DUR_1_2; break;
                        case 4: defDuration = DUR_1_4; break;
                        case 8: defDuration = DUR_1_8; break;
                        case 16: defDuration = DUR_1_16; break;
                        case 32: defDuration = DUR_1_32; break;
                        default: break;
                    }
                    break;
                case 'o': case 'O':
                    switch (atoi(b, i + 2)) {
                        case 4: defScale = SCALE_440; break;
                        case 5: defScale = SCALE_880; break;
                        case 6: defScale = SCALE_1760; break;
                        case 7: defScale = SCALE_3520; break;
                        default: break;
                    }
                    break;
                case 'b': case 'B':
                    defTempo = atoi(b, i + 2);
                    break;
                case 's': case 'S':
                    defStyle = style(at(b, i + 1), defStyle);
                    defStyle = style(at(b, i + 2), defStyle);
                    break;
                default:
                    break;
            }
            while (i < n && b.charAt(i) != ':' && b.charAt(i) != ',') i++;
            if (i >= n) throw new GsmException(GsmError.INVALIDDATA, "RTTTL settings section is not closed with ':'");
            if (b.charAt(i) == ',') i++;
            if (at(b, i) == ':') break;
        }
        i++;
        while (i < n && r.commands.size() != MAX_NOTES) {
            char c = b.charAt(i);
            if (c == 'z' || c == 'Z') {
                switch (at(b, i + 1)) {
                    case 'd': r.commands.add(new Command(CommandType.DISABLE_LED, null)); break;
                    case 'D': r.commands.add(new Command(CommandType.ENABLE_LED, null)); break;
                    case 'v': r.commands.add(new Command(CommandType.DISABLE_VIBRA, null)); break;
                    case 'V': r.commands.add(new Command(CommandType.ENABLE_VIBRA, null)); break;
                    case 'l': r.commands.add(new Command(CommandType.DISABLE_LIGHT, null)); break;
                    case 'L': r.commands.add(new Command(CommandType.ENABLE_LIGHT, null)); break;
                    default: break;
                }
            } else if (c == 'o' || c == 'O') {
                // Gammu compares buffer[i+2] with the integers 4..7, so this never matches.
            } else if (c == 's' || c == 'S') {
                defStyle = style(at(b, i + 1), defStyle);
                defStyle = style(at(b, i + 2), defStyle);
            } else {
                Note note = new Note();
                note.style = defStyle;
                note.tempo = defTempo;
                note.scale = defScale;
                note.duration = defDuration;
                note.durationSpec = NO_SPECIAL;
                note.note = PAUSE;
                switch (atoi(b, i)) {
                    case 1: note.duration = DUR_FULL; break;
                    case 2: note.duration = DUR_1_2; break;
                    case 4: note.duration = DUR_1_4; break;
                    case 8: note.duration = DUR_1_8; break;
                    case 16: note.duration = DUR_1_16; break;
                    case 32: note.duration = DUR_1_32; break;
                    default: break;
                }
                while (i < n && Character.isDigit(b.charAt(i))) i++;
                if (at(b, i) == '.') {
                    note.durationSpec = DOTTED;
                    i++;
                }
                switch (at(b, i)) {
                    case 'A': case 'a': note.note = NOTE_A; break;
                    case 'B': case 'b': case 'H': case 'h': note.note = NOTE_H; break;
                    case 'C': case 'c': note.note = NOTE_C; break;
                    case 'D': case 'd': note.note = NOTE_D; break;
                    case 'E': case 'e': note.note = NOTE_E; break;
                    case 'F': case 'f': note.note = NOTE_F; break;
                    case 'G': case 'g': note.note = NOTE_G; break;
                    default: break;
                }
                i++;
                boolean cut = false;
                if (i >= n) cut = true;
                if (!cut && b.charAt(i) == '#') {
                    switch (note.note) {
                        case NOTE_A: note.note = NOTE_AIS; break;
                        case NOTE_C: note.note = NOTE_CIS; break;
                        case NOTE_D: note.note = NOTE_DIS; break;
                        case NOTE_F: note.note = NOTE_FIS; break;
                        case NOTE_G: note.note = NOTE_GIS; break;
                        default: break;
                    }
                    i++;
                    if (i >= n) cut = true;
                }
                if (!cut && b.charAt(i) == '.') {
                    note.durationSpec = DOTTED;
                    i++;
                    if (i >= n) cut = true;
                }
                if (!cut && note.note != PAUSE && Character.isDigit(b.charAt(i))) {
                    switch (atoi(b, i)) {
                        case 4: note.scale = SCALE_440; break;
                        case 5: note.scale = SCALE_880; break;
                        case 6: note.scale = SCALE_1760; break;
                        case 7: note.scale = SCALE_3520; break;
                        default: break;
                    }
                    i++;
                    if (i >= n) cut = true;
                }
                if (cut) break; // Gammu breaks out before counting this note
                r.commands.add(new Command(CommandType.NOTE, note));
            }
            if (i >= n) break;
            while (b.charAt(i) != ',') {
                i++;
                if (i >= n) break;
            }
            if (i >= n) break;
            if (b.charAt(i) == ',') i++;
            if (i >= n) throw new GsmException(GsmError.INVALIDDATA, "RTTTL ends with a comma");
        }
        return r;
    }

    private static int style(char c, int def) {
        switch (c) {
            case 'C': case 'c': return CONTINUOUS;
            case 'N': case 'n': return NATURAL;
            case 'S': case 's': return STACCATO;
            default: return def;
        }
    }

    public int noteCount() {
        return commands.size();
    }

    public static Command note(Note n) {
        return new Command(CommandType.NOTE, n);
    }

    public static Note makeNote(int note, int scale, int duration, int spec, int style, int tempo) {
        Note n = new Note();
        n.note = note;
        n.scale = scale;
        n.duration = duration;
        n.durationSpec = spec;
        n.style = style;
        n.tempo = tempo;
        return n;
    }

    public Ringtone copy() {
        Ringtone r = new Ringtone();
        r.name = name;
        r.allNotesScale = allNotesScale;
        for (Command c : commands) {
            if (c.note == null) {
                r.commands.add(new Command(c.type, null));
            } else {
                Note n = c.note;
                r.commands.add(note(makeNote(n.note, n.scale, n.duration, n.durationSpec, n.style, n.tempo)));
            }
        }
        return r;
    }

    // ---- helpers for the editor and the preview ------------------------------------------

    /** Note value in GSM_RingNoteGetFullDuration units (full note = 128). */
    public static int fullDuration(Note n) {
        int d;
        switch (n.duration) {
            case DUR_FULL: d = 128; break;
            case DUR_1_2: d = 64; break;
            case DUR_1_4: d = 32; break;
            case DUR_1_8: d = 16; break;
            case DUR_1_16: d = 8; break;
            case DUR_1_32: d = 4; break;
            default: d = 1;
        }
        switch (n.durationSpec) {
            case DOTTED: d = d * 3 / 2; break;
            case 0x02 << 6: d = d * 9 / 4; break;
            case 0x03 << 6: d = d * 2 / 3; break;
            default: break;
        }
        return d;
    }

    /** Length in milliseconds at the note's tempo (beats are quarter notes). */
    public static double millis(Note n) {
        int bpm = n.tempo <= 0 ? 63 : n.tempo;
        return fullDuration(n) * (60000.0 / bpm) / 32.0;
    }

    /** GSM_RingNoteGetFrequency without the integer rounding, in Hz; 0 for pauses. */
    public static double frequency(Note n) {
        if (n.note == PAUSE || n.note < NOTE_C || n.note > NOTE_H) return 0;
        int semitone = n.note / 16 - 1; // C = 0
        // Scale_880 is the octave whose A is 880 Hz.
        return 880.0 * Math.pow(2, (semitone - 9) / 12.0) * Math.pow(2, n.scale - SCALE_880);
    }

    public double totalMillis() {
        double t = 0;
        for (Command c : commands) if (c.type == CommandType.NOTE) t += millis(c.note);
        return t;
    }

    /** The Smart Messaging tempo table entry nearest to bpm (what the phone will play). */
    public static int nearestTempo(int bpm) {
        int best = BPM[0];
        for (int b : BPM) if (Math.abs(b - bpm) < Math.abs(best - bpm)) best = b;
        return best;
    }

    public static final String[] NOTE_NAMES = {"c", "c#", "d", "d#", "e", "f", "f#", "g", "g#", "a", "a#", "b"};

    /** RTTTL text in the shape of GSM_SaveRingtoneRttl, readable again by parseRtttl. */
    public String toRtttl() {
        int[] durCount = new int[6];
        int[] scaleCount = new int[8];
        Note first = null;
        for (Command c : commands) {
            if (c.type != CommandType.NOTE) continue;
            Note n = c.note;
            if (first == null) first = n;
            int k = n.duration / 32;
            if (k >= 0 && k < 6) durCount[k] += k >= 4 ? 2 : 1;
            if (n.note != PAUSE && n.scale >= 4 && n.scale <= 7) scaleCount[n.scale]++;
        }
        int dk = 2, best = -1;
        for (int i = 0; i < 6; i++) if (durCount[i] > best) { best = durCount[i]; dk = i; }
        int defScale = SCALE_880;
        best = 0;
        for (int i = 4; i <= 7; i++) if (scaleCount[i] > best) { best = scaleCount[i]; defScale = i; }
        int defDur = dk * 32;
        int[] durNum = {1, 2, 4, 8, 16, 32};
        StringBuilder sb = new StringBuilder();
        String nm = name == null ? "" : name.replace(":", "").replace(",", "");
        if (nm.length() > 15) nm = nm.substring(0, 15);
        sb.append(nm).append(":d=").append(durNum[dk]).append(",o=").append(defScale);
        int defStyle = first == null ? NATURAL : first.style;
        if (defStyle == STACCATO) sb.append(",s=S");
        else if (defStyle == CONTINUOUS) sb.append(",s=C");
        sb.append(",b=").append(first == null ? 63 : first.tempo).append(':');
        boolean comma = false;
        int style = defStyle;
        for (Command c : commands) {
            if (c.type != CommandType.NOTE) continue;
            Note n = c.note;
            if (comma) sb.append(',');
            comma = true;
            if (n.style != style) {
                style = n.style;
                sb.append(style == STACCATO ? "s=S" : style == CONTINUOUS ? "s=C" : "s=N").append(',');
            }
            if (n.duration != defDur && n.duration / 32 < 6) sb.append(durNum[n.duration / 32]);
            if (n.note == PAUSE) sb.append('p');
            else sb.append(NOTE_NAMES[n.note / 16 - 1]);
            if (n.durationSpec == DOTTED) sb.append('.');
            if (n.note != PAUSE && n.scale != defScale) sb.append(n.scale);
        }
        return sb.toString();
    }

    /** Editor options applied on top of the tune before it is previewed or sent. */
    public static final class Edit {
        /** 0 = keep, 4..7 = move the tune so its lowest octave is this one, -1 = fit into 4..7. */
        public int octave = 0;
        /** 0 = keep; 1 = twice as long, 2 = half as long; 3..8 = every note becomes full..1/32. */
        public int duration = 0;
        /** 0 = keep. */
        public int tempo = 0;
        /** 0 = no limit. */
        public int maxNotes = 0;
    }

    public Ringtone edited(Edit e) {
        Ringtone r = copy();
        // Note limit counts only notes (pauses ride along with the note before them).
        if (e.maxNotes > 0) {
            int notes = 0;
            List<Command> keep = new ArrayList<>();
            for (Command c : r.commands) {
                if (c.type == CommandType.NOTE && c.note.note != PAUSE) {
                    if (notes == e.maxNotes) break;
                    notes++;
                }
                keep.add(c);
            }
            r.commands.clear();
            r.commands.addAll(keep);
        }
        int lo = 99, hi = -1;
        for (Command c : r.commands) {
            if (c.type != CommandType.NOTE || c.note.note == PAUSE) continue;
            lo = Math.min(lo, c.note.scale);
            hi = Math.max(hi, c.note.scale);
        }
        int shift = 0;
        if (lo != 99) {
            if (e.octave >= 4 && e.octave <= 7) shift = e.octave - lo;
            else if (e.octave == -1) {
                if (lo < 4) shift = 4 - lo;
                else if (hi > 7) shift = Math.max(7 - hi, 4 - lo);
            }
        }
        for (Command c : r.commands) {
            if (c.type != CommandType.NOTE) continue;
            Note n = c.note;
            if (n.note != PAUSE) n.scale = Math.max(4, Math.min(7, n.scale + shift));
            if (e.duration == 1 && n.duration > DUR_FULL) n.duration -= 0x20;
            else if (e.duration == 2 && n.duration < DUR_1_32) n.duration += 0x20;
            else if (e.duration >= 3 && e.duration <= 8) {
                n.duration = (e.duration - 3) * 0x20;
                n.durationSpec = NO_SPECIAL;
            }
            if (e.tempo > 0) n.tempo = e.tempo;
        }
        return r;
    }

    // ---- Smart Messaging encoder -------------------------------------------------

    private static final int[] BPM = {
        25, 28, 31, 35, 40, 45, 50, 56, 63, 70, 80, 90, 100, 112, 125, 140, 160, 180, 200, 225,
        250, 285, 320, 355, 400, 450, 500, 565, 635, 715, 800, 900
    };

    static int rttlTempo(int beats) {
        int i = 0;
        while (beats > BPM[i] && BPM[i] != 900) i++;
        return i << 3;
    }

    /** Bit writer with Gammu's AddBufferByte/BufferAlign semantics (MSB first). */
    static final class Bits {
        final byte[] buf;
        int bit;

        Bits(byte[] buf) {
            this.buf = buf;
        }

        void add(int source, int bits) {
            for (int i = 0; i < bits; i++) {
                int sbit = (source >> (7 - i)) & 1;
                int pos = bit + i;
                if (sbit != 0) buf[pos / 8] |= (byte) (1 << (7 - pos % 8));
                else buf[pos / 8] &= (byte) ~(1 << (7 - pos % 8));
            }
            bit += bits;
        }

        void addBytes(byte[] src, int bits) {
            for (int i = 0; i < bits; i++) {
                int sbit = (src[i / 8] >> (7 - i % 8)) & 1;
                int pos = bit + i;
                if (sbit != 0) buf[pos / 8] |= (byte) (1 << (7 - pos % 8));
                else buf[pos / 8] &= (byte) ~(1 << (7 - pos % 8));
            }
            bit += bits;
        }

        void align() {
            while (bit % 8 != 0) {
                buf[bit / 8] &= (byte) ~(1 << (7 - bit % 8));
                bit++;
            }
        }

        static int alignNumber(int b) {
            while (b % 8 != 0) b++;
            return b;
        }
    }

    /** Nokia's escaping of '~' in names (EncodeUnicodeSpecialNOKIAChars), Latin-1 only. */
    static byte[] nokiaName(String name) {
        StringBuilder sb = new StringBuilder();
        boolean special = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (special) {
                if (c == '~') sb.append('~');
                else sb.append('\u0001').append(c);
                special = false;
            } else if (c == '~') {
                special = true;
            } else {
                sb.append(c);
            }
        }
        if (special) sb.append('\u0001');
        return Hex.latin1(sb.toString());
    }

    /** Result of {@link #encodeSmartMessaging}. */
    public static final class Encoded {
        public final byte[] data;
        /** Index of the first command that did not fit (Gammu's return value). */
        public final int packedCommands;

        Encoded(byte[] data, int packedCommands) {
            this.data = data;
            this.packedCommands = packedCommands;
        }
    }

    /** GSM_EncodeNokiaRTTLRingtone with *maxlength = maxLength. */
    public Encoded encodeSmartMessaging(int maxLength) {
        byte[] pkg = new byte[Math.max(maxLength, 16) + 64];
        Bits w = new Bits(pkg);
        int defScale = 255, defStyle = 255, defTempo = 255;
        int howManyCommands = 0, howManyNotes = 0;

        w.add(0x02, 8);
        w.add(0x25 << 1, 7);
        w.align();
        w.add(0x1d << 1, 7);
        w.add(0x01 << 5, 3);

        byte[] nm = nokiaName(name);
        w.add(nm.length << 4, 4);
        w.addBytes(nm, 8 * nm.length);

        w.add(0x01, 8);
        w.add(0x00, 3);
        w.add(0x00, 2);
        w.add(0x15 << 4, 4);

        int startBitHowManyCommands = w.bit;
        w.bit += 8;

        boolean started = false;
        int i;
        for (i = 0; i < commands.size(); i++) {
            Command cmd = commands.get(i);
            if (cmd.type != CommandType.NOTE) {
                howManyNotes++;
                continue;
            }
            Note note = cmd.note;
            if (!started && note.note != PAUSE) started = true;
            if (!started) {
                howManyNotes++;
                continue;
            }
            int oldStartBit = w.bit;
            boolean stop = false;
            if (note.note != PAUSE) {
                if (defScale != note.scale || allNotesScale) {
                    if (Bits.alignNumber(w.bit + 5 + 8) / 8 > maxLength) {
                        w.bit = oldStartBit;
                        break;
                    }
                    defScale = note.scale;
                    w.add(0x02 << 5, 3);
                    w.add((defScale - 4) << 6, 2);
                    howManyCommands++;
                }
                if (defStyle != note.style) {
                    if (Bits.alignNumber(w.bit + 5 + 8) / 8 > maxLength) {
                        w.bit = oldStartBit;
                        break;
                    }
                    defStyle = note.style;
                    w.add(0x03 << 5, 3);
                    w.add(defStyle, 2);
                    howManyCommands++;
                }
            }
            if (defTempo != rttlTempo(note.tempo)) {
                if (Bits.alignNumber(w.bit + 8 + 8) / 8 > maxLength) {
                    w.bit = oldStartBit;
                    break;
                }
                defTempo = rttlTempo(note.tempo);
                w.add(0x04 << 5, 3);
                w.add(defTempo, 5);
                howManyCommands++;
            }
            if (Bits.alignNumber(w.bit + 12 + 8) / 8 > maxLength) {
                w.bit = oldStartBit;
                break;
            }
            w.add(0x01 << 5, 3);
            w.add(note.note, 4);
            w.add(note.duration, 3);
            w.add(note.durationSpec, 2);
            howManyCommands++;
            if (maxLength < 1000) {
                if (howManyNotes == 130 - 1) break;
            }
            howManyNotes++;
            if (stop) break;
        }
        w.align();
        w.add(0x00, 8);
        int end = w.bit;
        w.bit = startBitHowManyCommands;
        w.add(howManyCommands & 0xFF, 8);
        byte[] out = new byte[end / 8];
        System.arraycopy(pkg, 0, out, 0, out.length);
        return new Encoded(out, i);
    }
}
