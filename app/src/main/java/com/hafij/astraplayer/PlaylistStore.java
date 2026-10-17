package com.hafij.astraplayer;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Saved playlists, kept as one JSON file in app storage. */
public final class PlaylistStore {
    public static final class Playlist {
        public final String name;
        public final List<MediaItem> items;

        Playlist(String name, List<MediaItem> items) {
            this.name = name;
            this.items = items;
        }
    }

    private final File file;

    public PlaylistStore(Context c) {
        file = new File(c.getFilesDir(), "playlists.json");
    }

    public synchronized List<Playlist> all() {
        List<Playlist> out = new ArrayList<>();
        if (!file.exists()) return out;
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Playlist(o.getString("name"), MediaItem.fromJson(o.optJSONArray("items"))));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public synchronized Playlist get(String name) {
        for (Playlist p : all()) if (p.name.equals(name)) return p;
        return null;
    }

    /** Creates or replaces a playlist. */
    public synchronized void save(String name, List<MediaItem> items) {
        List<Playlist> all = all();
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).name.equals(name)) {
                all.set(i, new Playlist(name, new ArrayList<>(items)));
                replaced = true;
            }
        }
        if (!replaced) all.add(new Playlist(name, new ArrayList<>(items)));
        write(all);
    }

    public synchronized void append(String name, List<MediaItem> items) {
        Playlist p = get(name);
        List<MediaItem> merged = p == null ? new ArrayList<>() : new ArrayList<>(p.items);
        merged.addAll(items);
        save(name, merged);
    }

    public synchronized void removeItem(String name, int index) {
        Playlist p = get(name);
        if (p == null || index < 0 || index >= p.items.size()) return;
        List<MediaItem> items = new ArrayList<>(p.items);
        items.remove(index);
        save(name, items);
    }

    public synchronized void delete(String name) {
        List<Playlist> all = all();
        all.removeIf(p -> p.name.equals(name));
        write(all);
    }

    private void write(List<Playlist> all) {
        JSONArray a = new JSONArray();
        try {
            for (Playlist p : all) {
                JSONObject o = new JSONObject();
                o.put("name", p.name);
                o.put("items", MediaItem.toJson(p.items));
                a.put(o);
            }
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(file)) throw new IllegalStateException("rename failed");
        } catch (Exception ignored) {
        }
    }
}
