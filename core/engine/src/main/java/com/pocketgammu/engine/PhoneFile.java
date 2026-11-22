package com.pocketgammu.engine;

import java.util.Calendar;

/** GSM_File: one file or folder on a Series 40 phone. */
public final class PhoneFile {
    /** GSM_FileType values this app distinguishes. */
    public enum Type { OTHER, JPG, BMP, PNG, GIF, WBMP, AMR, MIDI, NRT, VIDEO_3GP, JAR, MMS }

    public String id = "";
    public String name = "";
    public boolean folder;
    public boolean readOnly, hidden, system, protect;
    public long used;
    public int level;
    public Type type = Type.OTHER;
    /** Modified time as {year, month, day, hour, minute, second}, or null ("ModifiedEmpty"). */
    public int[] modified;
    public byte[] buffer;

    public PhoneFile() {}

    public PhoneFile(String id) {
        this.id = id;
    }

    public PhoneFile copy() {
        PhoneFile f = new PhoneFile(id);
        f.name = name;
        f.folder = folder;
        f.readOnly = readOnly;
        f.hidden = hidden;
        f.system = system;
        f.protect = protect;
        f.used = used;
        f.level = level;
        f.type = type;
        f.modified = modified == null ? null : modified.clone();
        f.buffer = buffer;
        return f;
    }

    /** GSM_IdentifyFileFormat. */
    public static Type identify(byte[] b) {
        if (b.length > 2) {
            if (b[0] == 'B' && b[1] == 'M') return Type.BMP;
            if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return Type.GIF;
            if (b[0] == 0 && b[1] == 0) return Type.WBMP;
            if (b.length > 3 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return Type.PNG;
            if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return Type.JPG;
            if (b.length > 3 && b[0] == 'M' && b[1] == 'T' && b[2] == 'h' && b[3] == 'd') return Type.MIDI;
            if (b[0] == 0 && b[1] == 2) return Type.NRT;
        }
        return Type.OTHER;
    }

    /** Fill_GSM_DateTime: local time of a Java timestamp. */
    public static int[] dateOf(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return new int[] {c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND)};
    }

    public String modifiedText() {
        if (modified == null) return "";
        return String.format("%04d-%02d-%02d %02d:%02d", modified[0], modified[1], modified[2], modified[3], modified[4]);
    }

    @Override
    public String toString() {
        return (folder ? "[" + name + "]" : name + " (" + used + " bytes)") + "  " + id;
    }
}
