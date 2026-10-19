package com.hafij.astraplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns libVLC and the queue so playback survives the player screen going away
 * (background audio, picture-in-picture, media notification).
 */
public class PlaybackService extends Service implements MediaPlayer.EventListener {
    private static final String TAG = "AstraPlayback";

    public static final int REPEAT_ONCE = 0;
    public static final int REPEAT_FILE = 1;
    public static final int REPEAT_ALL = 2;

    public static final String ACTION_TOGGLE = "com.hafij.astraplayer.TOGGLE";
    public static final String ACTION_NEXT = "com.hafij.astraplayer.NEXT";
    public static final String ACTION_PREV = "com.hafij.astraplayer.PREV";
    public static final String ACTION_STOP = "com.hafij.astraplayer.STOP";
    public static final String ACTION_REWIND = "com.hafij.astraplayer.REWIND";

    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION_ID = 7;

    public interface Listener {
        default void onItemChanged(MediaItem item) {}
        default void onStateChanged() {}
        default void onTime(long timeMs, long lengthMs) {}
        default void onTracksChanged() {}
        /** Playback failed; message is shown to the user. */
        default void onError(MediaItem item, String title, String message) {}
        /** Short status text such as "Resumed at 12:04". */
        default void onInfo(String text) {}
        default void onRecordChanged(boolean recording, String path) {}
        default void onVideoOutput(boolean hasVideo) {}
        /** libVLC was re-created (subtitle style change); views must be attached again. */
        default void onEngineRestarted() {}
        /** Related YouTube videos for the current item arrived. */
        default void onRelatedChanged() {}
    }

    public final class LocalBinder extends Binder {
        public PlaybackService service() {
            return PlaybackService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();
    private final Random random = new Random();

    private Prefs prefs;
    private LibVLC vlc;
    private MediaPlayer player;
    private MediaSession session;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;

    private final List<MediaItem> queue = new ArrayList<>();
    private final Set<Integer> playedInShuffle = new HashSet<>();
    private int index = -1;
    private int repeatMode;
    private boolean shuffle;

    private MediaItem current;
    private String currentTitle = "";
    private String currentArtist = "";
    private Bitmap currentArt;
    private boolean resolving;
    private boolean hasVideo;
    private boolean sawStream;
    private boolean errorShown;
    private boolean foreground;
    private long lastResumeSave;
    private long lengthMs;
    private ParcelFileDescriptor openFd;
    private int loadToken;

    private long abA = -1;
    private long abB = -1;
    private boolean recording;
    private boolean pausedByFocusLoss;
    private boolean bufferedOnce;
    private boolean pauseOnStart;
    private boolean playingCached;
    private List<MediaItem> related = new ArrayList<>();
    private boolean currentLive;
    private final java.util.Set<String> autoCached = new java.util.HashSet<>();
    private boolean viewWanted;
    private boolean viewAttached;
    private boolean startPending;
    private long lastAbJump;
    private static volatile boolean fontsPreparing;

    public static void start(Context c) {
        c.startService(new Intent(c, PlaybackService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        repeatMode = prefs.repeatMode();
        shuffle = prefs.shuffle();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createEngine();
        createSession();
        prepareFonts();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private void createEngine() {
        ArrayList<String> opts = new ArrayList<>();
        opts.add("--audio-time-stretch"); // keeps pitch when the speed changes
        opts.add("--http-reconnect");
        opts.add("--no-sub-autodetect-file"); // we add external subtitles ourselves
        opts.add("--freetype-rel-fontsize=" + Prefs.SUB_SIZE_VALUES[clamp(prefs.subSize(), Prefs.SUB_SIZE_VALUES.length)]);
        opts.add("--freetype-color=" + Prefs.SUB_COLOR_VALUES[clamp(prefs.subColor(), Prefs.SUB_COLOR_VALUES.length)]);
        int bg = Prefs.SUB_BG_VALUES[clamp(prefs.subBg(), Prefs.SUB_BG_VALUES.length)];
        opts.add("--freetype-background-opacity=" + bg);
        opts.add("--freetype-background-color=0");
        opts.add("--freetype-outline-thickness=" + (bg > 0 ? 0 : 4));
        if (prefs.subBold()) opts.add("--freetype-bold");
        opts.add("--subsdec-encoding=UTF-8");
        if (BuildConfig.DEBUG) opts.add("-vv");
        vlc = new LibVLC(this, opts);
        player = new MediaPlayer(vlc);
        player.setEventListener(this);
    }

    /**
     * libVLC scans every system font (fontconfig) the first time it renders a subtitle, which
     * blocks playback for up to a minute. Build that cache once, in the background, on first
     * launch; later starts reuse it.
     */
    private void prepareFonts() {
        if (prefs.fontsReady() || fontsPreparing) return;
        fontsPreparing = true;
        Context app = getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                LibVLC builder = new LibVLC(app, new ArrayList<>());
                builder.buildFontCache();
                builder.release();
                new Prefs(app).setFontsReady();
            } catch (Throwable e) {
                Log.w(TAG, "font cache build failed", e);
            }
            fontsPreparing = false;
            main.post(() -> each(Listener::onStateChanged));
        }, "astra-fonts");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    public static boolean fontsPreparing() {
        return fontsPreparing;
    }

    private static int clamp(int i, int n) {
        return Math.max(0, Math.min(n - 1, i));
    }

    private void createSession() {
        session = new MediaSession(this, "Astra");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(true); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onStop() { stopPlayback(); }
            @Override public void onSeekTo(long pos) { seekTo(pos); }
            @Override public void onFastForward() { seekBy(10000); }
            @Override public void onRewind() { seekBy(-10000); }
        });
        Intent open = new Intent(this, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        session.setSessionActivity(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        session.setActive(true);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_TOGGLE.equals(a)) toggle();
        else if (ACTION_NEXT.equals(a)) next(true);
        else if (ACTION_PREV.equals(a)) previous();
        else if (ACTION_STOP.equals(a)) stopPlayback();
        else if (ACTION_REWIND.equals(a)) seekBy(-10000);
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Swiping the app away from recents while paused ends the session.
        if (!isPlaying()) stopPlayback();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        stopProxy(player.getTime());
        saveResume();
        if (recording) player.record(null);
        player.setEventListener(null);
        player.stop();
        player.release();
        vlc.release();
        session.release();
        closeFd();
        worker.shutdownNow();
        abandonFocus();
        super.onDestroy();
    }

    // ---- listeners ------------------------------------------------------------------------

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void each(java.util.function.Consumer<Listener> f) {
        for (Listener l : new ArrayList<>(listeners)) f.accept(l);
    }

    // ---- queue ----------------------------------------------------------------------------

    public MediaPlayer player() { return player; }
    public MediaItem current() { return current; }
    public List<MediaItem> related() { return new ArrayList<>(related); }
    /** True while the current YouTube video plays from the offline cache. */
    public boolean playingCached() { return playingCached; }
    public String currentTitle() { return currentTitle; }
    public List<MediaItem> queue() { return new ArrayList<>(queue); }
    public int index() { return index; }
    public boolean isResolving() { return resolving; }
    public boolean hasVideo() { return hasVideo; }
    public long length() { return lengthMs > 0 ? lengthMs : player.getLength(); }
    public int repeatMode() { return repeatMode; }
    public boolean shuffle() { return shuffle; }
    public boolean isRecording() { return recording; }
    public long abA() { return abA; }
    public long abB() { return abB; }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    public void playQueue(List<MediaItem> items, int start) {
        queue.clear();
        queue.addAll(items);
        playedInShuffle.clear();
        playIndex(Math.max(0, Math.min(start, items.size() - 1)));
    }

    public void enqueue(List<MediaItem> items) {
        queue.addAll(items);
        if (current == null && !queue.isEmpty()) playIndex(queue.size() - items.size());
        each(l -> l.onItemChanged(current));
    }

    /** Plays an item now, keeping the rest of the queue after it. */
    public void playNow(MediaItem item) {
        if (current == null || index < 0) {
            playQueue(java.util.Collections.singletonList(item), 0);
            return;
        }
        queue.add(index + 1, item);
        playIndex(index + 1);
    }

    /** Inserts items right after the current one. */
    public void playNext(List<MediaItem> items) {
        if (current == null) {
            enqueue(items);
            return;
        }
        queue.addAll(index + 1, items);
        each(l -> l.onItemChanged(current));
    }

    public void removeFromQueue(int i) {
        if (i < 0 || i >= queue.size()) return;
        queue.remove(i);
        if (i < index) index--;
        else if (i == index) {
            if (queue.isEmpty()) stopPlayback();
            else playIndex(Math.min(index, queue.size() - 1));
            return;
        }
        each(l -> l.onItemChanged(current));
    }

    public void setRepeatMode(int m) {
        repeatMode = m;
        prefs.setRepeatMode(m);
        each(Listener::onStateChanged);
    }

    public void setShuffle(boolean s) {
        shuffle = s;
        prefs.setShuffle(s);
        playedInShuffle.clear();
        if (index >= 0) playedInShuffle.add(index);
        each(Listener::onStateChanged);
    }

    public void playIndex(int i) {
        if (i < 0 || i >= queue.size()) return;
        saveResume();
        stopRecording();
        index = i;
        playedInShuffle.add(i);
        load(queue.get(i));
    }

    /** @param user true for a button press, false when the current item ended by itself. */
    public void next(boolean user) {
        if (queue.isEmpty()) return;
        if (!user && repeatMode == REPEAT_FILE) {
            playIndex(index);
            return;
        }
        int n;
        if (shuffle && queue.size() > 1) {
            if (playedInShuffle.size() >= queue.size()) {
                if (!user && repeatMode == REPEAT_ONCE) {
                    finishQueue();
                    return;
                }
                playedInShuffle.clear();
            }
            List<Integer> left = new ArrayList<>();
            for (int k = 0; k < queue.size(); k++) if (k != index && !playedInShuffle.contains(k)) left.add(k);
            n = left.isEmpty() ? (index + 1) % queue.size() : left.get(random.nextInt(left.size()));
        } else {
            n = index + 1;
            if (n >= queue.size()) {
                if (!user && repeatMode == REPEAT_ONCE) {
                    finishQueue();
                    return;
                }
                n = 0;
            }
        }
        playIndex(n);
    }

    public void previous() {
        if (queue.isEmpty()) return;
        if (player.getTime() > 5000) {
            seekTo(0);
            return;
        }
        playIndex(index - 1 < 0 ? queue.size() - 1 : index - 1);
    }

    private void finishQueue() {
        player.stop();
        each(Listener::onStateChanged);
        updateSession();
    }

    // ---- loading --------------------------------------------------------------------------

    private void load(MediaItem item) {
        stopProxy(player.getTime());
        final int token = ++loadToken;
        current = item;
        currentTitle = item.title;
        currentArtist = item.subtitle;
        currentArt = null;
        hasVideo = false;
        sawStream = false;
        errorShown = false;
        bufferedOnce = false;
        playingCached = false;
        currentLive = false;
        related = new ArrayList<>();
        lengthMs = item.durationMs;
        abA = abB = -1;
        player.stop();
        closeFd();
        each(l -> l.onItemChanged(item));
        if (item.kind == MediaItem.YOUTUBE && playCached(item)) return;
        if (item.kind == MediaItem.YOUTUBE) {
            resolving = true;
            each(Listener::onStateChanged);
            updateSession();
            final int quality = prefs.ytQuality();
            final String vid = YouTubeCache.videoId(item.uri);
            final int cacheHeight = wantCacheWhilePlaying(vid) ? prefs.cacheHeight() : 0;
            worker.execute(() -> {
                try {
                    YouTube.Resolved r = YouTube.resolve(item.uri, quality, false, cacheHeight);
                    Bitmap art = item.thumb != null || r.thumb != null ? download(r.thumb != null ? r.thumb : item.thumb) : null;
                    // Cache while playing: stream through the local proxy that saves the video.
                    StreamCacheProxy px = null;
                    String[] local = null;
                    if (r.cache != null && vid != null) {
                        try {
                            px = makeProxy(item, vid, r.cache);
                            local = px.start();
                        } catch (Exception e) {
                            Log.w(TAG, "cache while playing unavailable, streaming", e);
                            px = null;
                            local = null;
                        }
                    }
                    final StreamCacheProxy fpx = px;
                    final String[] flocal = local;
                    main.post(() -> {
                        if (token != loadToken) {
                            if (fpx != null) fpx.stop();
                            return;
                        }
                        resolving = false;
                        currentTitle = r.title;
                        currentArtist = r.uploader + (r.quality == null ? "" : " · " + r.quality);
                        currentArt = art;
                        if (r.durationMs > 0) lengthMs = r.durationMs;
                        currentLive = r.live;
                        related = r.related;
                        each(Listener::onRelatedChanged);
                        if (token != loadToken) {
                            if (fpx != null) fpx.stop();
                            return;
                        }
                        if (fpx != null) {
                            proxy = fpx;
                            proxyItem = item;
                            proxyHeight = r.cache.height;
                            currentArtist = r.uploader + " · " + r.cache.height + "p · saving offline";
                            startMedia(Uri.parse(flocal[0]), flocal[1], item, true);
                        } else {
                            startMedia(Uri.parse(r.videoUrl), r.audioUrl, item, !r.live);
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "YouTube resolve failed", e);
                    main.post(() -> {
                        if (token != loadToken) return;
                        resolving = false;
                        fail(item, "Could not open YouTube video", youtubeMessage(e));
                    });
                }
            });
            return;
        }
        resolving = false;
        try {
            if (item.uri.startsWith("content://")) {
                openFd = getContentResolver().openFileDescriptor(Uri.parse(item.uri), "r");
                if (openFd == null) throw new IllegalStateException("no descriptor");
                startMediaFd(item);
            } else if (item.uri.startsWith("/")) {
                File f = new File(item.uri);
                if (!f.exists()) {
                    fail(item, "File not found", item.uri + " no longer exists.");
                    return;
                }
                if (!f.canRead()) {
                    fail(item, "No permission", "Astra Player is not allowed to read this file. Allow file access in the Files tab.");
                    return;
                }
                startMedia(Uri.fromFile(f), null, item, true);
            } else {
                startMedia(Uri.parse(item.uri), null, item, true);
            }
        } catch (Exception e) {
            fail(item, "Cannot open", String.valueOf(e.getMessage()));
        }
    }

    /** Plays a YouTube item from the offline cache when a complete copy exists; no network needed. */
    private boolean playCached(MediaItem item) {
        String id = YouTubeCache.videoId(item.uri);
        if (id == null) return false;
        YouTubeCache st = CacheService.store(this);
        YouTubeCache.Entry e = st.get(id);
        if (e == null) return false;
        resolving = false;
        playingCached = true;
        if (e.title != null && !e.title.isEmpty()) currentTitle = e.title;
        currentArtist = (e.uploader == null || e.uploader.isEmpty() ? "" : e.uploader + " · ")
                + "Offline " + (e.height > 0 ? e.height + "p" : "audio");
        File thumb = st.thumbFile(e);
        currentArt = thumb == null ? null : BitmapFactory.decodeFile(thumb.getPath());
        if (e.durationMs > 0) lengthMs = e.durationMs;
        File audio = st.audioFile(e);
        Log.i(TAG, "playing cached " + id + " (" + e.height + "p)");
        // Suggestions need the network; fetch them quietly and skip when offline.
        final int token = loadToken;
        worker.execute(() -> {
            try {
                List<MediaItem> rel = YouTube.related(item.uri);
                main.post(() -> {
                    if (token != loadToken) return;
                    related = rel;
                    each(Listener::onRelatedChanged);
                });
            } catch (Exception ignored) {
            }
        });
        startMedia(Uri.fromFile(st.videoFile(e)), audio == null ? null : Uri.fromFile(audio).toString(), item, true);
        return true;
    }

    /** Auto-cache "every video I watch": queue the streamed YouTube video once it plays. */
    private void autoCacheCurrent() {
        MediaItem item = current;
        if (item == null || item.kind != MediaItem.YOUTUBE || playingCached || currentLive || proxy != null) return;
        if (prefs.autoCache() != Prefs.AUTO_CACHE_WATCHED) return;
        String id = YouTubeCache.videoId(item.uri);
        if (id == null || !autoCached.add(id)) return;
        CacheService.enqueue(this, Collections.singletonList(
                new MediaItem(item.uri, currentTitle, MediaItem.YOUTUBE, item.subtitle, item.thumb, lengthMs)), prefs.cacheHeight());
    }

    // ---- cache while playing --------------------------------------------------------------

    private StreamCacheProxy proxy;
    private MediaItem proxyItem;
    private int proxyHeight;

    /** Auto-cache "while playing" is on, the network is allowed, and nobody else is caching it. */
    private boolean wantCacheWhilePlaying(String vid) {
        if (vid == null || prefs.autoCache() != Prefs.AUTO_CACHE_WATCHED) return false;
        if (prefs.cacheWifiOnly() && !unmeteredNetwork()) return false;
        // The background cache owns the files while it has the video queued or running.
        CacheService.Job j = CacheService.job(vid);
        return j == null || (j.state != CacheService.RUNNING && j.state != CacheService.QUEUED);
    }

    private boolean unmeteredNetwork() {
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        if (cm == null) return false;
        android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }

    private StreamCacheProxy makeProxy(MediaItem item, String vid, YouTube.CacheSource src) {
        YouTubeCache st = CacheService.store(this);
        File dir = st.dir(vid);
        final StreamCacheProxy[] self = new StreamCacheProxy[1];
        self[0] = new StreamCacheProxy(vid, dir, src, () -> YouTube.resolveForCache(item.uri, src.height),
                () -> {
                    // Runs on a download thread once both streams are on disk.
                    try {
                        CacheService.saveEntry(st, vid, item.uri, src, dir);
                        main.post(() -> {
                            each(l -> l.onInfo("Saved for offline"));
                            CacheService.notifyChanged();
                        });
                    } catch (Exception e) {
                        Log.w(TAG, "saving cached copy failed", e);
                    }
                });
        return self[0];
    }

    /**
     * Stops the proxy of the previous item. If the user watched at least a minute (or half of
     * a short video), the rest is downloaded in the background so the copy is completed.
     */
    private void stopProxy(long watchedMs) {
        StreamCacheProxy px = proxy;
        if (px == null) return;
        proxy = null;
        px.stop();
        long len = length();
        if (!px.complete() && proxyItem != null && (watchedMs >= 60000 || (len > 0 && watchedMs >= len / 2))) {
            CacheService.enqueue(this, Collections.singletonList(new MediaItem(proxyItem.uri, currentTitle,
                    MediaItem.YOUTUBE, proxyItem.subtitle, proxyItem.thumb, len)), proxyHeight);
        }
        proxyItem = null;
    }

    private static String youtubeMessage(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (e instanceof org.schabi.newpipe.extractor.exceptions.ReCaptchaException) {
            return "YouTube is asking this network to prove it is not a bot. Try again later or on another network.";
        }
        if (e instanceof org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException) {
            return "This video is age-restricted and needs a signed-in YouTube account.";
        }
        if (e instanceof org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException) {
            return "This video is not available in your country.";
        }
        if (e instanceof java.io.IOException) return "Network error: " + m;
        return m;
    }

    private void startMediaFd(MediaItem item) {
        Media m = new Media(vlc, openFd.getFileDescriptor());
        configure(m, item, true);
        player.setMedia(m);
        m.release();
        begin(item);
    }

    private void startMedia(Uri uri, String audioSlave, MediaItem item, boolean resumable) {
        Media m = new Media(vlc, uri);
        if (audioSlave != null) m.addSlave(new IMedia.Slave(IMedia.Slave.Type.Audio, 4, audioSlave));
        if (item.kind != MediaItem.LOCAL) {
            m.addOption(":network-caching=2000");
            if (item.kind == MediaItem.YOUTUBE) m.addOption(":http-user-agent=" + YouTube.USER_AGENT);
        }
        if (item.isLocalPath()) {
            for (File s : MediaFiles.findSubtitles(new File(item.uri))) {
                m.addSlave(new IMedia.Slave(IMedia.Slave.Type.Subtitle, 4, Uri.fromFile(s).toString()));
            }
        }
        configure(m, item, resumable);
        player.setMedia(m);
        m.release();
        begin(item);
    }

    private void configure(Media m, MediaItem item, boolean resumable) {
        m.setHWDecoderEnabled(prefs.hwDecoding(), false);
        if (resumable && prefs.resumeEnabled()) {
            long pos = prefs.resumePosition(item.uri);
            if (pos > 5000) {
                m.addOption(":start-time=" + (pos / 1000.0));
                main.postDelayed(() -> each(l -> l.onInfo("Resumed at " + MediaFiles.formatTime(pos))), 400);
            }
        }
    }

    /**
     * Starts playback, unless a player screen is about to attach its video surface: starting
     * without it makes the hardware decoder restart mid-GOP when the surface arrives, which
     * shows grey blocks until the next keyframe (seconds, for phone and WhatsApp videos).
     */
    private void begin(MediaItem item) {
        boolean needsSurface = !MediaFiles.isAudio(item.title);
        if (needsSurface && viewWanted && !viewAttached) {
            startPending = true;
            main.removeCallbacks(pendingFallback);
            main.postDelayed(pendingFallback, 2000);
            each(Listener::onStateChanged);
            return;
        }
        player.play();
        afterStart();
    }

    private final Runnable pendingFallback = () -> {
        if (startPending) {
            startPending = false;
            viewWanted = false;
            player.play();
            afterStart();
        }
    };

    /** Call right before opening the player screen, so playback waits for its surface. */
    public void expectView() {
        viewWanted = true;
    }

    /** The player screen attached (true) or detached (false) its video surface. */
    public void setViewAttached(boolean attached) {
        viewAttached = attached;
        if (attached) {
            viewWanted = false;
            if (startPending) {
                startPending = false;
                main.removeCallbacks(pendingFallback);
                player.play();
                afterStart();
            }
        }
    }

    private void afterStart() {
        requestFocus();
        each(Listener::onStateChanged);
        updateSession();
    }

    private void fail(MediaItem item, String title, String message) {
        errorShown = true;
        each(l -> l.onError(item, title, message));
        each(Listener::onStateChanged);
        updateSession();
        // With nobody watching, move on so a broken file does not stall a background queue.
        if (listeners.isEmpty() && queue.size() > 1) main.postDelayed(() -> next(false), 1500);
    }

    private void closeFd() {
        if (openFd != null) {
            try {
                openFd.close();
            } catch (Exception ignored) {
            }
            openFd = null;
        }
    }

    // ---- transport ------------------------------------------------------------------------

    public void play() {
        if (current == null) return;
        if (player.getPlayerState() == org.videolan.libvlc.interfaces.IMedia.State.Ended
                || player.getPlayerState() == org.videolan.libvlc.interfaces.IMedia.State.Stopped) {
            playIndex(index);
            return;
        }
        requestFocus();
        player.play();
    }

    public void pause() {
        player.pause();
        saveResume();
    }

    public void toggle() {
        if (isPlaying()) pause();
        else play();
    }

    public void seekTo(long ms) {
        if (!player.isSeekable()) return;
        long len = length();
        if (len > 0) ms = Math.min(ms, len - 500);
        player.setTime(Math.max(0, ms));
        updateSession();
    }

    public void seekBy(long delta) {
        seekTo(player.getTime() + delta);
    }

    public void stopPlayback() {
        stopProxy(player.getTime());
        saveResume();
        stopRecording();
        player.stop();
        current = null;
        index = -1;
        queue.clear();
        abandonFocus();
        stopForegroundCompat(true);
        each(l -> l.onItemChanged(null));
        each(Listener::onStateChanged);
        stopSelf();
    }

    public void setRate(float rate) {
        player.setRate(rate);
        updateSession();
    }

    public float rate() {
        return player.getRate();
    }

    // ---- A-B repeat -----------------------------------------------------------------------

    /** Cycles: set A, set B, clear. Returns a short status text. */
    public String cycleAb() {
        long t = player.getTime();
        if (abA < 0) {
            abA = t;
            return "A set at " + MediaFiles.formatTime(t);
        } else if (abB < 0) {
            if (t <= abA + 300) return "Point B must be after A";
            abB = t;
            seekTo(abA);
            return "Repeating " + MediaFiles.formatTime(abA) + " - " + MediaFiles.formatTime(abB);
        }
        abA = abB = -1;
        return "A-B repeat off";
    }

    public void setAbA() { abA = player.getTime(); abB = -1; each(Listener::onStateChanged); }
    public void setAbB() { if (abA >= 0 && player.getTime() > abA) { abB = player.getTime(); seekTo(abA); } each(Listener::onStateChanged); }
    public void clearAb() { abA = abB = -1; each(Listener::onStateChanged); }

    // ---- recording ------------------------------------------------------------------------

    public File recordDir() {
        File d;
        if (Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager() : true) {
            d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Astra Player");
        } else {
            d = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Recordings");
        }
        if (!d.exists() && !d.mkdirs()) d = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Recordings");
        d.mkdirs();
        return d;
    }

    /** Starts recording from the current position; returns false when not allowed. */
    public boolean startRecording() {
        if (current == null || !current.savingAllowed() || recording) return false;
        boolean ok = player.record(recordDir().getAbsolutePath());
        if (ok) {
            recording = true;
            // Nothing is written while paused, so recording implies playing.
            if (!isPlaying()) play();
        }
        each(Listener::onStateChanged);
        return ok;
    }

    public void stopRecording() {
        if (!recording) return;
        player.record(null);
        recording = false;
        each(Listener::onStateChanged);
    }

    /** Renames libVLC's "vlc-record-<date>-<name>-.ext" to "<title> (rec yyyy-MM-dd HH.mm.ss).ext". */
    private String tidyRecording(String path) {
        File f = new File(path);
        if (!f.exists()) return path;
        String ext = MediaFiles.ext(f.getName());
        String title = currentTitle == null ? "Recording" : currentTitle;
        int dot = title.lastIndexOf('.');
        if (dot > 0 && title.length() - dot <= 5) title = title.substring(0, dot);
        title = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (title.length() > 80) title = title.substring(0, 80);
        String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.ROOT).format(new java.util.Date(f.lastModified()));
        File target = new File(f.getParentFile(), title + " (rec " + stamp + ")" + (ext.isEmpty() ? "" : "." + ext));
        return f.renameTo(target) ? target.getAbsolutePath() : path;
    }

    // ---- engine restart (subtitle style) -------------------------------------------------

    public void restartEngine() {
        long t = player.getTime();
        boolean wasPlaying = isPlaying();
        MediaItem item = current;
        stopRecording();
        player.setEventListener(null);
        player.stop();
        player.detachViews();
        player.release();
        vlc.release();
        createEngine();
        each(Listener::onEngineRestarted);
        if (item != null && index >= 0) {
            prefs.saveResume(item.uri, t);
            load(item);
            pauseOnStart = !wasPlaying;
        }
    }

    // ---- resume ---------------------------------------------------------------------------

    private void saveResume() {
        if (current == null || !prefs.resumeEnabled()) return;
        long t = player.getTime();
        long len = length();
        if (t <= 0) return;
        if (len > 0 && (t > len - 8000 || t > len * 0.97)) prefs.saveResume(current.uri, 0);
        else if (len <= 0 || len > 60000) prefs.saveResume(current.uri, t);
    }

    // ---- libVLC events --------------------------------------------------------------------

    @Override
    public void onEvent(MediaPlayer.Event event) {
        switch (event.type) {
            case MediaPlayer.Event.Playing:
            case MediaPlayer.Event.Paused:
            case MediaPlayer.Event.Stopped:
                if (event.type == MediaPlayer.Event.Playing && pauseOnStart) {
                    // Engine restarted while paused: show the frame, then pause again.
                    pauseOnStart = false;
                    main.postDelayed(this::pause, 300);
                }
                if (event.type == MediaPlayer.Event.Playing) {
                    startForegroundCompat();
                    autoCacheCurrent();
                }
                else saveResume();
                each(Listener::onStateChanged);
                updateSession();
                break;
            case MediaPlayer.Event.Buffering:
                // libVLC sends dozens of these per second; only the end of buffering matters.
                if (event.getBuffering() >= 100f && !bufferedOnce) {
                    bufferedOnce = true;
                    each(Listener::onStateChanged);
                }
                break;
            case MediaPlayer.Event.LengthChanged:
                if (event.getLengthChanged() > 0) lengthMs = event.getLengthChanged();
                updateSession();
                break;
            case MediaPlayer.Event.TimeChanged: {
                long t = event.getTimeChanged();
                // Ignore time events still queued from before the last jump back to A.
                if (abA >= 0 && abB > abA && t >= abB && SystemClock.elapsedRealtime() - lastAbJump > 700) {
                    lastAbJump = SystemClock.elapsedRealtime();
                    player.setTime(abA);
                }
                each(l -> l.onTime(t, length()));
                long now = System.currentTimeMillis();
                if (now - lastResumeSave > 10000) {
                    lastResumeSave = now;
                    saveResume();
                }
                break;
            }
            case MediaPlayer.Event.ESAdded:
            case MediaPlayer.Event.ESDeleted:
            case MediaPlayer.Event.ESSelected:
                sawStream = true;
                hasVideo = player.getVideoTracksCount() > 0;
                each(Listener::onTracksChanged);
                break;
            case MediaPlayer.Event.Vout:
                hasVideo = event.getVoutCount() > 0 || player.getVideoTracksCount() > 0;
                each(l -> l.onVideoOutput(hasVideo));
                break;
            case MediaPlayer.Event.EndReached:
                if (current != null && !sawStream && !errorShown) {
                    unsupported();
                    break;
                }
                if (current != null) prefs.saveResume(current.uri, 0);
                stopRecording();
                next(false);
                break;
            case MediaPlayer.Event.EncounteredError:
                if (current != null && !errorShown) unsupported();
                break;
            case MediaPlayer.Event.RecordChanged:
                recording = event.getRecording();
                String path = event.getRecordPath();
                if (!recording && path != null) path = tidyRecording(path);
                final String finalPath = path;
                each(l -> l.onRecordChanged(recording, finalPath));
                break;
            default:
                break;
        }
    }

    private void unsupported() {
        MediaItem item = current;
        String name = item == null ? "" : item.title;
        String msg;
        if (item != null && item.kind == MediaItem.STREAM) {
            msg = "The stream could not be opened. Check the address and your connection.\n\n" + item.uri;
        } else {
            msg = "Astra Player cannot play \"" + name + "\". The file may be damaged, incomplete, or not an audio or video file.";
        }
        fail(item, item != null && item.kind == MediaItem.STREAM ? "Stream unavailable" : "Unsupported file", msg);
    }

    // ---- audio focus ----------------------------------------------------------------------

    private void requestFocus() {
        if (focusRequest == null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setOnAudioFocusChangeListener(change -> {
                        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            if (isPlaying()) {
                                pausedByFocusLoss = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT;
                                pause();
                            }
                        } else if (change == AudioManager.AUDIOFOCUS_GAIN && pausedByFocusLoss) {
                            pausedByFocusLoss = false;
                            play();
                        }
                    }, main)
                    .build();
        }
        audioManager.requestAudioFocus(focusRequest);
    }

    private void abandonFocus() {
        if (focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
    }

    // ---- media session + notification ----------------------------------------------------

    private void updateSession() {
        if (session == null) return;
        boolean playing = isPlaying();
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(resolving ? PlaybackState.STATE_CONNECTING : playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        player.getTime(), playing ? player.getRate() : 0f)
                .build());
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, length());
        if (currentArt != null) md.putBitmap(MediaMetadata.METADATA_KEY_ART, currentArt);
        session.setMetadata(md.build());
        if (current != null && (foreground || playing)) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        boolean playing = isPlaying();
        Intent open = new Intent(this, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_play)
                .setContentTitle(currentTitle)
                .setContentText(resolving ? "Loading…" : currentArtist)
                .setContentIntent(content)
                .setDeleteIntent(serviceIntent(ACTION_STOP, 4))
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(0xFFF2A516)
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_prev), "Previous", serviceIntent(ACTION_PREV, 1)).build())
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, playing ? R.drawable.ic_pause : R.drawable.ic_play), playing ? "Pause" : "Play", serviceIntent(ACTION_TOGGLE, 2)).build())
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_next), "Next", serviceIntent(ACTION_NEXT, 3)).build())
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_close), "Stop", serviceIntent(ACTION_STOP, 4)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        if (currentArt != null) b.setLargeIcon(currentArt);
        return b.build();
    }

    private PendingIntent serviceIntent(String action, int code) {
        return PendingIntent.getService(this, code, new Intent(this, PlaybackService.class).setAction(action), PendingIntent.FLAG_IMMUTABLE);
    }

    private void startForegroundCompat() {
        if (current == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
            foreground = true;
        } catch (Exception e) {
            // Android 12+ refuses to start a foreground service from the background; the
            // notification still shows through updateSession().
            Log.w(TAG, "startForeground refused", e);
        }
    }

    private void stopForegroundCompat(boolean remove) {
        stopForeground(remove ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
        foreground = false;
        if (remove) getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
    }

    private static Bitmap download(String url) {
        if (url == null) return null;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            try (InputStream in = c.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Exception e) {
            return null;
        }
    }

    public MediaSession session() {
        return session;
    }
}
