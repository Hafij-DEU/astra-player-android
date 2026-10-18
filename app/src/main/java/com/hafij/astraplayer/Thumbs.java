package com.hafij.astraplayer;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Video thumbnails and durations for local files, read in the background and cached in memory. */
public final class Thumbs {
    private Thumbs() {
    }

    private static final int WIDTH = 320;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(20 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    /** Duration in ms per file key; -1 when the file could not be read. */
    private static final Map<String, Long> DURATIONS = new ConcurrentHashMap<>();
    /** Keys whose frame could not be decoded (exotic codecs), so we do not retry. */
    private static final Map<String, Boolean> FAILED = new ConcurrentHashMap<>();

    private static String key(File f) {
        return f.getAbsolutePath() + "@" + f.lastModified();
    }

    public static long cachedDuration(File f) {
        Long d = DURATIONS.get(key(f));
        return d == null ? 0 : Math.max(0, d);
    }

    /**
     * Shows the file's thumbnail in {@code view} (keeping the placeholder when none can be
     * decoded) and its duration in {@code duration}, if given. Views are tagged so a recycled
     * row never shows another file's picture.
     */
    public static void load(ImageView view, TextView duration, File f, boolean thumbnails) {
        String k = key(f);
        view.setTag(R.id.thumb_key, k);
        if (duration != null) duration.setTag(R.id.thumb_key, k);
        Long d = DURATIONS.get(k);
        if (duration != null && d != null && d > 0) duration.setText(MediaFiles.formatTime(d));
        Bitmap cached = thumbnails ? CACHE.get(k) : null;
        if (cached != null) show(view, cached);
        boolean needThumb = thumbnails && cached == null && !FAILED.containsKey(k);
        boolean needDuration = duration != null && d == null;
        if (!needThumb && !needDuration) return;
        POOL.execute(() -> {
            Bitmap bmp = null;
            long dur = -1;
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(f.getAbsolutePath());
                String ds = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (ds != null) dur = Long.parseLong(ds);
                if (needThumb) bmp = MediaFiles.isAudio(f.getName()) ? albumArt(r) : frame(r);
            } catch (Exception ignored) {
            } finally {
                try {
                    r.release();
                } catch (Exception ignored) {
                }
            }
            DURATIONS.put(k, dur);
            if (needThumb) {
                if (bmp != null) CACHE.put(k, bmp);
                else FAILED.put(k, true);
            }
            final Bitmap fb = bmp;
            final long fd = dur;
            MAIN.post(() -> {
                if (fb != null && k.equals(view.getTag(R.id.thumb_key))) show(view, fb);
                if (duration != null && fd > 0 && k.equals(duration.getTag(R.id.thumb_key))) duration.setText(MediaFiles.formatTime(fd));
            });
        });
    }

    private static Bitmap albumArt(MediaMetadataRetriever r) {
        byte[] pic = r.getEmbeddedPicture();
        if (pic == null) return null;
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= WIDTH) sample *= 2;
        o = new android.graphics.BitmapFactory.Options();
        o.inSampleSize = sample;
        return android.graphics.BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
    }

    private static Bitmap frame(MediaMetadataRetriever r) {
        String ws = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
        String hs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
        int w = ws == null ? 0 : Integer.parseInt(ws);
        int h = hs == null ? 0 : Integer.parseInt(hs);
        // A frame a few seconds in is more telling than the (often black) first one.
        long at = 3_000_000L;
        Bitmap b;
        if (Build.VERSION.SDK_INT >= 27 && w > 0 && h > 0) {
            int tw = Math.min(WIDTH, w);
            int th = Math.max(1, tw * h / w);
            b = r.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, th);
        } else {
            b = r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (b != null && b.getWidth() > WIDTH) {
                Bitmap s = Bitmap.createScaledBitmap(b, WIDTH, Math.max(1, WIDTH * b.getHeight() / b.getWidth()), true);
                if (s != b) b.recycle();
                b = s;
            }
        }
        if (b == null) b = r.getFrameAtTime();
        return b;
    }

    private static void show(ImageView view, Bitmap b) {
        view.setImageTintList(null);
        view.setBackgroundTintList(null);
        view.setPadding(0, 0, 0, 0);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setImageBitmap(b);
    }

    /** Forgets a file (after rename or delete). */
    public static void forget(File f) {
        String k = key(f);
        CACHE.remove(k);
        DURATIONS.remove(k);
        FAILED.remove(k);
    }
}
