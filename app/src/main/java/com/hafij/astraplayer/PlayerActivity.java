package com.hafij.astraplayer;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.File;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Full-screen player: video surface, compact bottom controls, gestures, PiP and hotkeys. */
public class PlayerActivity extends Activity implements PlaybackService.Listener {
    private static final int REQ_SUBTITLE = 41;
    private static final long HIDE_DELAY = 3500;
    private static final float[] SPEED_PRESETS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
    private static final MediaPlayer.ScaleType[] SCALES = {
            MediaPlayer.ScaleType.SURFACE_BEST_FIT, MediaPlayer.ScaleType.SURFACE_FIT_SCREEN,
            MediaPlayer.ScaleType.SURFACE_FILL, MediaPlayer.ScaleType.SURFACE_16_9,
            MediaPlayer.ScaleType.SURFACE_4_3, MediaPlayer.ScaleType.SURFACE_ORIGINAL};
    private static final String[] SCALE_NAMES = {"Best fit", "Fit screen (crop)", "Stretch", "16:9", "4:3", "Original size"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private PlaybackService service;
    private Prefs prefs;
    private PlaylistStore playlists;
    private AudioManager audio;

    private VLCVideoLayout videoLayout;
    private View topBar, controls, errorCard, audioPanel;
    private ImageButton btnPlay, btnLock, btnPip;
    private TextView title, subtitle, timeCurrent, timeTotal, centerInfo, badgeRec, badgeAb, audioTitle;
    private ImageView audioArt;
    private SeekBar seek;
    private ProgressBar loading;

    private boolean attached;
    private boolean videoTrackOff;
    private boolean started;
    private boolean controlsVisible = true;
    private boolean locked;
    private boolean seeking;
    private boolean leftPip;
    private int scaleIndex;
    private List<MediaItem> pendingQueue;
    private int pendingIndex;

    // Gesture state
    private int gestureMode; // 0 none, 1 seek, 2 brightness, 3 volume
    private long gestureSeekBase, gestureSeekTarget;

    private SeekPreview seekPreview;
    private View previewCard;
    private ImageView previewImage;
    private TextView previewTime;
    private long previewAt = -1;
    private float gestureVolume, gestureBrightness;

    private final Runnable hideControls = () -> showControls(false);
    private final Runnable hideInfo = () -> centerInfo.setVisibility(View.GONE);

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlaybackService.LocalBinder) binder).service();
            service.addListener(PlayerActivity.this);
            // Attach the surface before starting, so decoding begins on a keyframe with it.
            if (started) attachVideo();
            if (pendingQueue != null) {
                if (!attached) service.expectView();
                service.playQueue(pendingQueue, pendingIndex);
                pendingQueue = null;
            }
            refreshAll();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    /** Opens the player for a queue (from the library screens). */
    public static void open(Context c) {
        c.startActivity(new Intent(c, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    /** The player stays dark whatever the app theme is. */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Ui.applyTheme(this, Prefs.THEME_DARK);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        playlists = new PlaylistStore(this);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        setContentView(R.layout.activity_player);
        bindViews();
        enterImmersive();
        handleIntent(getIntent());
        PlaybackService.start(this);
        bindService(new Intent(this, PlaybackService.class), connection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        if (pendingQueue != null && service != null) {
            if (!attached) service.expectView();
            service.playQueue(pendingQueue, pendingIndex);
            pendingQueue = null;
        }
    }

    /** "Open with" from other apps: a file, content uri or stream URL. */
    private void handleIntent(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null && intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(https?|rtsp|rtmp)://\\S+").matcher(text);
                if (m.find()) data = Uri.parse(m.group());
            }
        }
        if (data == null) return;
        String scheme = data.getScheme() == null ? "" : data.getScheme();
        MediaItem item;
        if (scheme.equals("content")) {
            String path = resolvePath(data);
            item = path != null ? MediaItem.file(new File(path)) : new MediaItem(data.toString(), displayName(data), MediaItem.LOCAL, "", null, 0);
        } else if (scheme.equals("file")) {
            item = MediaItem.file(new File(data.getPath()));
        } else {
            item = MediaItem.fromUrl(data.toString());
            if (item.kind == MediaItem.STREAM) prefs.addHistory(item.uri);
        }
        pendingQueue = Collections.singletonList(item);
        pendingIndex = 0;
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        return null;
    }

    /** With all-files access, a real path lets us pick up subtitles next to the video. */
    private String resolvePath(Uri uri) {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) return null;
        try (Cursor c = getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String p = c.getString(0);
                if (p != null && new File(p).canRead()) return p;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void bindViews() {
        videoLayout = findViewById(R.id.video);
        topBar = findViewById(R.id.top_bar);
        controls = findViewById(R.id.controls);
        errorCard = findViewById(R.id.error_card);
        audioPanel = findViewById(R.id.audio_panel);
        audioArt = findViewById(R.id.audio_art);
        audioTitle = findViewById(R.id.audio_title);
        btnPlay = findViewById(R.id.btn_play);
        btnLock = findViewById(R.id.btn_lock);
        btnPip = findViewById(R.id.btn_pip);
        title = findViewById(R.id.title);
        subtitle = findViewById(R.id.subtitle);
        timeCurrent = findViewById(R.id.time_current);
        timeTotal = findViewById(R.id.time_total);
        centerInfo = findViewById(R.id.center_info);
        badgeRec = findViewById(R.id.badge_rec);
        badgeAb = findViewById(R.id.badge_ab);
        seek = findViewById(R.id.seek);
        loading = findViewById(R.id.loading);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnPlay.setOnClickListener(v -> { if (service != null) service.toggle(); bumpHide(); });
        findViewById(R.id.btn_prev).setOnClickListener(v -> { if (service != null) service.previous(); bumpHide(); });
        findViewById(R.id.btn_next).setOnClickListener(v -> { if (service != null) service.next(true); bumpHide(); });
        findViewById(R.id.btn_rew).setOnClickListener(v -> seekBy(-10000));
        findViewById(R.id.btn_fwd).setOnClickListener(v -> seekBy(10000));
        findViewById(R.id.btn_sub).setOnClickListener(v -> showSubtitleDialog());
        findViewById(R.id.btn_audio).setOnClickListener(v -> showAudioDialog());
        findViewById(R.id.btn_speed).setOnClickListener(v -> showSpeedDialog());
        findViewById(R.id.btn_ab).setOnClickListener(v -> { if (service != null) info(service.cycleAb()); refreshBadges(); bumpHide(); });
        findViewById(R.id.btn_more).setOnClickListener(this::showMoreMenu);
        findViewById(R.id.btn_shot).setOnClickListener(v -> screenshot());
        btnPip.setOnClickListener(v -> enterPip());
        btnPip.setVisibility(supportsPip() ? View.VISIBLE : View.GONE);
        btnLock.setOnClickListener(v -> setLocked(!locked));
        badgeRec.setOnClickListener(v -> { if (service != null) service.stopRecording(); });
        badgeAb.setOnClickListener(v -> { if (service != null) service.clearAb(); refreshBadges(); });
        findViewById(R.id.error_close).setOnClickListener(v -> {
            errorCard.setVisibility(View.GONE);
            if (service == null || service.queue().size() <= 1) finish();
        });
        findViewById(R.id.error_skip).setOnClickListener(v -> {
            errorCard.setVisibility(View.GONE);
            if (service != null) service.next(true);
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && service != null) {
                    long t = service.length() * p / 1000;
                    timeCurrent.setText(MediaFiles.formatTime(t));
                    // Centre the preview over the thumb.
                    int[] loc = new int[2];
                    s.getLocationInWindow(loc);
                    float track = s.getWidth() - s.getPaddingLeft() - s.getPaddingRight();
                    showSeekPreview(t, loc[0] + s.getPaddingLeft() + track * p / s.getMax(), true);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                seeking = true;
                main.removeCallbacks(hideControls);
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                seeking = false;
                hideSeekPreview();
                if (service != null) service.seekTo(service.length() * s.getProgress() / 1000);
                bumpHide();
            }
        });

        // Keep the control bar compact on wide screens: at most 640dp, centred.
        controls.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int parentW = ((View) v.getParent()).getWidth();
            int max = Ui.dp(this, 640);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            int want = parentW - lp.leftMargin - lp.rightMargin > max ? max : ViewGroup.LayoutParams.MATCH_PARENT;
            if (lp.width != want) {
                lp.width = want;
                v.post(v::requestLayout);
            }
        });

        // Keep controls clear of the navigation bar and display cutouts.
        findViewById(R.id.root).setOnApplyWindowInsetsListener((v, insets) -> {
            int l = 0, t = 0, r = 0, b = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets in = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l = in.left; t = in.top; r = in.right; b = in.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight(); b = insets.getSystemWindowInsetBottom();
            }
            topBar.setPadding(Ui.dp(this, 6) + l, Ui.dp(this, 6) + t, Ui.dp(this, 6) + r, Ui.dp(this, 22));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) controls.getLayoutParams();
            lp.bottomMargin = Ui.dp(this, 14) + b;
            lp.leftMargin = Ui.dp(this, 12) + l;
            lp.rightMargin = Ui.dp(this, 12) + r;
            controls.setLayoutParams(lp);
            return insets;
        });

        setupGestures();

        previewCard = findViewById(R.id.seek_preview);
        previewImage = findViewById(R.id.seek_preview_image);
        previewTime = findViewById(R.id.seek_preview_time);
        previewImage.setClipToOutline(true);
        seekPreview = new SeekPreview(this);
        seekPreview.setListener((t, b) -> {
            // Show a late frame only if it is still near the position being dragged to.
            if (previewCard.getVisibility() == View.VISIBLE && Math.abs(t - previewAt) < 4000) previewImage.setImageBitmap(b);
        });
    }

    /**
     * Shows the time (and, for local videos, a frame) of {@code timeMs} centred at window x
     * {@code centerX}: above the seek bar while dragging it, mid-screen for swipe seeking.
     */
    private void showSeekPreview(long timeMs, float centerX, boolean overSeekBar) {
        if (service == null) return;
        previewAt = timeMs;
        previewTime.setText(MediaFiles.formatTime(timeMs) + " / " + MediaFiles.formatTime(service.length()));
        boolean frames = seekPreview.active();
        previewImage.setVisibility(frames ? View.VISIBLE : View.GONE);
        if (frames) {
            android.graphics.Bitmap b = seekPreview.request(timeMs);
            if (b != null) previewImage.setImageBitmap(b);
        }
        previewCard.setVisibility(View.VISIBLE);
        View root = findViewById(R.id.root);
        previewCard.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int w = previewCard.getMeasuredWidth(), h = previewCard.getMeasuredHeight();
        int margin = Ui.dp(this, 8);
        float x = Math.max(margin, Math.min(root.getWidth() - w - margin, centerX - w / 2f));
        float y;
        if (overSeekBar && controls.getVisibility() == View.VISIBLE) {
            int[] loc = new int[2];
            controls.getLocationInWindow(loc);
            y = loc[1] - h - Ui.dp(this, 10);
        } else {
            y = root.getHeight() * 0.5f - h / 2f;
        }
        previewCard.setX(x);
        previewCard.setY(Math.max(margin, y));
        centerInfo.setVisibility(overSeekBar ? View.GONE : centerInfo.getVisibility());
    }

    private void hideSeekPreview() {
        previewAt = -1;
        if (previewCard != null) previewCard.setVisibility(View.GONE);
    }

    // ---- lifecycle ------------------------------------------------------------------------

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (service != null) {
            attachVideo();
            refreshAll();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        leftPip = false;
        enterImmersive();
        bumpHide();
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        if (service == null) return;
        if (leftPip) {
            // The PiP window was dismissed: stop like closing the player.
            service.pause();
            detachVideo();
            finish();
            return;
        }
        if (isInPictureInPictureMode()) return;
        detachVideo();
        if (!prefs.backgroundPlay() && !isChangingConfigurations()) service.pause();
    }

    @Override
    protected void onDestroy() {
        if (seekPreview != null) seekPreview.release();
        main.removeCallbacksAndMessages(null);
        if (service != null) {
            detachVideo();
            service.removeListener(this);
        }
        unbindService(connection);
        super.onDestroy();
    }

    private void attachVideo() {
        if (service == null || attached) return;
        MediaPlayer p = service.player();
        p.attachViews(videoLayout, null, true, false);
        p.setVideoScale(SCALES[scaleIndex]);
        attached = true;
        service.setViewAttached(true);
        if (videoTrackOff) {
            // Back from background: turn video on and seek in place, so the demuxer restarts
            // at a keyframe instead of decoding from the middle of a GOP.
            videoTrackOff = false;
            p.setVideoTrackEnabled(true);
            if (p.isSeekable()) p.setTime(p.getTime());
        }
    }

    private void detachVideo() {
        if (service == null || !attached) return;
        MediaPlayer p = service.player();
        // Without a surface libVLC would stall decoding video; turn the track off so audio keeps going.
        if (p.getVideoTracksCount() > 0) {
            p.setVideoTrackEnabled(false);
            videoTrackOff = true;
        }
        p.detachViews();
        attached = false;
        service.setViewAttached(false);
    }

    @Override
    public void onEngineRestarted() {
        attached = false;
        if (started) attachVideo();
    }

    // ---- picture in picture ---------------------------------------------------------------

    private boolean supportsPip() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    private PictureInPictureParams pipParams() {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();
        Rational ratio = new Rational(16, 9);
        if (service != null) {
            org.videolan.libvlc.interfaces.IMedia.VideoTrack vt = service.player().getCurrentVideoTrack();
            if (vt != null && vt.width > 0 && vt.height > 0) {
                int w = vt.width, h = vt.height;
                if (vt.sarNum > 0 && vt.sarDen > 0) w = w * vt.sarNum / vt.sarDen;
                if (vt.orientation >= 5) { int x = w; w = h; h = x; }
                float r = (float) w / h;
                if (r > 2.39f) { w = 239; h = 100; } else if (r < 1 / 2.39f) { w = 100; h = 239; }
                ratio = new Rational(w, h);
            }
        }
        b.setAspectRatio(ratio);
        boolean playing = service != null && service.isPlaying();
        List<RemoteAction> actions = new ArrayList<>();
        actions.add(remote(R.drawable.ic_rewind, "Back 10 s", PlaybackService.ACTION_REWIND, 11));
        actions.add(remote(playing ? R.drawable.ic_pause : R.drawable.ic_play, playing ? "Pause" : "Play", PlaybackService.ACTION_TOGGLE, 12));
        actions.add(remote(R.drawable.ic_next, "Next", PlaybackService.ACTION_NEXT, 13));
        b.setActions(actions);
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(playing && service.hasVideo());
            b.setSeamlessResizeEnabled(true);
        }
        return b.build();
    }

    private RemoteAction remote(int icon, String label, String action, int code) {
        Intent i = new Intent(this, PlaybackService.class).setAction(action);
        PendingIntent pi = PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE);
        return new RemoteAction(Icon.createWithResource(this, icon), label, label, pi);
    }

    private String lastPipKey;

    /** Rebuilding PiP params costs binder calls, so only do it when something visible changed. */
    private void updatePipParams() {
        if (!supportsPip() || service == null) return;
        org.videolan.libvlc.interfaces.IMedia.VideoTrack vt = service.player().getCurrentVideoTrack();
        String key = service.isPlaying() + "/" + service.hasVideo() + "/" + (vt == null ? "" : vt.width + "x" + vt.height);
        if (key.equals(lastPipKey)) return;
        lastPipKey = key;
        try {
            setPictureInPictureParams(pipParams());
        } catch (Exception ignored) {
        }
    }

    private void enterPip() {
        if (!supportsPip() || service == null || service.current() == null) return;
        try {
            enterPictureInPictureMode(pipParams());
        } catch (Exception e) {
            info("Picture-in-picture is turned off for this app in system settings");
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT < 31 && service != null && service.isPlaying() && service.hasVideo()) enterPip();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPip, newConfig);
        if (inPip) {
            showControls(false);
            btnLock.setVisibility(View.GONE);
            centerInfo.setVisibility(View.GONE);
        } else {
            leftPip = true;
            main.postDelayed(() -> { if (!isFinishing()) showControls(true); }, 100);
        }
    }

    // ---- service callbacks ----------------------------------------------------------------

    private void refreshAll() {
        onItemChanged(service.current());
        onStateChanged();
        onTime(service.player().getTime(), service.length());
        refreshBadges();
    }

    @Override
    public void onItemChanged(MediaItem item) {
        if (seekPreview != null) seekPreview.setSource(item);
        errorCard.setVisibility(View.GONE);
        // Hide the previous item's last frame until the new one draws.
        findViewById(R.id.blank).setVisibility(View.VISIBLE);
        if (item == null) {
            title.setText("");
            subtitle.setText("");
            return;
        }
        title.setText(service.currentTitle());
        int q = service.queue().size();
        String pos = q > 1 ? (service.index() + 1) + " / " + q + "  ·  " : "";
        subtitle.setText(pos + (item.subtitle == null ? "" : item.subtitle));
        updateAudioPanel();
        refreshBadges();
    }

    @Override
    public void onStateChanged() {
        if (service == null) return;
        boolean playing = service.isPlaying();
        btnPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        int st = service.player().getPlayerState();
        boolean buffering = service.isResolving() || st == org.videolan.libvlc.interfaces.IMedia.State.Opening;
        loading.setVisibility(buffering && errorCard.getVisibility() != View.VISIBLE ? View.VISIBLE : View.GONE);
        findViewById(R.id.loading_text).setVisibility(loading.getVisibility() == View.VISIBLE && PlaybackService.fontsPreparing() ? View.VISIBLE : View.GONE);
        if (service.current() != null) title.setText(service.currentTitle());
        refreshBadges();
        updatePipParams();
        if (!playing) {
            main.removeCallbacks(hideControls);
            if (!isInPictureInPictureMode() && !locked) showControls(true);
        } else {
            bumpHide();
        }
    }

    @Override
    public void onTime(long time, long length) {
        View blank = findViewById(R.id.blank);
        if (blank.getVisibility() == View.VISIBLE && service != null && time > 0 && (service.hasVideo() || time > 1000)) {
            blank.setVisibility(View.GONE);
        }
        if (seeking) return;
        timeCurrent.setText(MediaFiles.formatTime(time));
        timeTotal.setText(length > 0 ? MediaFiles.formatTime(length) : "LIVE");
        seek.setEnabled(length > 0);
        seek.setProgress(length > 0 ? (int) (time * 1000 / length) : 0);
        if (loading.getVisibility() == View.VISIBLE && service != null && service.isPlaying()) {
            loading.setVisibility(View.GONE);
            findViewById(R.id.loading_text).setVisibility(View.GONE);
        }
    }

    @Override
    public void onTracksChanged() {
        updateAudioPanel();
        updatePipParams();
    }

    @Override
    public void onVideoOutput(boolean hasVideo) {
        if (hasVideo) findViewById(R.id.blank).setVisibility(View.GONE);
        updateAudioPanel();
        updatePipParams();
    }

    private void updateAudioPanel() {
        if (service == null) return;
        boolean audioOnly = service.current() != null && !service.hasVideo() && !service.isResolving()
                && service.player().getAudioTracksCount() > 0;
        audioPanel.setVisibility(audioOnly ? View.VISIBLE : View.GONE);
        if (audioOnly) {
            audioTitle.setText(service.currentTitle());
            MediaItem it = service.current();
            if (it != null && it.thumb != null) {
                audioArt.setImageTintList(null);
                audioArt.setPadding(0, 0, 0, 0);
                Ui.loadThumb(audioArt, it.thumb);
            }
        }
    }

    @Override
    public void onError(MediaItem item, String errTitle, String message) {
        loading.setVisibility(View.GONE);
        findViewById(R.id.loading_text).setVisibility(View.GONE);
        ((TextView) findViewById(R.id.error_title)).setText(errTitle);
        ((TextView) findViewById(R.id.error_message)).setText(message);
        boolean more = service != null && service.queue().size() > 1;
        findViewById(R.id.error_skip).setVisibility(more ? View.VISIBLE : View.GONE);
        errorCard.setVisibility(View.VISIBLE);
        showControls(true);
    }

    @Override
    public void onInfo(String text) {
        info(text);
    }

    @Override
    public void onRecordChanged(boolean recording, String path) {
        refreshBadges();
        if (!recording && path != null) info("Recording saved: " + new File(path).getName());
        else if (recording) info("Recording… tap ● REC to stop");
    }

    private void refreshBadges() {
        if (service == null) return;
        badgeRec.setVisibility(service.isRecording() ? View.VISIBLE : View.GONE);
        long a = service.abA(), b = service.abB();
        if (a >= 0) {
            badgeAb.setVisibility(View.VISIBLE);
            badgeAb.setText(b >= 0 ? "A-B " + MediaFiles.formatTime(a) + "–" + MediaFiles.formatTime(b) : "A " + MediaFiles.formatTime(a) + " – ?");
        } else {
            badgeAb.setVisibility(View.GONE);
        }
        findViewById(R.id.btn_ab).setAlpha(a >= 0 ? 1f : 0.85f);
        ((ImageButton) findViewById(R.id.btn_ab)).setImageTintList(android.content.res.ColorStateList.valueOf(a >= 0 ? getColor(R.color.accent) : 0xCCF1F1F3));
    }

    // ---- controls visibility --------------------------------------------------------------

    private void showControls(boolean show) {
        if (isInPictureInPictureMode()) show = false;
        controlsVisible = show;
        boolean full = show && !locked;
        fade(topBar, full);
        fade(controls, full);
        fade(btnLock, show);
        if (show) bumpHide();
    }

    private static void fade(View v, boolean show) {
        v.animate().cancel();
        if (show) {
            if (v.getVisibility() != View.VISIBLE) {
                v.setAlpha(0f);
                v.setVisibility(View.VISIBLE);
            }
            v.animate().alpha(1f).setDuration(150).start();
        } else if (v.getVisibility() == View.VISIBLE) {
            v.animate().alpha(0f).setDuration(200).withEndAction(() -> v.setVisibility(View.GONE)).start();
        }
    }

    private void bumpHide() {
        main.removeCallbacks(hideControls);
        if (service != null && service.isPlaying() && !seeking) main.postDelayed(hideControls, HIDE_DELAY);
    }

    private void setLocked(boolean l) {
        locked = l;
        btnLock.setImageResource(l ? R.drawable.ic_lock : R.drawable.ic_unlock);
        btnLock.setImageTintList(android.content.res.ColorStateList.valueOf(l ? getColor(R.color.accent) : getColor(R.color.text)));
        info(l ? "Controls locked" : "Controls unlocked");
        showControls(true);
    }

    private void info(String text) {
        centerInfo.setText(text);
        centerInfo.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideInfo);
        main.postDelayed(hideInfo, 1600);
    }

    private void enterImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    // ---- gestures -------------------------------------------------------------------------

    private void setupGestures() {
        View g = findViewById(R.id.gesture);
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                gestureMode = 0;
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                showControls(!controlsVisible);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (locked || service == null) return true;
                float x = e.getX() / g.getWidth();
                if (x < 0.35f) seekBy(-10000);
                else if (x > 0.65f) seekBy(10000);
                else service.toggle();
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (locked || service == null || e1 == null) return false;
                float totalX = e2.getX() - e1.getX();
                float totalY = e2.getY() - e1.getY();
                if (gestureMode == 0) {
                    // Ignore swipes that start in the system gesture areas at the screen edges.
                    if (e1.getY() < Ui.dp(PlayerActivity.this, 40) || e1.getY() > g.getHeight() - Ui.dp(PlayerActivity.this, 40)) return false;
                    if (Math.abs(totalX) > Math.abs(totalY)) {
                        if (service.length() <= 0 || !service.player().isSeekable()) return false;
                        gestureMode = 1;
                        gestureSeekBase = service.player().getTime();
                    } else if (e1.getX() < g.getWidth() / 2f) {
                        gestureMode = 2;
                        float cur = getWindow().getAttributes().screenBrightness;
                        gestureBrightness = cur < 0 ? systemBrightness() : cur;
                    } else {
                        gestureMode = 3;
                        gestureVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                    }
                }
                if (gestureMode == 1) {
                    // Full width swipe = 90 seconds.
                    long delta = (long) (totalX / g.getWidth() * 90000);
                    gestureSeekTarget = Math.max(0, Math.min(service.length(), gestureSeekBase + delta));
                    info((delta >= 0 ? "+" : "−") + MediaFiles.formatTime(Math.abs(delta)));
                    showSeekPreview(gestureSeekTarget, g.getWidth() / 2f, false);
                } else if (gestureMode == 2) {
                    gestureBrightness = Math.max(0.01f, Math.min(1f, gestureBrightness + dy / g.getHeight() * 1.2f));
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = gestureBrightness;
                    getWindow().setAttributes(lp);
                    info("Brightness " + Math.round(gestureBrightness * 100) + "%");
                } else if (gestureMode == 3) {
                    int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    gestureVolume = Math.max(0, Math.min(max, gestureVolume + dy / g.getHeight() * max * 1.2f));
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(gestureVolume), 0);
                    info("Volume " + Math.round(gestureVolume * 100 / max) + "%");
                }
                return true;
            }
        });
        g.setOnTouchListener((v, ev) -> {
            boolean handled = detector.onTouchEvent(ev);
            if ((ev.getActionMasked() == MotionEvent.ACTION_UP || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) && gestureMode == 1 && service != null) {
                service.seekTo(gestureSeekTarget);
                hideSeekPreview();
                gestureMode = 0;
            }
            return handled;
        });
    }

    private float systemBrightness() {
        try {
            return android.provider.Settings.System.getInt(getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f;
        } catch (Exception e) {
            return 0.5f;
        }
    }

    private void seekBy(long delta) {
        if (service == null) return;
        service.seekBy(delta);
        info((delta > 0 ? "+" : "−") + Math.abs(delta / 1000) + " s");
        bumpHide();
    }

    // ---- hotkeys (PotPlayer-style, for keyboards, Chromebooks and TV remotes) -------------

    private final java.util.Set<Integer> consumedKeys = new java.util.HashSet<>();

    /** Hotkeys run before focused buttons see the key, so Space never "clicks" a button. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (hotkey(code, event)) {
                consumedKeys.add(code);
                return true;
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP && consumedKeys.remove(code)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean hotkey(int keyCode, KeyEvent event) {
        if (service == null) return false;
        boolean ctrl = event.isCtrlPressed();
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                service.toggle();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!controlsVisible) { showControls(true); return true; }
                return false;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (controlsVisible && !event.isFromSource(android.view.InputDevice.SOURCE_KEYBOARD)) return false;
                seekBy(ctrl ? -30000 : -5000);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (controlsVisible && !event.isFromSource(android.view.InputDevice.SOURCE_KEYBOARD)) return false;
                seekBy(ctrl ? 30000 : 5000);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                if (controlsVisible && !event.isFromSource(android.view.InputDevice.SOURCE_KEYBOARD)) return false;
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, keyCode == KeyEvent.KEYCODE_DPAD_UP ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
                int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                info("Volume " + audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max + "%");
                return true;
            }
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                service.next(true);
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                service.previous();
                return true;
            case KeyEvent.KEYCODE_C: changeSpeed(service.rate() + 0.1f); return true;
            case KeyEvent.KEYCODE_X: changeSpeed(service.rate() - 0.1f); return true;
            case KeyEvent.KEYCODE_Z: changeSpeed(1f); return true;
            case KeyEvent.KEYCODE_COMMA: shiftSubDelay(-500); return true;
            case KeyEvent.KEYCODE_PERIOD: shiftSubDelay(500); return true;
            case KeyEvent.KEYCODE_SLASH: shiftSubDelay(Long.MIN_VALUE); return true;
            case KeyEvent.KEYCODE_LEFT_BRACKET: service.setAbA(); info("A set at " + MediaFiles.formatTime(service.abA())); refreshBadges(); return true;
            case KeyEvent.KEYCODE_RIGHT_BRACKET: service.setAbB(); info(service.abB() >= 0 ? "A-B repeat on" : "Set A first"); refreshBadges(); return true;
            case KeyEvent.KEYCODE_BACKSLASH: service.clearAb(); info("A-B repeat off"); refreshBadges(); return true;
            case KeyEvent.KEYCODE_E: if (ctrl) { screenshot(); return true; } break;
            case KeyEvent.KEYCODE_M: toggleMute(); return true;
            case KeyEvent.KEYCODE_S: if (!ctrl) { cycleSubtitle(); return true; } break;
            case KeyEvent.KEYCODE_L: if (!ctrl) { cycleAudio(); return true; } break;
            case KeyEvent.KEYCODE_P: if (ctrl) { enterPip(); return true; } break;
            case KeyEvent.KEYCODE_TAB: showControls(!controlsVisible); return true;
            default:
                break;
        }
        return false;
    }

    private int volumeBeforeMute = -1;

    private void toggleMute() {
        if (volumeBeforeMute < 0) {
            volumeBeforeMute = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            info("Muted");
        } else {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volumeBeforeMute, 0);
            volumeBeforeMute = -1;
            info("Sound on");
        }
    }

    // ---- tracks, subtitles, speed --------------------------------------------------------

    private void showSubtitleDialog() {
        if (service == null) return;
        MediaPlayer p = service.player();
        MediaPlayer.TrackDescription[] tracks = p.getSpuTracks();
        int selected = p.getSpuTrack();
        List<String> labels = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        if (tracks == null || tracks.length == 0) {
            labels.add("No subtitles in this file");
            ids.add(Integer.MIN_VALUE);
        } else {
            for (MediaPlayer.TrackDescription t : tracks) {
                labels.add((t.id == selected ? "● " : "    ") + (t.id == -1 ? "Off" : t.name));
                ids.add(t.id);
            }
        }
        labels.add("Load subtitle file…");
        labels.add("Sync: " + delayText(p.getSpuDelay()) + "  (adjust…)");
        labels.add("Style…");
        Ui.dialog(this).setTitle("Subtitles").setItems(labels.toArray(new String[0]), (d, w) -> {
            int extra = w - ids.size();
            if (extra < 0) {
                if (ids.get(w) != Integer.MIN_VALUE) p.setSpuTrack(ids.get(w));
            } else if (extra == 0) {
                pickSubtitleFile();
            } else if (extra == 1) {
                delayDialog(true);
            } else {
                styleDialog();
            }
        }).show();
    }

    private void cycleSubtitle() {
        MediaPlayer p = service.player();
        MediaPlayer.TrackDescription[] t = p.getSpuTracks();
        if (t == null || t.length == 0) { info("No subtitles"); return; }
        int cur = p.getSpuTrack(), i = 0;
        for (int k = 0; k < t.length; k++) if (t[k].id == cur) i = k;
        MediaPlayer.TrackDescription n = t[(i + 1) % t.length];
        p.setSpuTrack(n.id);
        info("Subtitles: " + (n.id == -1 ? "Off" : n.name));
    }

    private void cycleAudio() {
        MediaPlayer p = service.player();
        MediaPlayer.TrackDescription[] t = p.getAudioTracks();
        if (t == null || t.length <= 1) { info("Only one audio track"); return; }
        int cur = p.getAudioTrack(), i = 0;
        for (int k = 0; k < t.length; k++) if (t[k].id == cur) i = k;
        MediaPlayer.TrackDescription n = t[(i + 1) % t.length];
        if (n.id == -1) n = t[(i + 2) % t.length];
        p.setAudioTrack(n.id);
        info("Audio: " + n.name);
    }

    private void pickSubtitleFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try {
            startActivityForResult(i, REQ_SUBTITLE);
        } catch (Exception e) {
            info("No file picker available");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SUBTITLE && resultCode == RESULT_OK && data != null && data.getData() != null && service != null) {
            Uri uri = data.getData();
            // libVLC reads content:// subtitles poorly; copy to cache with the right extension.
            try {
                String name = displayName(uri);
                if (name == null) name = "subtitle.srt";
                File out = new File(getCacheDir(), "subs/" + System.currentTimeMillis() + "_" + name.replaceAll("[^\\w.\\-]", "_"));
                out.getParentFile().mkdirs();
                try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                     java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                }
                boolean ok = service.player().addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle, Uri.fromFile(out), true);
                info(ok ? "Subtitle loaded: " + name : "Could not load " + name);
            } catch (Exception e) {
                info("Could not read the subtitle file");
            }
        }
    }

    private static String delayText(long us) {
        return String.format(Locale.ROOT, "%+.1f s", us / 1_000_000.0);
    }

    private void shiftSubDelay(long deltaMs) {
        MediaPlayer p = service.player();
        long d = deltaMs == Long.MIN_VALUE ? 0 : p.getSpuDelay() + deltaMs * 1000;
        p.setSpuDelay(d);
        info("Subtitle delay " + delayText(d));
    }

    /** Sync adjuster shared by subtitles and audio. */
    private void delayDialog(boolean subtitles) {
        MediaPlayer p = service.player();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView value = Ui.text(this, "", 28, getColor(R.color.accent));
        value.setGravity(Gravity.CENTER);
        TextView hint = Ui.text(this, subtitles ? "Positive = subtitles appear later" : "Positive = sound plays later", 12, getColor(R.color.text_dim));
        hint.setGravity(Gravity.CENTER);
        Runnable show = () -> value.setText(delayText(subtitles ? p.getSpuDelay() : p.getAudioDelay()));
        show.run();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        long[] steps = {-500, -100, 100, 500};
        for (long s : steps) {
            TextView b = Ui.button(this, (s > 0 ? "+" : "−") + Math.abs(s) / 1000.0, false);
            b.setOnClickListener(v -> {
                long cur = subtitles ? p.getSpuDelay() : p.getAudioDelay();
                if (subtitles) p.setSpuDelay(cur + s * 1000);
                else p.setAudioDelay(cur + s * 1000);
                show.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            row.addView(b, lp);
        }
        box.addView(value);
        box.addView(hint);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Ui.dp(this, 14);
        box.addView(row, rlp);
        Ui.dialog(this).setTitle(subtitles ? "Subtitle sync" : "Audio sync").setView(Ui.padded(this, box))
                .setPositiveButton("Done", null)
                .setNeutralButton("Reset", (d, w) -> { if (subtitles) p.setSpuDelay(0); else p.setAudioDelay(0); })
                .show();
    }

    private void styleDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        android.widget.Spinner size = spinner(Prefs.SUB_SIZE_NAMES, prefs.subSize());
        android.widget.Spinner color = spinner(Prefs.SUB_COLOR_NAMES, prefs.subColor());
        android.widget.Spinner bg = spinner(Prefs.SUB_BG_NAMES, prefs.subBg());
        android.widget.CheckBox bold = new android.widget.CheckBox(this);
        bold.setText("Bold");
        bold.setTextColor(getColor(R.color.text));
        bold.setChecked(prefs.subBold());
        box.addView(Ui.text(this, "Size", 12, getColor(R.color.text_dim)));
        box.addView(size);
        box.addView(Ui.text(this, "Colour", 12, getColor(R.color.text_dim)));
        box.addView(color);
        box.addView(Ui.text(this, "Background", 12, getColor(R.color.text_dim)));
        box.addView(bg);
        box.addView(bold);
        Ui.dialog(this).setTitle("Subtitle style").setView(Ui.padded(this, box))
                .setPositiveButton("Apply", (d, w) -> {
                    prefs.setSubStyle(size.getSelectedItemPosition(), color.getSelectedItemPosition(), bg.getSelectedItemPosition(), bold.isChecked());
                    if (service != null) service.restartEngine();
                    info("Subtitle style applied");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private android.widget.Spinner spinner(String[] items, int sel) {
        android.widget.Spinner s = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> a = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items);
        s.setAdapter(a);
        s.setSelection(Math.max(0, Math.min(items.length - 1, sel)));
        return s;
    }

    private void showAudioDialog() {
        if (service == null) return;
        MediaPlayer p = service.player();
        MediaPlayer.TrackDescription[] tracks = p.getAudioTracks();
        int selected = p.getAudioTrack();
        List<String> labels = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        if (tracks != null) {
            for (MediaPlayer.TrackDescription t : tracks) {
                labels.add((t.id == selected ? "● " : "    ") + (t.id == -1 ? "Mute (disable audio)" : t.name));
                ids.add(t.id);
            }
        }
        if (ids.isEmpty()) labels.add("No audio track");
        labels.add("Audio sync: " + delayText(p.getAudioDelay()) + "  (adjust…)");
        int base = ids.isEmpty() ? 1 : ids.size();
        Ui.dialog(this).setTitle("Audio track").setItems(labels.toArray(new String[0]), (d, w) -> {
            if (w < ids.size()) p.setAudioTrack(ids.get(w));
            else if (w == base) delayDialog(false);
        }).show();
    }

    private void changeSpeed(float r) {
        r = Math.round(Math.max(0.25f, Math.min(4f, r)) * 100) / 100f;
        service.setRate(r);
        info(String.format(Locale.ROOT, "Speed %.2f×", r));
    }

    private void showSpeedDialog() {
        if (service == null) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView value = Ui.text(this, "", 28, getColor(R.color.accent));
        value.setGravity(Gravity.CENTER);
        TextView hint = Ui.text(this, "Pitch is kept natural at every speed", 12, getColor(R.color.text_dim));
        hint.setGravity(Gravity.CENTER);
        SeekBar bar = new SeekBar(this);
        bar.setMax(75); // 0.25 .. 4.00 in 0.05 steps
        bar.setProgress(Math.round((service.rate() - 0.25f) / 0.05f));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        value.setText(String.format(Locale.ROOT, "%.2f×", service.rate()));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                float r = 0.25f + p * 0.05f;
                value.setText(String.format(Locale.ROOT, "%.2f×", r));
                if (user) service.setRate(r);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        LinearLayout row = new LinearLayout(this);
        for (float f : SPEED_PRESETS) {
            TextView b = Ui.button(this, (f == (int) f ? String.valueOf((int) f) : String.valueOf(f)) + "×", f == 1f);
            b.setOnClickListener(v -> { service.setRate(f); bar.setProgress(Math.round((f - 0.25f) / 0.05f)); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Ui.dp(this, 6));
            row.addView(b, lp);
        }
        hs.addView(row);
        box.addView(value);
        box.addView(hint);
        box.addView(bar);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = Ui.dp(this, 10);
        box.addView(hs, hlp);
        Ui.dialog(this).setTitle("Playback speed").setView(Ui.padded(this, box)).setPositiveButton("Done", null).show();
    }

    // ---- more menu ------------------------------------------------------------------------

    private void showMoreMenu(View anchor) {
        if (service == null) return;
        main.removeCallbacks(hideControls);
        PopupMenu m = new PopupMenu(this, anchor);
        Menu menu = m.getMenu();
        String[] repeatNames = {"Play once", "Repeat file", "Repeat all"};
        menu.add(0, 1, 0, "Queue (" + service.queue().size() + ")");
        menu.add(0, 2, 0, "Repeat: " + repeatNames[service.repeatMode()]);
        menu.add(0, 3, 0, "Shuffle").setCheckable(true).setChecked(service.shuffle());
        menu.add(0, 4, 0, "Save queue as playlist…");
        menu.add(0, 5, 0, "Add this to a playlist…");
        menu.add(0, 6, 0, "Video size: " + SCALE_NAMES[scaleIndex]);
        menu.add(0, 7, 0, service.isRecording() ? "Stop recording" : "Record a portion");
        menu.add(0, 8, 0, "Save stream…");
        menu.add(0, 9, 0, "Subtitle style…");
        menu.add(0, 10, 0, "YouTube quality: " + prefs.ytQuality() + "p");
        menu.add(0, 11, 0, "Play in background").setCheckable(true).setChecked(prefs.backgroundPlay());
        menu.add(0, 12, 0, "Hardware decoding").setCheckable(true).setChecked(prefs.hwDecoding());
        menu.add(0, 13, 0, "Resume where I left off").setCheckable(true).setChecked(prefs.resumeEnabled());
        menu.add(0, 14, 0, "Keyboard shortcuts");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: queueDialog(); break;
                case 2:
                    Ui.dialog(this).setTitle("Repeat").setSingleChoiceItems(repeatNames, service.repeatMode(), (d, w) -> {
                        service.setRepeatMode(w);
                        info(repeatNames[w]);
                        d.dismiss();
                    }).show();
                    break;
                case 3: service.setShuffle(!service.shuffle()); info(service.shuffle() ? "Shuffle on" : "Shuffle off"); break;
                case 4:
                    Ui.prompt(this, "Save queue as playlist", "Playlist name", service.currentTitle(), name -> {
                        playlists.save(name, service.queue());
                        CacheService.onAddedToPlaylist(this, service.queue());
                        info("Saved playlist \"" + name + "\"");
                    });
                    break;
                case 5:
                    if (service.current() != null) Ui.choosePlaylist(this, playlists, name -> {
                        playlists.append(name, Collections.singletonList(service.current()));
                        CacheService.onAddedToPlaylist(this, Collections.singletonList(service.current()));
                        info("Added to \"" + name + "\"");
                    });
                    break;
                case 6:
                    Ui.dialog(this).setTitle("Video size").setSingleChoiceItems(SCALE_NAMES, scaleIndex, (d, w) -> {
                        scaleIndex = w;
                        service.player().setVideoScale(SCALES[w]);
                        d.dismiss();
                    }).show();
                    break;
                case 7: toggleRecording(); break;
                case 8: saveStream(); break;
                case 9: styleDialog(); break;
                case 10: {
                    int cur = 0;
                    for (int k = 0; k < Prefs.YT_QUALITY_VALUES.length; k++) if (Prefs.YT_QUALITY_VALUES[k] == prefs.ytQuality()) cur = k;
                    Ui.dialog(this).setTitle("YouTube quality (max)").setSingleChoiceItems(Prefs.YT_QUALITY_NAMES, cur, (d, w) -> {
                        prefs.setYtQuality(Prefs.YT_QUALITY_VALUES[w]);
                        d.dismiss();
                        MediaItem it = service.current();
                        if (it != null && it.kind == MediaItem.YOUTUBE) service.playIndex(service.index());
                    }).show();
                    break;
                }
                case 11: prefs.setBackgroundPlay(!prefs.backgroundPlay()); break;
                case 12:
                    prefs.setHwDecoding(!prefs.hwDecoding());
                    info(prefs.hwDecoding() ? "Hardware decoding on" : "Software decoding");
                    if (service.current() != null) service.restartEngine();
                    break;
                case 13: prefs.setResumeEnabled(!prefs.resumeEnabled()); break;
                case 14: shortcutsDialog(); break;
                default: break;
            }
            return true;
        });
        m.setOnDismissListener(x -> bumpHide());
        m.show();
    }

    private void queueDialog() {
        List<MediaItem> q = service.queue();
        if (q.isEmpty()) { info("Queue is empty"); return; }
        String[] names = new String[q.size()];
        for (int i = 0; i < q.size(); i++) names[i] = (i == service.index() ? "▶  " : (i + 1) + ".  ") + q.get(i).title;
        android.app.AlertDialog d = Ui.dialog(this).setTitle("Queue · long-press to remove")
                .setItems(names, (dlg, w) -> service.playIndex(w))
                .setPositiveButton("Close", null)
                .create();
        d.setOnShowListener(x -> d.getListView().setOnItemLongClickListener((parent, view, pos, id) -> {
            service.removeFromQueue(pos);
            d.dismiss();
            queueDialog();
            return true;
        }));
        d.show();
    }

    private void toggleRecording() {
        MediaItem it = service.current();
        if (it == null) return;
        if (service.isRecording()) {
            service.stopRecording();
            return;
        }
        if (!it.savingAllowed()) {
            Ui.dialog(this).setTitle("Recording is off for YouTube")
                    .setMessage("Astra Player does not record or export YouTube videos to files. To watch one offline, long-press it (or open its playlist) and choose \"Cache for offline\": Astra keeps a private copy inside the app.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        if (!service.startRecording()) info("This media cannot be recorded");
        else info("Recording to " + service.recordDir().getName() + " — tap ● REC to stop");
    }

    private void saveStream() {
        MediaItem it = service.current();
        if (it == null) return;
        if (!it.savingAllowed()) {
            toggleRecording();
            return;
        }
        if (it.kind == MediaItem.LOCAL) {
            info("This file is already on your device. Use \"Record a portion\" to cut a part.");
            return;
        }
        String u = it.uri.toLowerCase(Locale.ROOT);
        boolean direct = (u.startsWith("http://") || u.startsWith("https://")) && !u.contains(".m3u8") && !u.contains(".mpd");
        String msg = direct
                ? "Download the whole file to Download/Astra Player, or record from the current position?\n\nOnly save media you own or are allowed to keep."
                : "This is a live or segmented stream. Astra Player can record it from the current position until you stop.\n\nOnly record streams you are allowed to keep.";
        android.app.AlertDialog.Builder b = Ui.dialog(this).setTitle("Save stream").setMessage(msg)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Record from now", (d, w) -> toggleRecording());
        if (direct) b.setPositiveButton("Download file", (d, w) -> download(it));
        b.show();
    }

    private void download(MediaItem it) {
        try {
            String name = MediaItem.guessTitle(it.uri).replaceAll("[\\\\/:*?\"<>|]", "_");
            if (MediaFiles.ext(name).isEmpty()) name = name + ".mp4";
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(it.uri))
                    .setTitle(name)
                    .setDescription("Astra Player")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Astra Player/" + name);
            getSystemService(DownloadManager.class).enqueue(r);
            info("Downloading to Download/Astra Player");
        } catch (Exception e) {
            info("Download failed: " + e.getMessage());
        }
    }

    private void shortcutsDialog() {
        String s = "Space — play / pause\n"
                + "← / → — seek 5 s (Ctrl: 30 s)\n"
                + "↑ / ↓ — volume\n"
                + "Page Up / Page Down — previous / next\n"
                + "C / X / Z — faster / slower / normal speed\n"
                + ", / . — subtitle sync −0.5 s / +0.5 s ( / resets)\n"
                + "[ / ] / \\ — set A / set B / clear A-B repeat\n"
                + "S — next subtitle track, L — next audio track\n"
                + "M — mute, Ctrl+E — screenshot, Ctrl+P — picture-in-picture\n\n"
                + "Touch: double-tap sides to seek 10 s, middle to pause; swipe left side for brightness, right side for volume, sideways to seek.";
        Ui.dialog(this).setTitle("Shortcuts").setMessage(s).setPositiveButton("OK", null).show();
    }

    // ---- screenshot -----------------------------------------------------------------------

    private SurfaceView findSurface(View v) {
        if (v instanceof SurfaceView && v.getWidth() > 1) return (SurfaceView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                SurfaceView s = findSurface(g.getChildAt(i));
                if (s != null) return s;
            }
        }
        return null;
    }

    private void screenshot() {
        if (service == null || !service.hasVideo()) {
            info("Nothing to capture");
            return;
        }
        SurfaceView sv = findSurface(videoLayout);
        if (sv == null || !sv.getHolder().getSurface().isValid()) {
            info("Video surface not ready");
            return;
        }
        int w = sv.getWidth(), h = sv.getHeight();
        org.videolan.libvlc.interfaces.IMedia.VideoTrack vt = service.player().getCurrentVideoTrack();
        if (vt != null && vt.width > 0 && vt.height > 0 && vt.width <= 4096 && vt.height <= 4096 && vt.orientation < 5) {
            w = vt.width;
            h = vt.height;
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        PixelCopy.request(sv, bmp, result -> {
            if (result != PixelCopy.SUCCESS) {
                info("Screenshot failed");
                return;
            }
            new Thread(() -> {
                String name = "Astra_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date()) + ".png";
                boolean ok = savePicture(bmp, name);
                main.post(() -> info(ok ? "Screenshot saved to Pictures/Astra Player" : "Could not save screenshot"));
            }).start();
        }, main);
    }

    private boolean savePicture(Bitmap bmp, String name) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Astra Player");
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return false;
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    return bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
            }
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Astra Player");
            dir.mkdirs();
            try (OutputStream out = new java.io.FileOutputStream(new File(dir, name))) {
                return bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        } catch (Exception e) {
            return false;
        }
    }
}
