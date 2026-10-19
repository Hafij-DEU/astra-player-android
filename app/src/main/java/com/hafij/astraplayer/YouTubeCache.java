package com.hafij.astraplayer;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * On-disk store of YouTube videos cached for offline playback: one folder per video id
 * (root/&lt;id&gt;/meta.json plus the media files). It only reads and writes files; the
 * download itself happens elsewhere. No Android imports so it runs in plain JVM tests.
 */
public final class YouTubeCache {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final String META = "meta.json";
    private static final String NOMEDIA = ".nomedia";

    public static final class Entry {
        public String id, url, title, uploader, thumbUrl;
        public long durationMs;
        /** Video height of the cached copy, e.g. 720; 0 = audio only. */
        public int height;
        /** File name inside dir(id), e.g. "video.mp4" (required). */
        public String videoFile;
        /** E.g. "audio.m4a", or null when the video file has sound. */
        public String audioFile;
        /** E.g. "thumb.jpg", or null. */
        public String thumbFile;
        /** Total size of the media files. */
        public long bytes;
        /** Epoch ms. */
        public long cachedAt;
        public boolean complete;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                putOpt(o, "url", url);
                putOpt(o, "title", title);
                putOpt(o, "uploader", uploader);
                putOpt(o, "thumbUrl", thumbUrl);
                o.put("durationMs", durationMs);
                o.put("height", height);
                putOpt(o, "videoFile", videoFile);
                putOpt(o, "audioFile", audioFile);
                putOpt(o, "thumbFile", thumbFile);
                o.put("bytes", bytes);
                o.put("cachedAt", cachedAt);
                o.put("complete", complete);
            } catch (Exception ignored) {
            }
            return o;
        }

        /** Tolerant of missing optional fields; absent strings stay null rather than "". */
        public static Entry fromJson(JSONObject o) {
            Entry e = new Entry();
            e.id = str(o, "id");
            e.url = str(o, "url");
            e.title = str(o, "title");
            e.uploader = str(o, "uploader");
            e.thumbUrl = str(o, "thumbUrl");
            e.durationMs = o.optLong("durationMs", 0);
            e.height = o.optInt("height", 0);
            e.videoFile = str(o, "videoFile");
            e.audioFile = str(o, "audioFile");
            e.thumbFile = str(o, "thumbFile");
            e.bytes = o.optLong("bytes", 0);
            e.cachedAt = o.optLong("cachedAt", 0);
            e.complete = o.optBoolean("complete", false);
            return e;
        }

        /** Size as "38 MB" / "1.2 GB" / "512 KB". */
        public static String formatBytes(long b) {
            if (b < 1024) return b + " B";
            // Compare the rounded value so 1023.6 KB shows as "1 MB", not "1024 KB".
            long kb = Math.round(b / 1024.0);
            if (kb < 1024) return kb + " KB";
            long mb = Math.round(b / (1024.0 * 1024));
            if (mb < 1024) return mb + " MB";
            return String.format(Locale.US, "%.1f GB", b / (1024.0 * 1024 * 1024));
        }

        private static void putOpt(JSONObject o, String k, String v) throws Exception {
            if (v != null) o.put(k, v);
        }

        private static String str(JSONObject o, String k) {
            if (!o.has(k) || o.isNull(k)) return null;
            String s = o.optString(k, null);
            return s == null || s.isEmpty() ? null : s;
        }
    }

    private final File root;

    /** The root folder is created lazily, so constructing the store never touches the disk. */
    public YouTubeCache(File root) {
        this.root = root;
    }

    public File root() {
        return root;
    }

    /**
     * 11-character YouTube video id from watch, youtu.be, shorts, embed, live, music.youtube
     * and m.youtube URLs (also with extra query params / timestamps), or null. Pure string
     * parsing, no network.
     */
    public static String videoId(String url) {
        if (url == null) return null;
        String s = url.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int frag = s.indexOf('#');
        if (frag >= 0) s = s.substring(0, frag);

        int cut = s.length();
        for (char c : new char[] {'/', '?'}) {
            int i = s.indexOf(c);
            if (i >= 0 && i < cut) cut = i;
        }
        String host = s.substring(0, cut).toLowerCase(Locale.US);
        int at = host.lastIndexOf('@');
        if (at >= 0) host = host.substring(at + 1);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        String rest = s.substring(cut);
        String path = rest;
        String query = "";
        int q = rest.indexOf('?');
        if (q >= 0) {
            path = rest.substring(0, q);
            query = rest.substring(q + 1);
        }

        String candidate = null;
        if (host.equals("youtu.be") || host.equals("www.youtu.be")) {
            candidate = firstSegment(path);
        } else if (isYouTubeHost(host)) {
            String[] parts = path.split("/");
            List<String> segs = new ArrayList<>();
            for (String p : parts) if (!p.isEmpty()) segs.add(p);
            if (segs.isEmpty()) return null;
            String first = segs.get(0).toLowerCase(Locale.US);
            if (first.equals("watch")) {
                candidate = queryParam(query, "v");
            } else if (segs.size() >= 2 && (first.equals("shorts") || first.equals("embed")
                    || first.equals("live") || first.equals("v") || first.equals("e"))) {
                candidate = segs.get(1);
                // "/embed/videoseries?list=..." is a playlist player; the word happens to be 11 chars.
                if (candidate.equals("videoseries")) return null;
            }
        }
        if (candidate == null) return null;
        return ID.matcher(candidate).matches() ? candidate : null;
    }

    private static boolean isYouTubeHost(String host) {
        return host.equals("youtube.com") || host.endsWith(".youtube.com")
                || host.equals("youtube-nocookie.com") || host.endsWith(".youtube-nocookie.com");
    }

    private static String firstSegment(String path) {
        for (String p : path.split("/")) if (!p.isEmpty()) return p;
        return null;
    }

    private static String queryParam(String query, String name) {
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(name)) return kv.substring(eq + 1);
        }
        return null;
    }

    private static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** Folder for a video id (not created). Rejects anything that could escape root. */
    public File dir(String id) {
        if (!validId(id)) throw new IllegalArgumentException("invalid video id: " + id);
        return new File(root, id);
    }

    /**
     * The complete, playable entry for an id, or null. Null when meta.json is
     * missing/unparseable, not complete, or a referenced media file is missing.
     */
    public synchronized Entry get(String id) {
        if (!validId(id)) return null;
        try {
            Entry e = readMeta(new File(root, id));
            return e != null && playable(e) ? e : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /** Every complete entry, newest cachedAt first. Ignores folders without valid meta. */
    public synchronized List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        try {
            File[] dirs = root.listFiles();
            if (dirs == null) return out;
            for (File d : dirs) {
                if (!d.isDirectory() || !validId(d.getName())) continue;
                Entry e = readMeta(d);
                if (e != null && playable(e)) out.add(e);
            }
            out.sort((a, b) -> Long.compare(b.cachedAt, a.cachedAt));
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Sum of all file sizes under root (including partial downloads). */
    public synchronized long totalBytes() {
        try {
            return size(root);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Writes dir(e.id)/meta.json atomically (write meta.json.tmp then rename). Creates the folder. */
    public synchronized void save(Entry e) throws IOException {
        if (e == null || !validId(e.id)) throw new IOException("invalid video id: " + (e == null ? null : e.id));
        ensureRoot();
        File d = new File(root, e.id);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("cannot create " + d);
        File tmp = new File(d, META + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(e.toJson().toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        try {
            Files.move(tmp.toPath(), new File(d, META).toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            // Some file systems refuse ATOMIC_MOVE; a plain replace is still better than nothing.
            Files.move(tmp.toPath(), new File(d, META).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Deletes the whole folder of an id (recursively). Returns true if something was removed. */
    public synchronized boolean delete(String id) {
        if (!validId(id)) return false;
        File d = new File(root, id);
        if (!exists(d)) return false;
        deleteTree(d);
        return !exists(d);
    }

    /** Deletes every video folder (keeps root and .nomedia). Returns how many videos were removed. */
    public synchronized int deleteAll() {
        int n = 0;
        File[] dirs = root.listFiles();
        if (dirs == null) return 0;
        for (File d : dirs) {
            if (!d.isDirectory() || !validId(d.getName())) continue;
            deleteTree(d);
            if (!exists(d)) n++;
        }
        return n;
    }

    /**
     * Removes folders that have no complete meta.json and whose newest file is older than
     * maxAgeMs (stale partial downloads). The age check keeps a download that is still running.
     * Returns count removed.
     */
    public synchronized int pruneIncomplete(long maxAgeMs, long nowMs) {
        int n = 0;
        File[] dirs = root.listFiles();
        if (dirs == null) return 0;
        for (File d : dirs) {
            if (!d.isDirectory() || !validId(d.getName())) continue;
            Entry e;
            try {
                e = readMeta(d);
            } catch (Exception ex) {
                e = null;
            }
            if (e != null && e.complete) continue;
            if (nowMs - newest(d) <= maxAgeMs) continue;
            deleteTree(d);
            if (!exists(d)) n++;
        }
        return n;
    }

    /** dir/videoFile. Throws IllegalArgumentException for an invalid id or a name that would leave the folder. */
    public File videoFile(Entry e) {
        if (!safeName(e.videoFile)) throw new IllegalArgumentException("invalid file name: " + e.videoFile);
        return new File(dir(e.id), e.videoFile);
    }

    /** dir/audioFile, or null when there is none (or the stored name would leave the folder). */
    public File audioFile(Entry e) {
        return safeName(e.audioFile) ? new File(dir(e.id), e.audioFile) : null;
    }

    /** dir/thumbFile, or null when there is none (or the stored name would leave the folder). */
    public File thumbFile(Entry e) {
        return safeName(e.thumbFile) ? new File(dir(e.id), e.thumbFile) : null;
    }

    /**
     * Writes .nomedia once so gallery apps do not index the cached videos. Best effort: a
     * missing marker must not stop the cache from working.
     */
    private void ensureRoot() throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("cannot create " + root);
        File nm = new File(root, NOMEDIA);
        try {
            if (!nm.exists()) nm.createNewFile();
        } catch (IOException ignored) {
        }
    }

    private static Entry readMeta(File d) {
        File f = new File(d, META);
        if (!f.isFile()) return null;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            Entry e = Entry.fromJson(o);
            // The folder name is the source of truth; a mismatched id would point at another video.
            if (!d.getName().equals(e.id)) return null;
            return e;
        } catch (Exception ex) {
            return null;
        }
    }

    /** Complete and every referenced media file is on disk; a missing thumbnail is not fatal. */
    private boolean playable(Entry e) {
        if (!e.complete || !safeName(e.videoFile)) return false;
        File d = new File(root, e.id);
        if (!new File(d, e.videoFile).isFile()) return false;
        if (e.audioFile == null) return true;
        return safeName(e.audioFile) && new File(d, e.audioFile).isFile();
    }

    /** Media file names must stay inside the video folder. */
    private static boolean safeName(String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0;
    }

    /** Symbolic links are never followed (a loop would recurse forever, a link could point outside root). */
    private static long size(File f) {
        if (isLink(f)) return 0;
        if (f.isFile()) return f.length();
        long sum = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) sum += size(k);
        return sum;
    }

    /**
     * Newest file modification time inside a folder. Folder mtimes are skipped because
     * creating a file bumps them; an empty folder falls back to its own mtime.
     */
    private static long newest(File d) {
        long t = newestFile(d);
        return t > 0 ? t : d.lastModified();
    }

    private static long newestFile(File f) {
        if (isLink(f)) return 0;
        if (f.isFile()) return f.lastModified();
        long t = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) t = Math.max(t, newestFile(k));
        return t;
    }

    /**
     * Recursive delete that removes a symbolic link itself instead of descending into it,
     * so a link inside the cache can never wipe files outside root.
     */
    private static void deleteTree(File f) {
        if (!isLink(f)) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        f.delete();
    }

    /** True for a symbolic link; an unreadable path counts as a link so we never descend into it. */
    private static boolean isLink(File f) {
        try {
            return Files.isSymbolicLink(f.toPath());
        } catch (Exception e) {
            return true;
        }
    }

    /** Exists without following links, so a dangling link still counts and can be removed. */
    private static boolean exists(File f) {
        try {
            return Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS);
        } catch (Exception e) {
            return f.exists();
        }
    }
}
