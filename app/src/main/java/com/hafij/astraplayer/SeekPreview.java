package com.hafij.astraplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Frames for the seek-bar preview. One retriever stays open per file; only the newest
 * requested position is decoded, so dragging fast never queues up work.
 */
final class SeekPreview {
    private static final int WIDTH = 320;
    /** Frames are cached per 2-second bucket, so dragging back and forth is instant. */
    private static final long BUCKET_MS = 2000;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicLong wanted = new AtomicLong(-1);
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final LruCache<Long, Bitmap> cache = new LruCache<Long, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(Long key, Bitmap value) {
            return value.getByteCount();
        }
    };

    // Touched only on the worker thread.
    private MediaMetadataRetriever retriever;
    private String openSource;
    private boolean failed;

    private volatile String source;
    private BiConsumer<Long, Bitmap> listener;

    SeekPreview(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Previews are offered for local files and content URIs, not for network streams. */
    static boolean supports(MediaItem item) {
        return item != null && item.kind == MediaItem.LOCAL && !MediaFiles.isAudio(item.title);
    }

    /** Sets the file to preview; {@code null} turns previews off. */
    void setSource(MediaItem item) {
        String s = supports(item) ? item.uri : null;
        if (s == null ? source == null : s.equals(source)) return;
        source = s;
        cache.evictAll();
    }

    boolean active() {
        return source != null;
    }

    /** {@code onFrame} gets the requested time and its frame on the main thread. */
    void setListener(BiConsumer<Long, Bitmap> onFrame) {
        listener = onFrame;
    }

    /** Returns a cached frame at once when there is one, and decodes in the background otherwise. */
    Bitmap request(long ms) {
        if (source == null) return null;
        Bitmap hit = cache.get(ms / BUCKET_MS);
        if (hit != null) return hit;
        wanted.set(ms);
        if (busy.compareAndSet(false, true)) worker.execute(this::drain);
        return null;
    }

    private void drain() {
        while (true) {
            long t = wanted.getAndSet(-1);
            if (t >= 0) {
                Bitmap b = grab(t);
                if (b != null) {
                    cache.put(t / BUCKET_MS, b);
                    final long ft = t;
                    main.post(() -> {
                        if (listener != null) listener.accept(ft, b);
                    });
                }
                continue;
            }
            busy.set(false);
            // A request may have arrived between the check and clearing the flag.
            if (wanted.get() < 0 || !busy.compareAndSet(false, true)) return;
        }
    }

    private Bitmap grab(long ms) {
        String s = source;
        if (s == null) return null;
        try {
            if (!s.equals(openSource)) {
                closeRetriever();
                openSource = s;
                failed = false;
                retriever = new MediaMetadataRetriever();
                if (s.startsWith("content://")) retriever.setDataSource(context, Uri.parse(s));
                else retriever.setDataSource(s);
            }
            if (failed || retriever == null) return null;
            long us = ms * 1000;
            Bitmap b;
            if (Build.VERSION.SDK_INT >= 27) {
                String ws = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String hs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                int w = ws == null ? 0 : Integer.parseInt(ws);
                int h = hs == null ? 0 : Integer.parseInt(hs);
                if (w > 0 && h > 0) {
                    int tw = Math.min(WIDTH, w);
                    b = retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, Math.max(1, tw * h / w));
                } else {
                    b = retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                }
            } else {
                b = retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (b != null && b.getWidth() > WIDTH) {
                    Bitmap sc = Bitmap.createScaledBitmap(b, WIDTH, Math.max(1, WIDTH * b.getHeight() / b.getWidth()), true);
                    if (sc != b) b.recycle();
                    b = sc;
                }
            }
            return b;
        } catch (Exception | OutOfMemoryError e) {
            // Formats Android cannot decode (WMV, RealVideo…): stop trying for this file.
            failed = true;
            return null;
        }
    }

    private void closeRetriever() {
        if (retriever != null) {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
            retriever = null;
        }
        openSource = null;
    }

    void release() {
        source = null;
        listener = null;
        worker.execute(this::closeRetriever);
        worker.shutdown();
    }
}
