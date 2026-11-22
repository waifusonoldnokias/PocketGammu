package com.pocketgammu.engine;

/**
 * Renders a note tune as soft sine waves, the way the phone's buzzer would
 * time it: Natural style leaves a short gap after each note, Staccato plays
 * half of it, Continuous runs notes together. Each note fades in and out over
 * a few milliseconds so there are no clicks.
 */
public final class ToneSynth {
    public static final int RATE = 22050;

    private ToneSynth() {}

    /** Start time of each note in the rendered audio, in ms (for a moving playhead). */
    public static double[] noteStarts(Ringtone r) {
        double[] out = new double[r.commands.size()];
        double t = 0;
        for (int i = 0; i < r.commands.size(); i++) {
            out[i] = t;
            Ringtone.Command c = r.commands.get(i);
            if (c.type == Ringtone.CommandType.NOTE) t += Ringtone.millis(c.note);
        }
        return out;
    }

    public static short[] render(Ringtone r, double volume) {
        double total = r.totalMillis() + 120;
        int n = (int) Math.min((long) (total * RATE / 1000), RATE * 600L);
        short[] pcm = new short[n];
        double t = 0;
        double phase = 0;
        for (Ringtone.Command c : r.commands) {
            if (c.type != Ringtone.CommandType.NOTE) continue;
            Ringtone.Note note = c.note;
            double ms = Ringtone.millis(note);
            int start = (int) (t * RATE / 1000);
            int len = (int) (ms * RATE / 1000);
            t += ms;
            double f = Ringtone.frequency(note);
            if (f <= 0) {
                phase = 0;
                continue;
            }
            int sound = len;
            if (note.style == Ringtone.NATURAL) sound = Math.max(1, len - Math.min(len / 8, RATE * 30 / 1000));
            else if (note.style == Ringtone.STACCATO) sound = Math.max(1, len / 2);
            int fade = Math.min(RATE * 6 / 1000, sound / 3);
            boolean legato = note.style == Ringtone.CONTINUOUS;
            double step = 2 * Math.PI * f / RATE;
            for (int i = 0; i < sound && start + i < n; i++) {
                double env = 1;
                if (!legato || i < fade) env = Math.min(1, i / (double) Math.max(1, fade));
                int left = sound - i;
                if (left < fade) env = Math.min(env, left / (double) Math.max(1, fade));
                double v = Math.sin(phase);
                pcm[start + i] = (short) Math.round(v * env * volume * 32767);
                phase += step;
                if (phase > 2 * Math.PI) phase -= 2 * Math.PI;
            }
        }
        return pcm;
    }
}
