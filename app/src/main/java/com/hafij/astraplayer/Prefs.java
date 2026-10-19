package com.hafij.astraplayer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Settings, resume positions and stream history. */
public final class Prefs {
    // Subtitle sizes map to libVLC's --freetype-rel-fontsize (a larger number is a smaller font).
    public static final String[] SUB_SIZE_NAMES = {"Small", "Normal", "Large", "Larger"};
    public static final int[] SUB_SIZE_VALUES = {20, 16, 12, 9};
    public static final String[] SUB_COLOR_NAMES = {"White", "Yellow", "Amber", "Cyan", "Green"};
    public static final int[] SUB_COLOR_VALUES = {0xFFFFFF, 0xFFFF55, 0xF2A516, 0x66E0FF, 0x7CFC8A};
    public static final String[] SUB_BG_NAMES = {"None", "Shadow box", "Solid box"};
    public static final int[] SUB_BG_VALUES = {0, 128, 230};
    public static final String[] YT_QUALITY_NAMES = {"360p", "480p", "720p", "1080p", "1440p", "2160p"};
    public static final int[] YT_QUALITY_VALUES = {360, 480, 720, 1080, 1440, 2160};

    public static final int THEME_SYSTEM = 0, THEME_LIGHT = 1, THEME_DARK = 2;
    public static final String[] THEME_NAMES = {"System default", "Light", "Dark"};

    public static final int FILES_FOLDERS = 0, FILES_ALL = 1, FILES_BROWSE = 2;
    public static final String[] FILES_VIEW_NAMES = {"Video folders", "All videos", "Browse storage"};
    public static final String[] FILES_VIEW_HINTS = {
            "Only folders that hold videos, with their full path",
            "Every video in one list",
            "All folders, starting from the storage root"};

    public static final int KIND_VIDEO = 0, KIND_MUSIC = 1;
    public static final String[] MUSIC_VIEW_NAMES = {"Music folders", "All songs", "Browse storage"};
    public static final String[] MUSIC_VIEW_HINTS = {
            "Only folders that hold music, with their full path",
            "Every song in one list",
            "All folders, starting from the storage root"};

    public static final int SORT_NAME = 0, SORT_DATE = 1, SORT_SIZE = 2;
    public static final String[] SORT_NAMES = {"Name", "Date modified", "Size / number of videos"};

    private static final int MAX_HISTORY = 30;
    private static final int MAX_RESUME = 400;

    private final SharedPreferences sp;
    private final SharedPreferences resume;

    public Prefs(Context c) {
        sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
        resume = c.getSharedPreferences("resume", Context.MODE_PRIVATE);
    }

    public int subSize() { return sp.getInt("sub_size", 1); }
    public int subColor() { return sp.getInt("sub_color", 0); }
    public int subBg() { return sp.getInt("sub_bg", 0); }
    public boolean subBold() { return sp.getBoolean("sub_bold", false); }

    public void setSubStyle(int size, int color, int bg, boolean bold) {
        sp.edit().putInt("sub_size", size).putInt("sub_color", color).putInt("sub_bg", bg)
                .putBoolean("sub_bold", bold).apply();
    }

    public boolean backgroundPlay() { return sp.getBoolean("background_play", true); }
    public void setBackgroundPlay(boolean v) { sp.edit().putBoolean("background_play", v).apply(); }

    public boolean hwDecoding() { return sp.getBoolean("hw_decoding", true); }
    public void setHwDecoding(boolean v) { sp.edit().putBoolean("hw_decoding", v).apply(); }

    public boolean resumeEnabled() { return sp.getBoolean("resume", true); }
    public void setResumeEnabled(boolean v) { sp.edit().putBoolean("resume", v).apply(); }

    public int ytQuality() { return sp.getInt("yt_quality", 1080); }
    public void setYtQuality(int v) { sp.edit().putInt("yt_quality", v).apply(); }

    public int repeatMode() { return sp.getInt("repeat", PlaybackService.REPEAT_ALL); }
    public void setRepeatMode(int v) { sp.edit().putInt("repeat", v).apply(); }

    public boolean shuffle() { return sp.getBoolean("shuffle", false); }
    public void setShuffle(boolean v) { sp.edit().putBoolean("shuffle", v).apply(); }

    public int theme() { return sp.getInt("theme", THEME_SYSTEM); }
    public void setTheme(int v) { sp.edit().putInt("theme", v).apply(); }

    public int filesView() { return sp.getInt("files_view", FILES_FOLDERS); }
    public void setFilesView(int v) { sp.edit().putInt("files_view", v).apply(); }

    /** JSON cache of the last video-folder scan, so the list shows instantly. */
    public String videoFolders() { return sp.getString("video_folders", null); }
    public void setVideoFolders(String json) { sp.edit().putString("video_folders", json).apply(); }

    /** "Use as music player": adds a Music library next to Videos in the Files tab. */
    public boolean musicLibrary() { return sp.getBoolean("music_library", false); }
    public void setMusicLibrary(boolean v) { sp.edit().putBoolean("music_library", v).apply(); }

    /** Which library the Files tab shows: KIND_VIDEO or KIND_MUSIC. */
    public int filesKind() { return musicLibrary() ? sp.getInt("files_kind", KIND_VIDEO) : KIND_VIDEO; }
    public void setFilesKind(int v) { sp.edit().putInt("files_kind", v).apply(); }

    public int musicView() { return sp.getInt("music_view", FILES_FOLDERS); }
    public void setMusicView(int v) { sp.edit().putInt("music_view", v).apply(); }

    /** The Files view of the library currently shown (folders, all, browse). */
    public int currentView() { return filesKind() == KIND_MUSIC ? musicView() : filesView(); }
    public void setCurrentView(int v) {
        if (filesKind() == KIND_MUSIC) setMusicView(v);
        else setFilesView(v);
    }

    public String folderCache(int kind) { return sp.getString(kind == KIND_MUSIC ? "music_folders" : "video_folders", null); }
    public void setFolderCache(int kind, String json) { sp.edit().putString(kind == KIND_MUSIC ? "music_folders" : "video_folders", json).apply(); }

    /** One-hand mode: search bars, toolbars and dialogs sit at the bottom, within thumb reach. */
    public boolean oneHand() { return sp.getBoolean("one_hand", true); }
    public void setOneHand(boolean v) { sp.edit().putBoolean("one_hand", v).apply(); }

    /** Offline cache: only download on unmetered networks (Wi-Fi). */
    public boolean cacheWifiOnly() { return sp.getBoolean("cache_wifi_only", true); }
    public void setCacheWifiOnly(boolean v) { sp.edit().putBoolean("cache_wifi_only", v).apply(); }

    /** Last resolution picked for caching, offered first next time. */
    public int cacheHeight() { return sp.getInt("cache_height", 720); }
    public void setCacheHeight(int v) { sp.edit().putInt("cache_height", v).apply(); }

    /** The one-time explanation before the first cache. */
    public boolean cacheNoticeShown() { return sp.getBoolean("cache_notice", false); }
    public void setCacheNoticeShown() { sp.edit().putBoolean("cache_notice", true).apply(); }

    public int sortMode() { return sp.getInt("sort_mode", SORT_NAME); }
    public boolean sortDescending() { return sp.getBoolean("sort_desc", false); }
    public void setSort(int mode, boolean descending) {
        sp.edit().putInt("sort_mode", mode).putBoolean("sort_desc", descending).apply();
    }

    public boolean showHidden() { return sp.getBoolean("show_hidden", false); }
    public void setShowHidden(boolean v) { sp.edit().putBoolean("show_hidden", v).apply(); }

    public boolean thumbnails() { return sp.getBoolean("thumbnails", true); }
    public void setThumbnails(boolean v) { sp.edit().putBoolean("thumbnails", v).apply(); }

    /** Absolute paths of folders and files the user hid from the Files tab. */
    public Set<String> hiddenPaths() { return stringSet("hidden_paths"); }
    public void setHiddenPaths(Set<String> paths) { putStringSet("hidden_paths", paths); }

    /** Lower-case extensions (without dot) never shown in the Files tab. */
    public Set<String> excludedExtensions() { return stringSet("excluded_ext"); }
    public void setExcludedExtensions(Set<String> exts) { putStringSet("excluded_ext", exts); }

    /** Filter built from the current hide/exclude settings. */
    public MediaFiles.Filter filter() {
        return new MediaFiles.Filter(hiddenPaths(), excludedExtensions(), showHidden());
    }

    // Stored as a JSON array: getStringSet returns a live set that must not be modified.
    private Set<String> stringSet(String key) {
        Set<String> out = new TreeSet<>();
        try {
            JSONArray a = new JSONArray(sp.getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    private void putStringSet(String key, Set<String> values) {
        sp.edit().putString(key, new JSONArray(new ArrayList<>(new TreeSet<>(values))).toString()).apply();
    }

    public boolean fontsReady() { return sp.getBoolean("fonts_ready", false); }
    public void setFontsReady() { sp.edit().putBoolean("fonts_ready", true).apply(); }

    public String lastFolder() { return sp.getString("last_folder", null); }
    public void setLastFolder(String path) { sp.edit().putString("last_folder", path).apply(); }

    public long resumePosition(String key) {
        return key == null ? 0 : resume.getLong(key, 0);
    }

    public void saveResume(String key, long ms) {
        if (key == null) return;
        SharedPreferences.Editor e = resume.edit();
        if (ms <= 0) {
            e.remove(key);
        } else {
            e.putLong(key, ms);
            // Keep the store bounded: drop arbitrary old entries once it grows large.
            Map<String, ?> all = resume.getAll();
            if (all.size() > MAX_RESUME) {
                int drop = all.size() - MAX_RESUME;
                for (String k : all.keySet()) {
                    if (drop-- <= 0) break;
                    if (!k.equals(key)) e.remove(k);
                }
            }
        }
        e.apply();
    }

    public List<String> history() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString("stream_history", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    public void addHistory(String url) {
        List<String> h = history();
        h.remove(url);
        h.add(0, url);
        while (h.size() > MAX_HISTORY) h.remove(h.size() - 1);
        sp.edit().putString("stream_history", new JSONArray(h).toString()).apply();
    }

    public void removeHistory(String url) {
        List<String> h = history();
        h.remove(url);
        sp.edit().putString("stream_history", new JSONArray(h).toString()).apply();
    }
}
