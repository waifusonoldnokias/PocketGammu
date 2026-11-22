package com.pocketgammu.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Higher-level file manager operations built from Gammu's primitives
 * (getfiles, addfile, addfolder, deletefiles, deletefolder). Series 40 phones
 * have no rename or copy command, so rename, copy and move are done the way
 * a careful user would by hand: copy first, check, then delete the original.
 */
public final class S40FileOps {
    public interface Listener {
        /** Called for every file or folder as it is processed. */
        void onStep(String what, long done, long total);
    }

    private final S40Files fs;
    private final Listener listener;

    public S40FileOps(S40Files fs, Listener listener) {
        this.fs = fs;
        this.listener = listener;
    }

    private void step(String what, long done, long total) {
        if (listener != null) listener.onStep(what, done, total);
    }

    private S40Files.Progress progress(final String what) {
        return new S40Files.Progress() {
            @Override
            public void onProgress(long done, long total) {
                step(what, done, total);
            }
        };
    }

    /** All entries of a folder, following ERR_FOLDERPART the way the listing UI does. */
    public List<PhoneFile> children(String folderId) throws IOException {
        return fs.list(folderId, new boolean[1]);
    }

    /** Names already used in a folder (compared case-insensitively, as the phones do). */
    public Set<String> names(String folderId) throws IOException {
        Set<String> out = new HashSet<>();
        for (PhoneFile f : children(folderId)) out.add(f.name.toLowerCase());
        return out;
    }

    /** "name.ext" -> "name (2).ext" ... until it is free in the folder. */
    public static String freeName(String name, Set<String> taken) {
        if (!taken.contains(name.toLowerCase())) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            String n = base + " (" + i + ")" + ext;
            if (!taken.contains(n.toLowerCase())) return n;
        }
    }

    /** Copies a file or a whole folder tree into destFolderId under newName. Returns the new ID. */
    public String copy(PhoneFile src, String destFolderId, String newName) throws IOException {
        Set<String> created = new HashSet<>();
        return copyInto(src, destFolderId, newName, created, 0);
    }

    private String copyInto(PhoneFile src, String destFolderId, String name, Set<String> created, int depth)
            throws IOException {
        if (depth > 16) throw new GsmException(GsmError.INVALIDDATA, "folders nested too deeply");
        if (!src.folder) {
            PhoneFile got = fs.download(src.id, progress("Reading " + src.name));
            String id = fs.upload(destFolderId, name, got.buffer,
                    src.modified != null ? src.modified : got.modified, progress("Writing " + name));
            created.add(id);
            return id;
        }
        List<PhoneFile> kids = children(src.id);
        String id = fs.addFolder(destFolderId, name);
        created.add(id);
        step("Created folder " + name, 0, 0);
        for (PhoneFile k : kids) {
            // The new folder can show up inside the one being copied when copying into itself.
            if (created.contains(k.id)) continue;
            copyInto(k, id, k.name, created, depth + 1);
        }
        return id;
    }

    /** Deletes a file, or a folder with everything in it. */
    public void deleteTree(PhoneFile f) throws IOException {
        deleteTree(f, 0);
    }

    private void deleteTree(PhoneFile f, int depth) throws IOException {
        if (depth > 16) throw new GsmException(GsmError.INVALIDDATA, "folders nested too deeply");
        if (!f.folder) {
            step("Deleting " + f.name, 0, 0);
            fs.deleteFile(f.id);
            return;
        }
        for (PhoneFile k : children(f.id)) deleteTree(k, depth + 1);
        step("Deleting folder " + f.name, 0, 0);
        fs.deleteFolder(f.id);
    }

    /** Moves src from its folder into destFolderId (copy, then delete the original). */
    public String move(PhoneFile src, String srcFolderId, String destFolderId, String newName) throws IOException {
        if (srcFolderId != null && srcFolderId.equals(destFolderId) && src.name.equals(newName)) return src.id;
        if (src.folder && isInside(destFolderId, src)) {
            throw new GsmException(GsmError.INVALIDDATA, "a folder cannot be moved into itself");
        }
        String id = copy(src, destFolderId, newName);
        deleteTree(src);
        return id;
    }

    /** Renames in place. */
    public String rename(PhoneFile f, String folderId, String newName) throws IOException {
        if (newName.equals(f.name)) return f.id;
        Set<String> taken = names(folderId);
        taken.remove(f.name.toLowerCase());
        if (taken.contains(newName.toLowerCase())) {
            throw new GsmException(GsmError.INVALIDDATA, "\"" + newName + "\" already exists in this folder");
        }
        String id = copy(f, folderId, newName);
        deleteTree(f);
        return id;
    }

    /** True if folderId is folder f or somewhere below it. */
    public boolean isInside(String folderId, PhoneFile f) throws IOException {
        if (folderId.equals(f.id)) return true;
        // Filesystem 2 IDs are paths.
        if (!folderId.startsWith("c") && !f.id.startsWith("c")) {
            String p = f.id.endsWith("/") ? f.id : f.id + "/";
            return folderId.startsWith(p);
        }
        // Filesystem 1 IDs are numbers: walk the tree under f.
        List<PhoneFile> todo = new ArrayList<>();
        todo.add(f);
        int guard = 0;
        while (!todo.isEmpty() && guard++ < 500) {
            PhoneFile d = todo.remove(todo.size() - 1);
            for (PhoneFile k : children(d.id)) {
                if (!k.folder) continue;
                if (k.id.equals(folderId)) return true;
                todo.add(k);
            }
        }
        return false;
    }
}
