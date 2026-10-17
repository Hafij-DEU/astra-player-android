package com.hafij.astraplayer;

import java.io.File;
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
