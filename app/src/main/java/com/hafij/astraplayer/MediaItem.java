package com.hafij.astraplayer;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** One entry in the queue or a saved playlist. */
public final class MediaItem {
    public static final int LOCAL = 0;
    public static final int STREAM = 1;
    public static final int YOUTUBE = 2;

    /** File path, content:// uri or stream URL. For YouTube, the watch page URL. */
    public final String uri;
    public final String title;
    public final int kind;
    /** Extra text shown under the title (channel, folder). */
    public final String subtitle;
    /** Thumbnail URL (YouTube only). */
    public final String thumb;
    /** Duration in ms, or 0 when unknown. */
    public final long durationMs;

    public MediaItem(String uri, String title, int kind, String subtitle, String thumb, long durationMs) {
        this.uri = uri;
        this.title = title == null || title.isEmpty() ? guessTitle(uri) : title;
        this.kind = kind;
        this.subtitle = subtitle == null ? "" : subtitle;
        this.thumb = thumb;
        this.durationMs = durationMs;
    }

    public static MediaItem file(File f) {
        return new MediaItem(f.getAbsolutePath(), f.getName(), LOCAL, f.getParentFile() == null ? "" : f.getParentFile().getName(), null, 0);
    }

    /** Builds an item from a user-typed or intent URL, detecting YouTube links. */
    public static MediaItem fromUrl(String url) {
        url = url.trim();
        if (YouTube.isYouTubeUrl(url)) return new MediaItem(url, null, YOUTUBE, "YouTube", null, 0);
        if (url.startsWith("/")) return file(new File(url));
        if (url.startsWith("file://")) return file(new File(Uri.parse(url).getPath()));
        if (url.startsWith("content://")) return new MediaItem(url, null, LOCAL, "", null, 0);
        return new MediaItem(url, null, STREAM, Uri.parse(url).getHost(), null, 0);
    }

    public boolean isLocalPath() {
        return kind == LOCAL && uri.startsWith("/");
    }

    /** Recording and saving are refused for sites whose terms forbid it. */
    public boolean savingAllowed() {
        return kind != YOUTUBE;
    }

    public static String guessTitle(String uri) {
        if (uri == null) return "";
        String s = uri;
        int q = s.indexOf('?');
        if (q > 0 && !s.startsWith("/")) s = s.substring(0, q);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int slash = s.lastIndexOf('/');
        String name = slash >= 0 ? s.substring(slash + 1) : s;
        name = Uri.decode(name);
        return name.isEmpty() ? uri : name;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("uri", uri);
            o.put("title", title);
            o.put("kind", kind);
            o.put("sub", subtitle);
            if (thumb != null) o.put("thumb", thumb);
            o.put("dur", durationMs);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static MediaItem fromJson(JSONObject o) {
        return new MediaItem(o.optString("uri"), o.optString("title"), o.optInt("kind"),
                o.optString("sub"), o.has("thumb") ? o.optString("thumb") : null, o.optLong("dur"));
    }

    public static JSONArray toJson(List<MediaItem> items) {
        JSONArray a = new JSONArray();
        for (MediaItem m : items) a.put(m.toJson());
        return a;
    }

    public static List<MediaItem> fromJson(JSONArray a) {
        List<MediaItem> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(fromJson(o));
        }
        return out;
    }
}
