package com.hafij.astraplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads YouTube videos into the private offline cache, one at a time, as a foreground
 * service so a long playlist keeps going when the app is in the background. The queue is
 * kept in preferences, so it continues after the app or phone restarts.
 */
public class CacheService extends Service {
    private static final String TAG = "AstraCache";
    public static final String ACTION_START = "com.hafij.astraplayer.CACHE_START";
    public static final String ACTION_CANCEL = "com.hafij.astraplayer.CACHE_CANCEL";
    private static final String CHANNEL = "cache";
    private static final int NOTIFICATION_ID = 8;
    private static final long MIN_FREE_BYTES = 200L * 1024 * 1024;
    private static final int MAX_URL_REFRESHES = 3;

    public static final int QUEUED = 0, RUNNING = 1, FAILED = 2, DONE = 3;

    /** One video to cache. Fields are read by the UI thread, written by the worker. */
    public static final class Job {
        public final String id;
        public final String url;
        public final String title;
        public final int maxHeight;
        public volatile int state = QUEUED;
        public volatile long done;
        public volatile long total = -1;
        public volatile String error;

        Job(String id, String url, String title, int maxHeight) {
            this.id = id;
            this.url = url;
            this.title = title;
            this.maxHeight = maxHeight;
        }

        /** Whole percent, or -1 while the size is unknown. */
        public int percent() {
            long t = total;
            return t > 0 ? (int) Math.min(100, done * 100 / t) : -1;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LinkedHashMap<String, Job> JOBS = new LinkedHashMap<>();
    private static final List<Runnable> LISTENERS = new ArrayList<>();
    private static final AtomicBoolean CANCEL = new AtomicBoolean(false);
    private static YouTubeCache store;
    private static volatile boolean running;
    private static long lastNotify;

    private Thread worker;
    private int finished, failed;

    // ---- static API used by the screens and the player ------------------------------------

    /** The cache folder: app-private external storage (deleted with the app, hidden from the gallery). */
    public static synchronized YouTubeCache store(Context c) {
        if (store == null) {
            File base = c.getApplicationContext().getExternalFilesDir(null);
            if (base == null) base = c.getApplicationContext().getFilesDir();
            store = new YouTubeCache(new File(base, "yt-cache"));
        }
        return store;
    }

    /**
     * Queues the YouTube items of {@code items} at {@code maxHeight}. Videos already cached at
     * that height, or already queued, are skipped. Returns how many were added.
     */
    public static int enqueue(Context c, List<MediaItem> items, int maxHeight) {
        YouTubeCache st = store(c);
        int added = 0;
        synchronized (JOBS) {
            for (MediaItem m : items) {
                if (m.kind != MediaItem.YOUTUBE) continue;
                String id = YouTubeCache.videoId(m.uri);
                if (id == null) continue;
                Job existing = JOBS.get(id);
                if (existing != null && (existing.state == QUEUED || existing.state == RUNNING)) continue;
                // A copy at exactly this height is done. Any other height is re-cached; if the
                // resolver lands on the height already on disk, the download is skipped.
                YouTubeCache.Entry e = st.get(id);
                if (e != null && e.height == maxHeight) continue;
                JOBS.put(id, new Job(id, m.uri, m.title, maxHeight));
                added++;
            }
            saveQueue(c);
        }
        if (added > 0) start(c);
        changed();
        return added;
    }

    /** Auto-cache: queue YouTube videos just added to a playlist, if the user turned that on. */
    public static void onAddedToPlaylist(Context c, List<MediaItem> items) {
        Prefs p = new Prefs(c);
        if (p.autoCache() == Prefs.AUTO_CACHE_PLAYLISTS) enqueue(c, items, p.cacheHeight());
    }

    public static Job job(String id) {
        synchronized (JOBS) {
            return id == null ? null : JOBS.get(id);
        }
    }

    /** Queued and running jobs. */
    public static int pending() {
        int n = 0;
        synchronized (JOBS) {
            for (Job j : JOBS.values()) if (j.state == QUEUED || j.state == RUNNING) n++;
        }
        return n;
    }

    public static boolean busy() {
        return running && pending() > 0;
    }

    /** Stops caching; partial downloads stay on disk and resume next time. */
    public static void cancelAll(Context c) {
        synchronized (JOBS) {
            JOBS.values().removeIf(j -> j.state == QUEUED || j.state == RUNNING);
            saveQueue(c);
        }
        CANCEL.set(true);
        changed();
    }

    /** Forgets a queued job (for example because its cached copy was deleted). */
    public static void forget(Context c, String id) {
        synchronized (JOBS) {
            Job j = JOBS.get(id);
            if (j != null && j.state != RUNNING) JOBS.remove(id);
            saveQueue(c);
        }
        changed();
    }

    /** Listeners run on the main thread whenever a job starts, progresses or ends. */
    public static void addListener(Runnable r) {
        if (!LISTENERS.contains(r)) LISTENERS.add(r);
    }

    public static void removeListener(Runnable r) {
        LISTENERS.remove(r);
    }

    private static void changed() {
        MAIN.post(() -> {
            for (Runnable r : new ArrayList<>(LISTENERS)) r.run();
        });
    }

    /** Continues a queue left over from a previous run (app or phone restart). */
    public static void resumeQueue(Context c) {
        boolean any;
        synchronized (JOBS) {
            loadQueue(c);
            any = !JOBS.isEmpty();
        }
        if (any && !running) start(c);
    }

    private static void start(Context c) {
        Intent i = new Intent(c, CacheService.class).setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            // Android 12+ refuses foreground starts from the background; the queue is kept
            // and resumes the next time the app is opened.
            Log.w(TAG, "could not start cache service", e);
        }
    }

    private static SharedPreferences queuePrefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("yt_cache_queue", MODE_PRIVATE);
    }

    // Call with JOBS locked.
    private static void saveQueue(Context c) {
        JSONArray a = new JSONArray();
        for (Job j : JOBS.values()) {
            if (j.state != QUEUED && j.state != RUNNING) continue;
            try {
                a.put(new JSONObject().put("id", j.id).put("url", j.url).put("title", j.title).put("h", j.maxHeight));
            } catch (Exception ignored) {
            }
        }
        queuePrefs(c).edit().putString("queue", a.toString()).apply();
    }

    // Call with JOBS locked.
    private static void loadQueue(Context c) {
        try {
            JSONArray a = new JSONArray(queuePrefs(c).getString("queue", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String id = o.optString("id");
                if (!JOBS.containsKey(id)) JOBS.put(id, new Job(id, o.optString("url"), o.optString("title"), o.optInt("h", 720)));
            }
        } catch (Exception ignored) {
        }
    }

    // ---- service --------------------------------------------------------------------------

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Offline cache", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground(notification("Preparing offline cache…", 0, 0, true));
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelAll(this);
            if (worker == null) stopSelf();
            return START_NOT_STICKY;
        }
        synchronized (JOBS) {
            loadQueue(this);
        }
        if (worker == null) {
            CANCEL.set(false);
            running = true;
            finished = failed = 0;
            worker = new Thread(this::work, "astra-cache");
            worker.start();
        }
        return START_NOT_STICKY;
    }

    /** Android 15 limits data-sync services to 6 hours a day; stop cleanly and resume later. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        CANCEL.set(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        CANCEL.set(true);
        running = false;
        super.onDestroy();
    }

    private void goForeground(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIFICATION_ID, n);
        } catch (Exception e) {
            Log.w(TAG, "startForeground refused", e);
        }
    }

    private void work() {
        YouTubeCache st = store(this);
        st.pruneIncomplete(7L * 24 * 3600 * 1000, System.currentTimeMillis());
        while (!CANCEL.get()) {
            Job j = nextJob();
            if (j == null) break;
            j.state = RUNNING;
            j.error = null;
            changed();
            try {
                cacheOne(st, j);
                j.state = DONE;
                finished++;
            } catch (ChunkedDownloader.CancelledException e) {
                // Leave it queued so it resumes next time, unless the user cancelled it.
                j.state = CANCEL.get() && JOBS.containsKey(j.id) ? QUEUED : j.state;
                break;
            } catch (Exception e) {
                Log.w(TAG, "caching " + j.id + " failed", e);
                j.state = FAILED;
                j.error = describe(e);
                failed++;
            }
            synchronized (JOBS) {
                saveQueue(this);
            }
            changed();
        }
        running = false;
        worker = null;
        MAIN.post(this::finish);
    }

    private Job nextJob() {
        synchronized (JOBS) {
            for (Job j : JOBS.values()) if (j.state == QUEUED) return j;
        }
        return null;
    }

    private void finish() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (finished + failed > 0) {
            String text = finished + (finished == 1 ? " video ready offline" : " videos ready offline")
                    + (failed > 0 ? ", " + failed + " failed" : "");
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                    new Notification.Builder(this, CHANNEL)
                            .setSmallIcon(R.drawable.ic_save)
                            .setContentTitle("Offline cache")
                            .setContentText(text)
                            .setContentIntent(openApp())
                            .setAutoCancel(true)
                            .build());
        }
        changed();
        stopSelf();
    }

    private void cacheOne(YouTubeCache st, Job j) throws Exception {
        // The player may be caching this video while it plays: let it finish first.
        while (StreamCacheProxy.active(j.id)) {
            if (CANCEL.get()) throw new ChunkedDownloader.CancelledException();
            Thread.sleep(2000);
        }
        YouTubeCache.Entry existing = st.get(j.id);
        if (existing != null && existing.height == j.maxHeight) return;
        waitForAllowedNetwork(j);
        YouTube.CacheSource src = YouTube.resolveForCache(j.url, j.maxHeight);
        File dir = st.dir(j.id);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create cache folder");
        ChunkedDownloader dl = new ChunkedDownloader(YouTube.USER_AGENT);

        // Files are named by height, so an older copy stays playable until the new one is done.
        String videoName = "v" + src.height + "." + src.videoExt;
        String audioName = src.audioUrl == null ? null : "a." + src.audioExt;
        File video = new File(dir, videoName);
        File audio = audioName == null ? null : new File(dir, audioName);

        long vTotal = video.exists() ? video.length() : dl.probeLength(src.videoUrl);
        long aTotal = audio == null ? 0 : audio.exists() ? audio.length() : dl.probeLength(src.audioUrl);
        long need = Math.max(0, vTotal) + Math.max(0, aTotal);
        if (need > 0 && dir.getUsableSpace() < need + MIN_FREE_BYTES) {
            throw new IOException("Not enough free storage (needs " + YouTubeCache.Entry.formatBytes(need) + ")");
        }
        j.total = vTotal > 0 && aTotal >= 0 ? need : -1;

        String thumbName = null;
        if (src.thumb != null) {
            File thumb = new File(dir, "thumb.jpg");
            try {
                if (!thumb.exists() || thumb.length() == 0) {
                    File part = new File(dir, "thumb.jpg.part");
                    part.delete();
                    dl.download(src.thumb, part, null, CANCEL);
                    if (!part.renameTo(thumb)) part.delete();
                }
                if (thumb.exists()) thumbName = thumb.getName();
            } catch (ChunkedDownloader.CancelledException e) {
                throw e;
            } catch (IOException e) {
                Log.w(TAG, "thumbnail failed for " + j.id, e);
            }
        }

        String[] urls = {src.videoUrl, src.audioUrl};
        long[] base = {0, Math.max(0, vTotal)};
        File[] targets = {video, audio};
        for (int k = 0; k < 2; k++) {
            File target = targets[k];
            if (target == null || target.exists()) continue;
            File part = new File(target.getPath() + ".part");
            final long offset = base[k];
            int refreshes = 0;
            while (true) {
                try {
                    dl.download(urls[k], part, (done, total) -> {
                        j.done = offset + done;
                        progress(j);
                    }, CANCEL);
                    break;
                } catch (ChunkedDownloader.ExpiredException e) {
                    // googlevideo links expire after some hours: ask YouTube for fresh ones.
                    if (++refreshes > MAX_URL_REFRESHES) throw e;
                    waitForAllowedNetwork(j);
                    YouTube.CacheSource fresh = YouTube.resolveForCache(j.url, j.maxHeight);
                    if (fresh.height != src.height || !String.valueOf(fresh.audioExt).equals(String.valueOf(src.audioExt))) {
                        throw new IOException("YouTube changed the available formats; try again");
                    }
                    urls[0] = fresh.videoUrl;
                    urls[1] = fresh.audioUrl;
                }
            }
            if (!part.renameTo(target)) throw new IOException("Cannot finish " + target.getName());
        }

        YouTubeCache.Entry e = new YouTubeCache.Entry();
        e.id = j.id;
        e.url = j.url;
        e.title = src.title;
        e.uploader = src.uploader;
        e.thumbUrl = src.thumb;
        e.durationMs = src.durationMs;
        e.height = src.height;
        e.videoFile = videoName;
        e.audioFile = audioName;
        e.thumbFile = thumbName;
        e.bytes = video.length() + (audio == null ? 0 : audio.length());
        e.cachedAt = System.currentTimeMillis();
        e.complete = true;
        st.save(e);
        // Drop the files of an older copy at another height.
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (n.equals("meta.json") || n.equals(videoName) || n.equals(audioName) || n.equals(thumbName)) continue;
                if (n.endsWith(".part") || n.endsWith(".total")) continue;
                f.delete();
            }
        }
        j.done = e.bytes;
        j.total = e.bytes;
    }

    /** Public trigger for screens after something outside the service changed the cache. */
    public static void notifyChanged() {
        changed();
    }

    /**
     * Writes the cache entry for streams already on disk (used by cache-while-playing):
     * fetches the thumbnail, saves meta.json and removes files of an older copy.
     */
    static void saveEntry(YouTubeCache st, String id, String url, YouTube.CacheSource src, File dir) throws IOException {
        String videoName = "v" + src.height + "." + src.videoExt;
        String audioName = src.audioUrl == null ? null : "a." + src.audioExt;
        File video = new File(dir, videoName);
        File audio = audioName == null ? null : new File(dir, audioName);
        String thumbName = null;
        File thumb = new File(dir, "thumb.jpg");
        if (src.thumb != null && (!thumb.exists() || thumb.length() == 0)) {
            File part = new File(dir, "thumb.jpg.part");
            part.delete();
            try {
                new ChunkedDownloader(YouTube.USER_AGENT).download(src.thumb, part, null, new AtomicBoolean(false));
                if (!part.renameTo(thumb)) part.delete();
            } catch (IOException e) {
                part.delete();
            }
        }
        if (thumb.exists() && thumb.length() > 0) thumbName = thumb.getName();
        YouTubeCache.Entry e = new YouTubeCache.Entry();
        e.id = id;
        e.url = url;
        e.title = src.title;
        e.uploader = src.uploader;
        e.thumbUrl = src.thumb;
        e.durationMs = src.durationMs;
        e.height = src.height;
        e.videoFile = videoName;
        e.audioFile = audioName;
        e.thumbFile = thumbName;
        e.bytes = video.length() + (audio == null ? 0 : audio.length());
        e.cachedAt = System.currentTimeMillis();
        e.complete = true;
        st.save(e);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (n.equals("meta.json") || n.equals(videoName) || n.equals(audioName) || n.equals(thumbName)) continue;
                if (n.endsWith(".part") || n.endsWith(".total")) continue;
                f.delete();
            }
        }
    }

    /** With "Wi-Fi only" on, waits while the network is metered or missing. */
    private void waitForAllowedNetwork(Job j) throws ChunkedDownloader.CancelledException {
        Prefs prefs = new Prefs(this);
        boolean notified = false;
        while (prefs.cacheWifiOnly() && !unmeteredNetwork()) {
            if (CANCEL.get()) throw new ChunkedDownloader.CancelledException();
            if (!notified) {
                getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                        notification("Waiting for Wi-Fi to cache “" + j.title + "”", 0, 0, false));
                notified = true;
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                throw new ChunkedDownloader.CancelledException();
            }
        }
    }

    private boolean unmeteredNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }

    private void progress(Job j) {
        long now = System.currentTimeMillis();
        if (now - lastNotify < 700) return;
        lastNotify = now;
        int left = pending();
        int pct = j.percent();
        String title = "Caching “" + j.title + "”";
        String text = (pct >= 0 ? pct + "%" : YouTubeCache.Entry.formatBytes(j.done)) + (left > 1 ? "  ·  " + (left - 1) + " more queued" : "");
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                notification(title + "\n" + text, Math.max(0, pct), 100, pct < 0));
        changed();
    }

    private Notification notification(String text, int progress, int max, boolean indeterminate) {
        String[] lines = text.split("\n", 2);
        PendingIntent cancel = PendingIntent.getService(this, 21,
                new Intent(this, CacheService.class).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_save)
                .setContentTitle(lines[0])
                .setContentText(lines.length > 1 ? lines[1] : null)
                .setContentIntent(openApp())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setColor(0xFFF2A516)
                .setProgress(max, progress, indeterminate)
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_close), "Stop", cancel).build());
        return b.build();
    }

    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 22, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static String describe(Exception e) {
        if (e instanceof org.schabi.newpipe.extractor.exceptions.ReCaptchaException) return "YouTube asked this network to prove it is not a bot";
        if (e instanceof org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException) return "Age-restricted video";
        if (e instanceof org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException) return "Not available in your country";
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
