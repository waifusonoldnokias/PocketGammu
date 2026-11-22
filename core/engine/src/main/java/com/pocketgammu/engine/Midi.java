package com.pocketgammu.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Standard MIDI file reader that turns one channel into a monophonic Nokia
 * tune. Where the channel plays chords, the highest note wins (it is usually
 * the melody); a note ends when the next one starts.
 */
public final class Midi {
    public static final class NoteEvent {
        public final long tick;
        public final int channel, key, velocity;
        public final boolean on;

        NoteEvent(long tick, int channel, int key, int velocity, boolean on) {
            this.tick = tick;
            this.channel = channel;
            this.key = key;
            this.velocity = velocity;
            this.on = on;
        }
    }

    public static final class ChannelInfo {
        public int channel; // 0-based
        public int notes;
        public int lowest = 127, highest = 0;
        public int program = -1;
        public String trackName = "";

        public String label() {
            StringBuilder sb = new StringBuilder("Channel ").append(channel + 1);
            if (channel == 9) sb.append(" · Drums");
            else if (program >= 0) sb.append(" · ").append(GM_FAMILIES[program / 8]);
            if (!trackName.isEmpty()) sb.append(" · \"").append(trackName).append('"');
            return sb.toString();
        }

        public String detail() {
            return notes + " notes, " + keyName(lowest) + " to " + keyName(highest);
        }
    }

    static final String[] GM_FAMILIES = {
        "Piano", "Chromatic percussion", "Organ", "Guitar", "Bass", "Strings", "Ensemble", "Brass",
        "Reed", "Pipe", "Synth lead", "Synth pad", "Synth effects", "Ethnic", "Percussive", "Sound effects"
    };

    public static String keyName(int key) {
        return Ringtone.NOTE_NAMES[key % 12].toUpperCase() + (key / 12 - 1);
    }

    public int format, division;
    public final List<NoteEvent> events = new ArrayList<>();
    /** {tick, microseconds per quarter}. */
    public final List<long[]> tempos = new ArrayList<>();
    public final List<ChannelInfo> channels = new ArrayList<>();
    public String title = "";
    /** Semitones added to every key. */
    public int transpose = 0;

    private static int u8(byte[] b, int p) {
        return b[p] & 0xFF;
    }

    private static int be(byte[] b, int p, int n) {
        int v = 0;
        for (int i = 0; i < n; i++) v = (v << 8) | u8(b, p + i);
        return v;
    }

    public static Midi parse(byte[] b) throws GsmException {
        if (b.length < 14 || b[0] != 'M' || b[1] != 'T' || b[2] != 'h' || b[3] != 'd') {
            throw new GsmException(GsmError.INVALIDDATA, "not a MIDI file");
        }
        Midi m = new Midi();
        int hlen = be(b, 4, 4);
        m.format = be(b, 8, 2);
        int ntrks = be(b, 10, 2);
        m.division = be(b, 12, 2);
        if ((m.division & 0x8000) != 0) {
            throw new GsmException(GsmError.NOTSUPPORTED, "SMPTE-timed MIDI files are not supported");
        }
        if (m.division == 0) m.division = 96;
        ChannelInfo[] ch = new ChannelInfo[16];
        String[] trackNames = new String[16];
        int p = 8 + hlen;
        for (int t = 0; t < ntrks && p + 8 <= b.length; t++) {
            if (b[p] != 'M' || b[p + 1] != 'T' || b[p + 2] != 'r' || b[p + 3] != 'k') {
                int len = be(b, p + 4, 4);
                p += 8 + len; // unknown chunk
                t--;
                continue;
            }
            int len = be(b, p + 4, 4);
            int q = p + 8, end = Math.min(b.length, q + len);
            long tick = 0;
            int running = 0;
            String trackName = "";
            List<Integer> trackChannels = new ArrayList<>();
            while (q < end) {
                long delta = 0;
                int c;
                int guard = 0;
                do {
                    c = u8(b, q++);
                    delta = (delta << 7) | (c & 0x7F);
                } while ((c & 0x80) != 0 && q < end && ++guard < 5);
                tick += delta;
                if (q >= end) break;
                int st = u8(b, q);
                if (st >= 0x80) {
                    q++;
                    if (st < 0xF0) running = st;
                } else {
                    st = running;
                    if (st == 0) throw new GsmException(GsmError.INVALIDDATA, "bad MIDI data");
                }
                if (st == 0xFF) {
                    int type = u8(b, q++);
                    long l = 0;
                    guard = 0;
                    do {
                        c = u8(b, q++);
                        l = (l << 7) | (c & 0x7F);
                    } while ((c & 0x80) != 0 && q < end && ++guard < 5);
                    int dl = (int) Math.min(l, end - q);
                    if (type == 0x51 && dl == 3) m.tempos.add(new long[] {tick, be(b, q, 3)});
                    if (type == 0x03 && dl > 0) {
                        trackName = new String(b, q, dl, java.nio.charset.StandardCharsets.ISO_8859_1).trim();
                        if (m.title.isEmpty() && t == 0) m.title = trackName;
                    }
                    q += dl;
                    if (type == 0x2F) break;
                } else if (st == 0xF0 || st == 0xF7) {
                    long l = 0;
                    guard = 0;
                    do {
                        c = u8(b, q++);
                        l = (l << 7) | (c & 0x7F);
                    } while ((c & 0x80) != 0 && q < end && ++guard < 5);
                    q += (int) Math.min(l, end - q);
                } else {
                    int kind = st & 0xF0, chn = st & 0x0F;
                    int d1 = q < end ? u8(b, q) : 0;
                    int d2 = kind == 0xC0 || kind == 0xD0 ? 0 : (q + 1 < end ? u8(b, q + 1) : 0);
                    q += kind == 0xC0 || kind == 0xD0 ? 1 : 2;
                    if (ch[chn] == null) {
                        ch[chn] = new ChannelInfo();
                        ch[chn].channel = chn;
                    }
                    if (!trackChannels.contains(chn)) trackChannels.add(chn);
                    if (kind == 0x90 && d2 > 0) {
                        m.events.add(new NoteEvent(tick, chn, d1, d2, true));
                        ch[chn].notes++;
                        ch[chn].lowest = Math.min(ch[chn].lowest, d1);
                        ch[chn].highest = Math.max(ch[chn].highest, d1);
                    } else if (kind == 0x80 || kind == 0x90) {
                        m.events.add(new NoteEvent(tick, chn, d1, 0, false));
                    } else if (kind == 0xC0 && ch[chn].program < 0) {
                        ch[chn].program = d1;
                    }
                }
            }
            if (!trackName.isEmpty()) {
                for (int c : trackChannels) if (trackNames[c] == null) trackNames[c] = trackName;
            }
            p = p + 8 + len;
        }
        for (int i = 0; i < 16; i++) {
            if (ch[i] != null && ch[i].notes > 0) {
                if (trackNames[i] != null) ch[i].trackName = trackNames[i];
                m.channels.add(ch[i]);
            }
        }
        if (m.channels.isEmpty()) throw new GsmException(GsmError.INVALIDDATA, "this MIDI file has no notes");
        // Stable sort keeps note-offs before note-ons at the same tick within a track.
        Collections.sort(m.events, new Comparator<NoteEvent>() {
            @Override
            public int compare(NoteEvent a, NoteEvent c) {
                if (a.tick != c.tick) return a.tick < c.tick ? -1 : 1;
                if (a.on != c.on) return a.on ? 1 : -1;
                return 0;
            }
        });
        Collections.sort(m.tempos, new Comparator<long[]>() {
            @Override
            public int compare(long[] a, long[] c) {
                return Long.compare(a[0], c[0]);
            }
        });
        return m;
    }

    /** Song tempo in BPM: the first tempo event, or MIDI's default 120. */
    public int bpm() {
        if (tempos.isEmpty()) return 120;
        return (int) Math.round(60_000_000.0 / Math.max(1, tempos.get(0)[1]));
    }

    /** Milliseconds from the start at a tick, following tempo changes. */
    double millisAt(long tick) {
        double ms = 0;
        long lastTick = 0;
        double usPerQ = 500_000;
        for (long[] t : tempos) {
            if (t[0] >= tick) break;
            ms += (t[0] - lastTick) * usPerQ / division / 1000.0;
            lastTick = t[0];
            usPerQ = t[1];
        }
        return ms + (tick - lastTick) * usPerQ / division / 1000.0;
    }

    /** One monophonic line: {startTick, endTick, key}. */
    public List<long[]> melody(int channel) {
        List<long[]> starts = new ArrayList<>();
        int[] active = new int[128];
        long[] since = new long[128];
        // Gather note intervals.
        List<long[]> notes = new ArrayList<>();
        for (NoteEvent e : events) {
            if (e.channel != channel) continue;
            if (e.on) {
                if (active[e.key]++ == 0) since[e.key] = e.tick;
            } else if (active[e.key] > 0) {
                if (--active[e.key] == 0) notes.add(new long[] {since[e.key], e.tick, e.key});
            }
        }
        long last = events.isEmpty() ? 0 : events.get(events.size() - 1).tick;
        for (int k = 0; k < 128; k++) if (active[k] > 0) notes.add(new long[] {since[k], last, k});
        Collections.sort(notes, new Comparator<long[]>() {
            @Override
            public int compare(long[] a, long[] b) {
                if (a[0] != b[0]) return Long.compare(a[0], b[0]);
                return Long.compare(b[2], a[2]); // highest first at the same start
            }
        });
        for (long[] n : notes) {
            if (!starts.isEmpty()) {
                long[] prev = starts.get(starts.size() - 1);
                if (n[0] == prev[0]) continue; // lower note of a chord
                if (n[0] < prev[1]) prev[1] = n[0]; // overlap: cut the previous note
            }
            if (n[1] > n[0]) starts.add(new long[] {n[0], n[1], n[2]});
        }
        return starts;
    }

    private static final double[] BEATS = {4, 2, 1, 0.5, 0.25, 0.125};

    /** Nearest Nokia note value for a length in beats: {duration, spec}. */
    static int[] quantize(double beats) {
        int bd = Ringtone.DUR_1_32, bs = Ringtone.NO_SPECIAL;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 6; i++) {
            for (int dotted = 0; dotted < 2; dotted++) {
                double v = BEATS[i] * (dotted == 1 ? 1.5 : 1);
                double err = Math.abs(Math.log(beats / v));
                if (err < best) {
                    best = err;
                    bd = i * 0x20;
                    bs = dotted == 1 ? Ringtone.DOTTED : Ringtone.NO_SPECIAL;
                }
            }
        }
        return new int[] {bd, bs};
    }

    /** Converts a channel to a Nokia tune at the given tempo (0 = the song's own). */
    public Ringtone toRingtone(int channel, String name, int tempo) {
        int bpm = Ringtone.nearestTempo(tempo > 0 ? tempo : bpm());
        double beatMs = 60000.0 / bpm;
        Ringtone r = new Ringtone();
        r.name = name;
        double cursor = -1;
        for (long[] n : melody(channel)) {
            double s = millisAt(n[0]), e = millisAt(n[1]);
            if (cursor < 0) cursor = s; // skip silence before the first note
            double gap = (s - cursor) / beatMs;
            if (gap >= 0.1) addLength(r, Ringtone.PAUSE, 0, gap, bpm);
            int key = Math.max(0, Math.min(127, (int) n[2] + transpose));
            int scale = key / 12 - 1, note = (key % 12 + 1) << 4;
            addLength(r, note, scale, Math.max(0.1, (e - s) / beatMs), bpm);
            cursor = e;
            if (r.commands.size() >= Ringtone.MAX_NOTES) break;
        }
        // The scale may still be outside 4..7; Edit with octave = -1 (or a fixed octave) fixes it,
        // but clamp here so the tune is valid on its own.
        int lo = 99, hi = -1;
        for (Ringtone.Command c : r.commands) {
            if (c.note.note == Ringtone.PAUSE) continue;
            lo = Math.min(lo, c.note.scale);
            hi = Math.max(hi, c.note.scale);
        }
        int shift = lo < 4 ? 4 - lo : hi > 7 ? Math.max(7 - hi, 4 - lo) : 0;
        for (Ringtone.Command c : r.commands) {
            if (c.note.note == Ringtone.PAUSE) c.note.scale = Ringtone.SCALE_880;
            else c.note.scale = Math.max(4, Math.min(7, c.note.scale + shift));
        }
        return r;
    }

    private static void addLength(Ringtone r, int note, int scale, double beats, int bpm) {
        // Longer than a dotted whole note: split.
        while (beats > 6.2 && r.commands.size() < Ringtone.MAX_NOTES) {
            r.commands.add(Ringtone.note(Ringtone.makeNote(note, scale, Ringtone.DUR_FULL, Ringtone.DOTTED,
                    Ringtone.NATURAL, bpm)));
            beats -= 6;
        }
        if (r.commands.size() >= Ringtone.MAX_NOTES) return;
        int[] q = quantize(beats);
        r.commands.add(Ringtone.note(Ringtone.makeNote(note, scale, q[0], q[1], Ringtone.NATURAL, bpm)));
    }
}
