package com.hafij.astraplayer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** File-type detection, folder listing and external subtitle discovery. */
public final class MediaFiles {
    private MediaFiles() {
    }

    private static final Set<String> VIDEO = new HashSet<>(Arrays.asList(
            "mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "flv", "f4v", "mpg", "mpeg", "mpe", "m2v",
            "m2ts", "mts", "ts", "tp", "trp", "vob", "3gp", "3g2", "ogv", "ogm", "rm", "rmvb", "asf", "divx",
            "xvid", "dv", "mxf", "nut", "nsv", "y4m", "h264", "h265", "hevc", "264", "265", "av1", "ivf"));
    private static final Set<String> AUDIO = new HashSet<>(Arrays.asList(
            "mp3", "m4a", "m4b", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "ape", "alac", "aiff",
            "aif", "amr", "awb", "mka", "wv", "dsf", "dff", "ac3", "eac3", "dts", "mpc", "tta", "mid", "midi",
            "mod", "xm", "it", "s3m", "spx", "caf", "au", "ra", "weba"));
    private static final Set<String> PLAYLIST = new HashSet<>(Arrays.asList(
            "m3u", "m3u8", "pls", "xspf", "cue", "asx", "wpl"));
    public static final Set<String> SUBTITLE = new HashSet<>(Arrays.asList(
            "srt", "ass", "ssa", "vtt", "sub", "smi", "sami", "idx", "usf", "jss", "psb", "rt", "mpl2", "sup", "ttml"));

    public static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isVideo(String name) { return VIDEO.contains(ext(name)); }
    public static boolean isAudio(String name) { return AUDIO.contains(ext(name)); }
    public static boolean isPlayable(String name) {
        String e = ext(name);
        return VIDEO.contains(e) || AUDIO.contains(e) || PLAYLIST.contains(e);
    }
    public static boolean isSubtitle(String name) { return SUBTITLE.contains(ext(name)); }

    private static String base(String name) {
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(0, dot)).toLowerCase(Locale.ROOT);
    }

    /** Sub-folders and playable files of a folder, folders first, both sorted by name. */
    public static List<File> list(File dir) {
        List<File> dirs = new ArrayList<>();
        List<File> files = new ArrayList<>();
        File[] all = dir.listFiles();
        if (all == null) return dirs;
        for (File f : all) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) dirs.add(f);
            else if (isPlayable(f.getName())) files.add(f);
        }
        dirs.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        files.sort((a, b) -> naturalCompare(a.getName(), b.getName()));
        dirs.addAll(files);
        return dirs;
    }

    /** Playable files directly inside a folder, in natural name order (for play-folder). */
    public static List<MediaItem> folderItems(File dir) {
        List<MediaItem> out = new ArrayList<>();
        for (File f : list(dir)) if (f.isFile()) out.add(MediaItem.file(f));
        return out;
    }

    /** A folder that directly holds at least one video. */
    public static final class VideoFolder {
        public final File dir;
        public final int videos;

        public VideoFolder(File dir, int videos) {
            this.dir = dir;
            this.videos = videos;
        }
    }

    /**
     * Walks the storage roots and returns every folder that directly holds videos. Hidden
     * folders and Android/data and Android/obb are skipped (Android/media is kept: WhatsApp
     * saves videos there). Canonical paths guard against symlink loops.
     */
    public static List<VideoFolder> findVideoFolders(List<File> roots) {
        List<VideoFolder> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ArrayDeque<File> stack = new ArrayDeque<>();
        for (int i = roots.size() - 1; i >= 0; i--) stack.push(roots.get(i));
        while (!stack.isEmpty()) {
            File dir = stack.pop();
            String canon;
            try {
                canon = dir.getCanonicalPath();
            } catch (IOException e) {
                canon = dir.getAbsolutePath();
            }
            if (!seen.add(canon)) continue;
            File[] kids = dir.listFiles();
            if (kids == null) continue;
            int videos = 0;
            List<File> subdirs = new ArrayList<>();
            for (File f : kids) {
                String name = f.getName();
                if (name.startsWith(".")) continue;
                if (f.isDirectory()) {
                    if (isSkippedAndroidDir(dir, name)) continue;
                    subdirs.add(f);
                } else if (isVideo(name)) {
                    videos++;
                }
            }
            if (videos > 0) out.add(new VideoFolder(dir, videos));
            for (int i = subdirs.size() - 1; i >= 0; i--) stack.push(subdirs.get(i));
        }
        out.sort((a, b) -> {
            int c = naturalCompare(a.dir.getName(), b.dir.getName());
            return c != 0 ? c : a.dir.getAbsolutePath().compareTo(b.dir.getAbsolutePath());
        });
        return out;
    }

    private static boolean isSkippedAndroidDir(File parent, String name) {
        if (!parent.getName().equals("Android")) return false;
        return name.equals("data") || name.equals("obb");
    }

    public static String toJson(List<VideoFolder> folders) {
        JSONArray a = new JSONArray();
        for (VideoFolder f : folders) {
            JSONObject o = new JSONObject();
            try {
                o.put("path", f.dir.getAbsolutePath());
                o.put("videos", f.videos);
            } catch (JSONException ignored) {
            }
            a.put(o);
        }
        return a.toString();
    }

    public static List<VideoFolder> foldersFromJson(String json) {
        List<VideoFolder> out = new ArrayList<>();
        if (json == null) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new VideoFolder(new File(o.getString("path")), o.optInt("videos")));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    /** Videos directly inside a folder, in natural name order. */
    public static List<File> videoFiles(File dir) {
        List<File> out = new ArrayList<>();
        File[] all = dir.listFiles();
        if (all == null) return out;
        for (File f : all) {
            if (f.isFile() && !f.getName().startsWith(".") && isVideo(f.getName())) out.add(f);
        }
        out.sort((a, b) -> naturalCompare(a.getName(), b.getName()));
        return out;
    }

    public static List<MediaItem> videoItems(File dir) {
        List<MediaItem> out = new ArrayList<>();
        for (File f : videoFiles(dir)) out.add(MediaItem.file(f));
        return out;
    }

    /**
     * External subtitles for a local video: files in the same folder (or a Subs/Subtitles
     * sub-folder) whose name starts with the video's base name. When the folder holds exactly
     * one video, every subtitle file there is taken.
     */
    public static List<File> findSubtitles(File video) {
        List<File> out = new ArrayList<>();
        File dir = video.getParentFile();
        if (dir == null) return out;
        String base = base(video.getName());
        List<File> candidates = new ArrayList<>();
        int videos = 0;
        File[] here = dir.listFiles();
        if (here == null) return out;
        for (File f : here) {
            if (f.isFile() && isSubtitle(f.getName())) candidates.add(f);
            else if (f.isFile() && isVideo(f.getName())) videos++;
            else if (f.isDirectory()) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (n.equals("subs") || n.equals("sub") || n.equals("subtitles")) {
                    File[] inner = f.listFiles();
                    if (inner != null) for (File g : inner) if (g.isFile() && isSubtitle(g.getName())) candidates.add(g);
                }
            }
        }
        for (File f : candidates) {
            String b = base(f.getName());
            // .idx/.sub pairs: libVLC wants only the .idx.
            if (ext(f.getName()).equals("sub") && new File(f.getParentFile(), f.getName().replaceAll("(?i)\\.sub$", ".idx")).exists()) continue;
            if (b.startsWith(base) || videos == 1) out.add(f);
        }
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    /** "Episode 2" sorts before "Episode 10". */
    public static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                int c = Character.toLowerCase(ca) - Character.toLowerCase(cb);
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    public static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }
}
