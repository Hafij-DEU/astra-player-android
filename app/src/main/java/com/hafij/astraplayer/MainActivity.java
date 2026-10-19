package com.hafij.astraplayer;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/** Library screen: Files, Playlists, Streams and YouTube tabs plus a now-playing bar. */
public class MainActivity extends Activity implements PlaybackService.Listener {
    private static final int TAB_FILES = 0, TAB_PLAYLISTS = 1, TAB_STREAMS = 2, TAB_YOUTUBE = 3, TAB_SETTINGS = 4;
    /** Views tagged with this move below the list in one-hand mode. */
    private static final String BOTTOMABLE = "bottomable";
    private static final int REQ_OPEN = 11, REQ_STORAGE = 12, REQ_NOTIFY = 13;
    private static final long SCAN_INTERVAL_MS = 30_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Prefs prefs;
    private PlaylistStore playlists;
    private PlaybackService service;

    private FrameLayout content;
    private LinearLayout nowBar;
    private TextView nowTitle, nowSub;
    private ImageButton nowPlay;
    private final List<LinearLayout> tabs = new ArrayList<>();
    private int tab = TAB_FILES;

    // Files tab. In browse mode `folder` is the current directory; in the other views it is
    // the opened video folder (null = the folder or video list).
    private File folder;
    private final ExecutorService scanner = Executors.newSingleThreadExecutor();
    // Per library (Prefs.KIND_VIDEO / KIND_MUSIC): cached folders, scan state, flat file list.
    @SuppressWarnings("unchecked")
    private final List<MediaFiles.VideoFolder>[] folders = new List[]{new ArrayList<>(), new ArrayList<>()};
    private final String[] foldersJson = new String[2];
    private final boolean[] scanning = new boolean[2];
    private final long[] lastScan = new long[2];
    @SuppressWarnings("unchecked")
    private final List<File>[] allFiles = new List[2]; // null until built from folders
    private final boolean[] allBuilding = new boolean[2];
    private final int[] allGen = new int[2];
    private boolean hadStorageAccess;
    private boolean destroyed;
    private BaseAdapter filesAdapter;
    private TextView filesStatus;
    // Offline cache: views refreshed in place when a download progresses.
    private BaseAdapter cacheAdapter;
    private TextView cacheStatus;
    private final Runnable cacheListener = this::onCacheChanged;
    private String filesQuery = "";
    // Playlists tab: null = overview, otherwise the open playlist
    private String openPlaylist;
    /** The automatic "Offline videos" playlist is open. */
    private boolean openOffline;
    /** YouTube tab section: 0 Search, 1 Offline videos, 2 YouTube playlists. */
    private int ytSection;
    private int offlineShown = -1;
    // YouTube tab state survives tab switches
    private String ytQuery = "";
    private final List<MediaItem> ytResults = new ArrayList<>();
    private YouTube.Results ytPage;
    private boolean ytLoading;
    private String ytError;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlaybackService.LocalBinder) binder).service();
            service.addListener(MainActivity.this);
            refreshNowBar();
            if (tab == TAB_PLAYLISTS) showTab(tab);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Ui.applyTheme(this, new Prefs(base).theme());
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        playlists = new PlaylistStore(this);
        String last = prefs.lastFolder();
        folder = last != null && new File(last).isDirectory() ? new File(last) : null;
        // Show the last scan at once; onResume refreshes it in the background.
        for (int k = 0; k < 2; k++) {
            foldersJson[k] = prefs.folderCache(k);
            folders[k] = MediaFiles.foldersFromJson(foldersJson[k]);
        }
        hadStorageAccess = hasStorageAccess();
        CacheService.addListener(cacheListener);
        CacheService.resumeQueue(this);
        buildLayout();
        if (state != null) tab = state.getInt("tab", TAB_FILES);
        showTab(tab);
        bindService(new Intent(this, PlaybackService.class), connection, BIND_AUTO_CREATE);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean access = hasStorageAccess();
        if (tab == TAB_FILES) {
            // Rebuild only when access changed (back from the settings page); otherwise keep
            // the scroll position and just refresh rows so resume positions update.
            if (access != hadStorageAccess) showTab(TAB_FILES);
            else {
                if (filesAdapter != null) filesAdapter.notifyDataSetChanged();
                addContinueButton();
            }
        } else if (tab == TAB_PLAYLISTS) {
            showTab(TAB_PLAYLISTS);
        }
        hadStorageAccess = access;
        if (access && prefs.currentView() != Prefs.FILES_BROWSE && SystemClock.elapsedRealtime() - lastScan[kind()] > SCAN_INTERVAL_MS) {
            rescan();
        }
        refreshNowBar();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        CacheService.removeListener(cacheListener);
        if (service != null) service.removeListener(this);
        unbindService(connection);
        worker.shutdownNow();
        scanner.shutdownNow();
        super.onDestroy();
    }

    // ---- layout ---------------------------------------------------------------------------

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));
        root.setFitsSystemWindows(true);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(14), dp(8), dp(6));
        TextView logo = Ui.text(this, "Astra", 24, getColor(R.color.accent));
        logo.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        float lw = logo.getPaint().measureText("Astra");
        logo.getPaint().setShader(new android.graphics.LinearGradient(0, 0, lw, 0,
                new int[]{getColor(R.color.amber), getColor(R.color.c_orange), getColor(R.color.c_rose)}, null,
                android.graphics.Shader.TileMode.CLAMP));
        TextView logo2 = Ui.text(this, " Player", 24, getColor(R.color.text));
        logo2.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        header.addView(logo);
        header.addView(logo2, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton open = iconButton(R.drawable.ic_add, "Open files");
        open.setOnClickListener(v -> openDocuments());
        header.addView(open);
        ImageButton about = iconButton(R.drawable.ic_more, "About");
        about.setOnClickListener(this::showAppMenu);
        header.addView(about);
        root.addView(header);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        nowBar = new LinearLayout(this);
        nowBar.setGravity(Gravity.CENTER_VERTICAL);
        nowBar.setBackgroundResource(R.drawable.bg_now);
        nowBar.setPadding(dp(14), dp(8), dp(8), dp(8));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        nowTitle = Ui.text(this, "", 14, getColor(R.color.text));
        nowTitle.setSingleLine(true);
        nowTitle.setEllipsize(TextUtils.TruncateAt.END);
        nowSub = Ui.text(this, "", 12, getColor(R.color.accent));
        nowSub.setSingleLine(true);
        texts.addView(nowTitle);
        texts.addView(nowSub);
        nowBar.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        nowPlay = new ImageButton(this);
        nowPlay.setBackgroundResource(R.drawable.bg_play);
        nowPlay.setImageTintList(ColorStateList.valueOf(0xFF101013));
        nowPlay.setPadding(dp(8), dp(8), dp(8), dp(8));
        nowPlay.setScaleType(ImageView.ScaleType.FIT_CENTER);
        nowPlay.setContentDescription("Play or pause");
        nowPlay.setOnClickListener(v -> { if (service != null) service.toggle(); });
        nowBar.addView(nowPlay, new LinearLayout.LayoutParams(dp(40), dp(40)));
        nowBar.setOnClickListener(v -> PlayerActivity.open(this));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.setMargins(dp(10), dp(4), dp(10), dp(4));
        nowBar.setVisibility(View.GONE);
        root.addView(nowBar, nlp);

        LinearLayout nav = new LinearLayout(this);
        nav.setBackgroundColor(getColor(R.color.nav));
        nav.setPadding(0, dp(4), 0, dp(4));
        String[] names = {"Files", "Playlists", "Streams", "YouTube", "Settings"};
        int[] icons = {R.drawable.ic_folder, R.drawable.ic_playlist, R.drawable.ic_link, R.drawable.ic_search, R.drawable.ic_tune};
        for (int i = 0; i < names.length; i++) {
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(6), 0, dp(6));
            t.setBackgroundResource(R.drawable.bg_round_btn);
            ImageView iv = new ImageView(this);
            iv.setImageResource(icons[i]);
            iv.setBackgroundResource(R.drawable.bg_nav_pill);
            iv.setPadding(dp(16), dp(4), dp(16), dp(4));
            t.addView(iv, new LinearLayout.LayoutParams(dp(56), dp(30)));
            TextView tv = Ui.text(this, names[i], 11, getColor(R.color.text_dim));
            tv.setGravity(Gravity.CENTER);
            t.addView(tv);
            final int idx = i;
            t.setOnClickListener(v -> {
                openPlaylist = null;
                openOffline = false;
                showTab(idx);
            });
            nav.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            tabs.add(t);
        }
        root.addView(nav);
        setContentView(root);
    }

    private ImageButton iconButton(int res, String desc) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setBackgroundResource(R.drawable.bg_round_btn);
        b.setContentDescription(desc);
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        return b;
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private void showTab(int t) {
        tab = t;
        int[] tabColors = {getColor(R.color.accent), getColor(R.color.c_violet), getColor(R.color.c_teal),
                getColor(R.color.c_rose), getColor(R.color.c_green)};
        for (int i = 0; i < tabs.size(); i++) {
            LinearLayout l = tabs.get(i);
            boolean on = i == t;
            int color = on ? tabColors[i] : getColor(R.color.text_dim);
            ImageView iv = (ImageView) l.getChildAt(0);
            iv.setImageTintList(ColorStateList.valueOf(color));
            iv.setBackgroundTintList(ColorStateList.valueOf(on ? Ui.soft(tabColors[i]) : 0));
            TextView tv = (TextView) l.getChildAt(1);
            tv.setTextColor(color);
            tv.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        content.removeAllViews();
        cacheAdapter = null;
        cacheStatus = null;
        cacheStatusItems = null;
        cacheSettingsRow = null;
        View v;
        switch (t) {
            case TAB_PLAYLISTS: v = buildPlaylists(); break;
            case TAB_STREAMS: v = buildStreams(); break;
            case TAB_YOUTUBE: v = buildYouTube(); break;
            case TAB_SETTINGS: v = buildSettings(); break;
            default: v = buildFiles(); break;
        }
        if (prefs.oneHand()) moveBottomables(v);
        content.addView(v);
        if (t == TAB_FILES) addContinueButton();
    }

    /**
     * Floating "Continue" button on the Files tab: plays the last item again from where it was
     * left (the saved resume position). Local files queue their folder again.
     */
    private View continueFab;

    private void addContinueButton() {
        if (continueFab != null) content.removeView(continueFab);
        continueFab = null;
        MediaItem last = prefs.lastPlayed();
        if (last == null) return;
        boolean local = last.isLocalPath();
        if (local && !new File(last.uri).isFile()) return;
        long pos = prefs.resumePosition(last.uri);
        TextView fab = Ui.text(this, "▶  " + last.title + (pos > 0 ? "  ·  " + MediaFiles.formatTime(pos) : ""), 14, 0xFF101013);
        fab.setTypeface(Typeface.DEFAULT_BOLD);
        fab.setSingleLine(true);
        fab.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        fab.setMaxWidth(Math.min(getResources().getDisplayMetrics().widthPixels - dp(48), dp(420)));
        fab.setGravity(Gravity.CENTER_VERTICAL);
        fab.setPadding(dp(18), dp(12), dp(20), dp(12));
        fab.setBackgroundResource(R.drawable.bg_chip);
        fab.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.amber)));
        fab.setElevation(dp(6));
        fab.setContentDescription("Continue " + last.title);
        fab.setOnClickListener(v -> {
            if (local) playFrom(new File(last.uri));
            else play(Collections.singletonList(last), 0);
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        // Stay above the bottom toolbar in one-hand mode.
        lp.setMargins(dp(16), dp(16), dp(16), prefs.oneHand() ? dp(110) : dp(16));
        content.addView(fab, lp);
        continueFab = fab;
    }

    private void showAppMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Play in background").setCheckable(true).setChecked(prefs.backgroundPlay());
        m.getMenu().add(0, 2, 0, "Resume where I left off").setCheckable(true).setChecked(prefs.resumeEnabled());
        m.getMenu().add(0, 4, 0, "Theme: " + Prefs.THEME_NAMES[prefs.theme()]);
        m.getMenu().add(0, 5, 0, "Files view: " + (musicKind() ? Prefs.MUSIC_VIEW_NAMES : Prefs.FILES_VIEW_NAMES)[prefs.currentView()]);
        m.getMenu().add(0, 6, 0, "Use as music player").setCheckable(true).setChecked(prefs.musicLibrary());
        m.getMenu().add(0, 3, 0, "About Astra Player");
        m.setOnMenuItemClickListener(i -> {
            if (i.getItemId() == 1) prefs.setBackgroundPlay(!prefs.backgroundPlay());
            else if (i.getItemId() == 2) prefs.setResumeEnabled(!prefs.resumeEnabled());
            else if (i.getItemId() == 4) chooseTheme();
            else if (i.getItemId() == 5) chooseFilesView();
            else if (i.getItemId() == 6) {
                if (tab != TAB_FILES) showTab(TAB_FILES);
                setMusicLibrary(!prefs.musicLibrary());
            }
            else {
                showAbout();
            }
            return true;
        });
        m.show();
    }

    private void showAbout() {
        String v = "";
        long code = 0;
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            v = pi.versionName;
            code = pi.getLongVersionCode();
        } catch (Exception ignored) {
        }
        String text = "A free media player with no ads, no account and no subscription.\n\n"
                + "Made by Md Hafijur Rahman (Hafij-DEU)\n"
                + "Version " + v + " (build " + code + ")\n"
                + "Android companion of Astra Player for Linux\n\n"
                + "Source and releases:\nhttps://github.com/Hafij-DEU/astra-player-android\n"
                + "Linux version:\nhttps://github.com/Hafij-DEU/video-player\n\n"
                + "Playback: libVLC (LGPL 2.1+), VideoLAN.\n"
                + "YouTube: NewPipeExtractor (GPL 3.0+), Team NewPipe.\n\n"
                + "Astra Player is free software under the GNU GPL 3.0 or later. "
                + "It is not affiliated with PotPlayer, VideoLAN, NewPipe or YouTube.";
        TextView t = Ui.text(this, text, 14, getColor(R.color.text));
        t.setLineSpacing(0, 1.15f);
        t.setLinkTextColor(getColor(R.color.accent));
        android.text.util.Linkify.addLinks(t, android.text.util.Linkify.WEB_URLS);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(t);
        Ui.dialog(this).setTitle("Astra Player " + v).setView(Ui.padded(this, sv))
                .setPositiveButton("OK", null).show();
    }

    /** One-hand mode: search fields and toolbars go below the list, near the thumb. */
    private static void moveBottomables(View v) {
        if (!(v instanceof LinearLayout)) return;
        LinearLayout box = (LinearLayout) v;
        List<View> move = new ArrayList<>();
        for (int i = 0; i < box.getChildCount(); i++) {
            View c = box.getChildAt(i);
            if (BOTTOMABLE.equals(c.getTag())) move.add(c);
        }
        // Status lines first, so the toolbar ends up last, right above the tab bar.
        move.sort((a, b) -> Boolean.compare(!(a instanceof TextView), !(b instanceof TextView)));
        for (View c : move) {
            box.removeView(c);
            box.addView(c);
        }
    }

    // ---- Settings tab ---------------------------------------------------------------------

    private View buildSettings() {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), 0, dp(12), dp(16));
        sv.addView(box);

        settingsSection(box, "Appearance", getColor(R.color.c_rose));
        settingsRow(box, R.drawable.ic_tune, getColor(R.color.c_rose), "Theme", Prefs.THEME_NAMES[prefs.theme()], this::chooseTheme);
        settingsSwitch(box, R.drawable.ic_up, getColor(R.color.c_rose), "One-hand mode",
                "Search bars, toolbars and dialogs at the bottom", prefs.oneHand(), on -> {
                    prefs.setOneHand(on);
                    showTab(TAB_SETTINGS);
                });

        settingsSection(box, "Library", getColor(R.color.accent));
        settingsSwitch(box, R.drawable.ic_music_file, getColor(R.color.c_violet), "Use as music player",
                "Adds a Music library next to Videos in Files", prefs.musicLibrary(), on -> {
                    setMusicLibrary(on);
                    showTab(TAB_SETTINGS);
                });
        settingsRow(box, R.drawable.ic_view_list, getColor(R.color.accent), "Videos view", Prefs.FILES_VIEW_NAMES[prefs.filesView()], () -> pickView(false));
        if (prefs.musicLibrary()) {
            settingsRow(box, R.drawable.ic_view_list, getColor(R.color.c_violet), "Music view", Prefs.MUSIC_VIEW_NAMES[prefs.musicView()], () -> pickView(true));
        }
        settingsRow(box, R.drawable.ic_sort, getColor(R.color.c_blue), "Sort by",
                Prefs.SORT_NAMES[prefs.sortMode()] + (prefs.sortDescending() ? ", descending" : ", ascending"), () -> {
                    chooseSort();
                    main.postDelayed(() -> { if (tab == TAB_SETTINGS) showTab(TAB_SETTINGS); }, 300);
                });
        settingsSwitch(box, R.drawable.ic_movie, getColor(R.color.c_teal), "Thumbnails and album art",
                "Read a preview frame or cover from each file", prefs.thumbnails(), prefs::setThumbnails);
        settingsSwitch(box, R.drawable.ic_lock, getColor(R.color.c_indigo), "Show hidden items",
                "Hidden items appear dimmed", prefs.showHidden(), on -> {
                    prefs.setShowHidden(on);
                    filterChangedQuiet();
                });
        settingsRow(box, R.drawable.ic_lock, getColor(R.color.c_indigo), "Hidden items", prefs.hiddenPaths().size() + " hidden", this::manageHidden);
        settingsRow(box, R.drawable.ic_movie, getColor(R.color.c_orange), "Video file types", typesSummary(false), () -> chooseFileTypes(false));
        settingsRow(box, R.drawable.ic_music_file, getColor(R.color.c_pink), "Music file types", typesSummary(true), () -> chooseFileTypes(true));
        Set<String> exts = prefs.excludedExtensions();
        settingsRow(box, R.drawable.ic_close, getColor(R.color.c_rose), "Excluded extensions", exts.isEmpty() ? "None" : TextUtils.join(", ", exts), this::editExcludedExtensions);

        settingsSection(box, "Playback", getColor(R.color.c_teal));
        settingsSwitch(box, R.drawable.ic_audio, getColor(R.color.c_teal), "Play in background",
                "Keep playing sound when you leave the player", prefs.backgroundPlay(), prefs::setBackgroundPlay);
        settingsSwitch(box, R.drawable.ic_play, getColor(R.color.c_green), "Resume where I left off",
                "Remember the position of every file", prefs.resumeEnabled(), prefs::setResumeEnabled);
        settingsSwitch(box, R.drawable.ic_speed, getColor(R.color.c_blue), "Hardware decoding",
                "Turn off if a video shows green or broken frames", prefs.hwDecoding(), prefs::setHwDecoding);
        settingsRow(box, R.drawable.ic_search, getColor(R.color.c_rose), "YouTube quality (max)", prefs.ytQuality() + "p", () -> {
            int cur = 0;
            for (int k = 0; k < Prefs.YT_QUALITY_VALUES.length; k++) if (Prefs.YT_QUALITY_VALUES[k] == prefs.ytQuality()) cur = k;
            Ui.dialog(this).setTitle("YouTube quality (max)").setSingleChoiceItems(Prefs.YT_QUALITY_NAMES, cur, (d, w) -> {
                prefs.setYtQuality(Prefs.YT_QUALITY_VALUES[w]);
                d.dismiss();
                showTab(TAB_SETTINGS);
            }).show();
        });

        settingsSection(box, "Offline cache (YouTube)", getColor(R.color.c_orange));
        cacheSettingsRow = settingsBase(box, R.drawable.ic_save, getColor(R.color.c_orange), "Cached videos", cacheSummary());
        cacheSettingsRow.setOnClickListener(v -> manageCache());
        settingsRow(box, R.drawable.ic_movie, getColor(R.color.c_orange), "Cache quality",
                prefs.cacheHeightLabel() + " · lower if a video has no such quality", () -> {
                    String[] names = new String[Prefs.YT_QUALITY_NAMES.length + 1];
                    names[0] = "Auto (same as streaming quality)";
                    System.arraycopy(Prefs.YT_QUALITY_NAMES, 0, names, 1, Prefs.YT_QUALITY_NAMES.length);
                    int cur = 0;
                    for (int k = 0; k < Prefs.YT_QUALITY_VALUES.length; k++) if (Prefs.YT_QUALITY_VALUES[k] == prefs.cacheHeight()) cur = k + 1;
                    Ui.dialog(this).setTitle("Cache quality (max)").setSingleChoiceItems(names, cur, (d, w) -> {
                        prefs.setCacheHeight(w == 0 ? 0 : Prefs.YT_QUALITY_VALUES[w - 1]);
                        d.dismiss();
                        showTab(TAB_SETTINGS);
                    }).show();
                });
        settingsRow(box, R.drawable.ic_save, getColor(R.color.c_green), "Auto-cache",
                Prefs.AUTO_CACHE_NAMES[prefs.autoCache()], () ->
                        Ui.dialog(this).setTitle("Auto-cache YouTube videos").setSingleChoiceItems(Prefs.AUTO_CACHE_NAMES, prefs.autoCache(), (d, w) -> {
                            d.dismiss();
                            Runnable apply = () -> {
                                prefs.setAutoCache(w);
                                showTab(TAB_SETTINGS);
                            };
                            if (w == Prefs.AUTO_CACHE_OFF) apply.run();
                            else withCacheNotice(apply);
                        }).show());
        settingsSwitch(box, R.drawable.ic_link, getColor(R.color.c_teal), "Cache on Wi-Fi only",
                "Wait for an unmetered network before downloading", prefs.cacheWifiOnly(), prefs::setCacheWifiOnly);
        settingsRow(box, R.drawable.ic_close, getColor(R.color.c_rose), "Stop caching",
                "Pause the queue; partial downloads resume next time", () -> {
                    CacheService.cancelAll(this);
                    Ui.toast(this, "Caching stopped");
                    onCacheChanged();
                });

        settingsSection(box, "About", getColor(R.color.c_violet));
        String v = "";
        try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        settingsRow(box, R.drawable.ic_more, getColor(R.color.c_violet), "About Astra Player", "Version " + v + " · by Md Hafijur Rahman", this::showAbout);
        return sv;
    }

    private String typesSummary(boolean audio) {
        List<String> all = audio ? MediaFiles.audioExtensions() : MediaFiles.videoExtensions();
        Set<String> ex = prefs.excludedExtensions();
        int off = 0;
        for (String e : all) if (ex.contains(e)) off++;
        return off == 0 ? "All " + all.size() + " types" : (all.size() - off) + " of " + all.size() + " types";
    }

    private void pickView(boolean music) {
        String[] names = music ? Prefs.MUSIC_VIEW_NAMES : Prefs.FILES_VIEW_NAMES;
        int cur = music ? prefs.musicView() : prefs.filesView();
        Ui.dialog(this).setTitle(music ? "Music view" : "Videos view").setSingleChoiceItems(names, cur, (d, w) -> {
            d.dismiss();
            if (music) prefs.setMusicView(w);
            else prefs.setFilesView(w);
            folder = null;
            filesQuery = "";
            prefs.setLastFolder(null);
            showTab(TAB_SETTINGS);
        }).show();
    }

    /** Like filterChanged, but stays on the current tab. */
    private void filterChangedQuiet() {
        int keep = tab;
        filterChanged();
        if (keep != TAB_FILES) showTab(keep);
    }

    private void settingsSection(LinearLayout box, String title, int color) {
        TextView t = Ui.text(this, title.toUpperCase(java.util.Locale.ROOT), 12, color);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(6), dp(18), 0, dp(6));
        box.addView(t);
    }

    private Ui.Row settingsBase(LinearLayout box, int icon, int color, String title, String sub) {
        Ui.Row row = new Ui.Row(this);
        row.setIcon(icon, color);
        row.title.setText(title);
        row.sub.setSingleLine(false);
        row.sub.setMaxLines(3);
        row.sub.setText(sub);
        row.trailing.setText("");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        box.addView(row, lp);
        return row;
    }

    private void settingsRow(LinearLayout box, int icon, int color, String title, String value, Runnable action) {
        Ui.Row row = settingsBase(box, icon, color, title, value);
        row.setOnClickListener(v -> action.run());
    }

    private void settingsSwitch(LinearLayout box, int icon, int color, String title, String sub, boolean value,
                                java.util.function.Consumer<Boolean> onChange) {
        Ui.Row row = settingsBase(box, icon, color, title, sub);
        android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(value);
        sw.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{color, getColor(R.color.surface_high)}));
        sw.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{Ui.soft(color), getColor(R.color.divider)}));
        sw.setOnCheckedChangeListener((b, on) -> onChange.accept(on));
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());
    }

    private void chooseTheme() {
        Ui.dialog(this).setTitle("Theme").setSingleChoiceItems(Prefs.THEME_NAMES, prefs.theme(), (d, w) -> {
            d.dismiss();
            if (w == prefs.theme()) return;
            prefs.setTheme(w);
            recreate();
        }).show();
    }

    private void chooseFilesView() {
        CharSequence[] items = new CharSequence[Prefs.FILES_VIEW_NAMES.length];
        for (int i = 0; i < items.length; i++) {
            String[] names = musicKind() ? Prefs.MUSIC_VIEW_NAMES : Prefs.FILES_VIEW_NAMES;
            String[] hints = musicKind() ? Prefs.MUSIC_VIEW_HINTS : Prefs.FILES_VIEW_HINTS;
            android.text.SpannableString sp = new android.text.SpannableString(names[i] + "\n" + hints[i]);
            int start = names[i].length() + 1;
            sp.setSpan(new android.text.style.RelativeSizeSpan(0.8f), start, sp.length(), 0);
            sp.setSpan(new android.text.style.ForegroundColorSpan(getColor(R.color.text_dim)), start, sp.length(), 0);
            items[i] = sp;
        }
        Ui.dialog(this).setTitle(musicKind() ? "Music view" : "Files view").setSingleChoiceItems(items, prefs.currentView(), (d, w) -> {
            d.dismiss();
            setFilesView(w);
        }).show();
    }

    private void setFilesView(int view) {
        prefs.setCurrentView(view);
        filesQuery = "";
        folder = null;
        prefs.setLastFolder(null);
        showTab(TAB_FILES);
        if (view != Prefs.FILES_BROWSE && lastScan[kind()] == 0) rescan();
    }

    // ---- playback helpers -----------------------------------------------------------------

    private void play(List<MediaItem> items, int start) {
        if (items.isEmpty()) return;
        PlaybackService.start(this);
        if (service != null) {
            service.expectView();
            service.playQueue(items, start);
            PlayerActivity.open(this);
        } else {
            main.postDelayed(() -> play(items, start), 150);
        }
    }

    private void enqueue(List<MediaItem> items) {
        if (items.isEmpty()) return;
        PlaybackService.start(this);
        if (service == null) {
            main.postDelayed(() -> enqueue(items), 150);
            return;
        }
        boolean was = service.current() != null;
        if (!was) service.expectView();
        service.enqueue(items);
        Ui.toast(this, items.size() == 1 ? "Added to queue" : items.size() + " items added to queue");
        if (!was) PlayerActivity.open(this);
    }

    private void addToPlaylist(List<MediaItem> items) {
        Ui.choosePlaylist(this, playlists, name -> {
            playlists.append(name, items);
            CacheService.onAddedToPlaylist(this, items);
            Ui.toast(this, "Added to \"" + name + "\"");
            if (tab == TAB_PLAYLISTS) showTab(TAB_PLAYLISTS);
        });
    }

    /** Long-press menu shared by every list of media items. */
    private void itemMenu(View anchor, List<MediaItem> items, int pos) {
        PopupMenu m = new PopupMenu(this, anchor, Gravity.END);
        m.getMenu().add(0, 1, 0, "Play");
        m.getMenu().add(0, 2, 0, "Play next in queue");
        m.getMenu().add(0, 3, 0, "Add to queue");
        m.getMenu().add(0, 4, 0, "Add to playlist…");
        addCacheMenuItems(m, items.get(pos));
        m.setOnMenuItemClickListener(i -> {
            MediaItem it = items.get(pos);
            if (handleCacheMenuItem(i.getItemId(), it)) return true;
            switch (i.getItemId()) {
                case 1: play(Collections.singletonList(it), 0); break;
                case 2:
                    if (service != null && service.current() != null) {
                        service.playNext(Collections.singletonList(it));
                        Ui.toast(this, "Plays next");
                    } else {
                        enqueue(Collections.singletonList(it));
                    }
                    break;
                case 3: enqueue(Collections.singletonList(it)); break;
                case 4: addToPlaylist(Collections.singletonList(it)); break;
                default: break;
            }
            return true;
        });
        m.show();
    }

    // ---- now playing ----------------------------------------------------------------------

    private void refreshNowBar() {
        if (service == null || service.current() == null) {
            nowBar.setVisibility(View.GONE);
            return;
        }
        nowBar.setVisibility(View.VISIBLE);
        nowTitle.setText(service.currentTitle());
        nowSub.setText(service.isResolving() ? "Loading…" : service.isPlaying() ? "Playing" : "Paused");
        nowPlay.setImageResource(service.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    @Override public void onItemChanged(MediaItem item) { refreshNowBar(); }
    @Override public void onStateChanged() { refreshNowBar(); }

    // ---- Files tab ------------------------------------------------------------------------

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_STORAGE && tab == TAB_FILES) showTab(TAB_FILES);
    }

    private void openDocuments() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "audio/*", "application/octet-stream", "application/x-mpegurl"})
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_OPEN);
        } catch (Exception e) {
            Ui.toast(this, "No file picker available");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_OPEN || resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        List<MediaItem> items = new ArrayList<>();
        for (Uri u : uris) {
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            String name = null;
            try (android.database.Cursor c = getContentResolver().query(u, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) name = c.getString(0);
            } catch (Exception ignored) {
            }
            items.add(new MediaItem(u.toString(), name, MediaItem.LOCAL, "Picked file", null, 0));
        }
        play(items, 0);
    }

    /** Top-level storage volumes (internal storage and SD cards / USB drives). */
    private List<File> storageRoots() {
        List<File> roots = new ArrayList<>();
        roots.add(Environment.getExternalStorageDirectory());
        File[] dirs = getExternalFilesDirs(null);
        for (File d : dirs) {
            if (d == null) continue;
            String p = d.getAbsolutePath();
            int i = p.indexOf("/Android/data/");
            if (i > 0) {
                File r = new File(p.substring(0, i));
                if (!roots.contains(r) && r.canRead()) roots.add(r);
            }
        }
        return roots;
    }

    private View buildFiles() {
        filesAdapter = null;
        filesStatus = null;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        if (!hasStorageAccess()) {
            box.setPadding(dp(18), dp(18), dp(18), dp(18));
            TextView t = Ui.text(this, "Browse your folders", 20, getColor(R.color.text));
            TextView m = Ui.text(this, "To show folders, play whole folders and pick up subtitle files next to your videos, Astra Player needs access to all files. Nothing leaves your device.", 14, getColor(R.color.text_dim));
            m.setPadding(0, dp(8), 0, dp(16));
            TextView allow = Ui.button(this, "Allow file access", true);
            allow.setOnClickListener(v -> requestStorage());
            TextView pick = Ui.button(this, "Just pick files instead", false);
            pick.setOnClickListener(v -> openDocuments());
            box.addView(t);
            box.addView(m);
            box.addView(allow);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            box.addView(pick, lp);
            return box;
        }
        if (prefs.musicLibrary()) addKindChips(box);
        int view = prefs.currentView();
        if (view == Prefs.FILES_BROWSE) return buildBrowse(box);
        if (folder != null) return buildVideoFolder(box, folder);
        return view == Prefs.FILES_ALL ? buildAllVideos(box) : buildVideoFolders(box);
    }

    // ---- Files tab: toolbar, search, sort, settings ------------------------------------

    /** Videos | Music switch, shown when the Music library is on. */
    private void addKindChips(LinearLayout box) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(14), 0, dp(14), dp(8));
        String[] labels = {"🎬  Videos", "🎵  Music"};
        int[] colors = {getColor(R.color.amber), getColor(R.color.c_violet)};
        for (int k = 0; k < 2; k++) {
            TextView chip = Ui.text(this, labels[k], 14, 0);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(18), dp(8), dp(18), dp(8));
            chip.setBackgroundResource(R.drawable.bg_chip);
            boolean on = kind() == k;
            chip.setBackgroundTintList(ColorStateList.valueOf(on ? colors[k] : Ui.soft(colors[k])));
            chip.setTextColor(on ? (k == 0 ? 0xFF101013 : 0xFFFFFFFF) : getColor(R.color.text));
            if (on) chip.setTypeface(Typeface.DEFAULT_BOLD);
            final int kk = k;
            chip.setOnClickListener(v -> setKind(kk));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            row.addView(chip, lp);
        }
        box.addView(row);
    }

    /**
     * Search field plus sort, view, settings and refresh buttons. {@code onQuery} re-filters
     * the current list in place, so typing never rebuilds the tab or loses focus.
     */
    private void addFilesToolbar(LinearLayout box, boolean withRefresh, java.util.function.Consumer<String> onQuery) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), 0, dp(6), 0);
        EditText search = Ui.input(this, "Search", filesQuery);
        search.setTextSize(14);
        search.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        search.setCompoundDrawablePadding(dp(8));
        search.setCompoundDrawableTintList(ColorStateList.valueOf(getColor(R.color.text_dim)));
        search.setPadding(dp(12), dp(8), dp(12), dp(8));
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                filesQuery = s.toString().trim();
                onQuery.accept(filesQuery);
            }
        });
        search.setOnEditorActionListener((v, a, e) -> {
            hideKeyboard(v);
            return true;
        });
        bar.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.setTag(BOTTOMABLE);
        ImageButton sort = iconButton(R.drawable.ic_sort, "Sort");
        sort.setOnClickListener(v -> chooseSort());
        bar.addView(sort);
        bar.addView(viewSwitchButton());
        ImageButton settings = iconButton(R.drawable.ic_tune, "Files settings");
        settings.setOnClickListener(v -> filesSettings());
        bar.addView(settings);
        if (withRefresh) {
            ImageButton refresh = iconButton(R.drawable.ic_refresh, "Look for videos again");
            refresh.setOnClickListener(v -> rescan());
            bar.addView(refresh);
        }
        box.addView(bar);
    }

    private void addStatusLine(LinearLayout box) {
        filesStatus = Ui.text(this, "", 12, getColor(R.color.text_dim));
        filesStatus.setSingleLine(true);
        filesStatus.setPadding(dp(18), dp(4), dp(18), dp(6));
        if (prefs.oneHand()) filesStatus.setTag(BOTTOMABLE);
        box.addView(filesStatus);
        updateFilesStatus();
    }

    private ImageButton viewSwitchButton() {
        ImageButton b = iconButton(R.drawable.ic_view_list, "Change files view");
        b.setOnClickListener(v -> chooseFilesView());
        return b;
    }

    private void updateFilesStatus() {
        if (filesStatus == null) return;
        int k = kind();
        boolean music = k == Prefs.KIND_MUSIC;
        String text;
        if (prefs.currentView() == Prefs.FILES_ALL) {
            text = allFiles[k] == null || (scanning[k] && allFiles[k].isEmpty()) ? lookingText()
                    : count(allFiles[k].size(), music ? "song" : "video", music ? "songs" : "videos");
        } else {
            text = scanning[k] && folders[k].isEmpty() ? lookingText()
                    : count(visibleFolders().size(), music ? "folder with music" : "folder with videos", music ? "folders with music" : "folders with videos");
        }
        text += "  ·  " + Prefs.SORT_NAMES[prefs.sortMode()].replace(" / number of videos", "") + (prefs.sortDescending() ? " ↓" : " ↑");
        if (scanning[k] && !folders[k].isEmpty()) text += "  ·  updating…";
        filesStatus.setText(text);
    }

    private int kind() {
        return prefs.filesKind();
    }

    private boolean musicKind() {
        return kind() == Prefs.KIND_MUSIC;
    }

    private String lookingText() {
        return musicKind() ? "Looking for music…" : "Looking for videos…";
    }

    private String noneFoundText() {
        return (musicKind() ? "No music found on this device." : "No videos found on this device.") + "\nTap refresh to look again.";
    }

    private static String count(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    private static boolean matches(String query, String... texts) {
        if (query == null || query.isEmpty()) return true;
        String q = query.toLowerCase(java.util.Locale.ROOT);
        for (String t : texts) if (t != null && t.toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
        return false;
    }

    private void chooseSort() {
        String[] names = new String[Prefs.SORT_NAMES.length * 2];
        for (int i = 0; i < Prefs.SORT_NAMES.length; i++) {
            names[i * 2] = Prefs.SORT_NAMES[i] + "  ↑";
            names[i * 2 + 1] = Prefs.SORT_NAMES[i] + "  ↓";
        }
        int cur = prefs.sortMode() * 2 + (prefs.sortDescending() ? 1 : 0);
        Ui.dialog(this).setTitle("Sort by").setSingleChoiceItems(names, cur, (d, w) -> {
            d.dismiss();
            prefs.setSort(w / 2, w % 2 == 1);
            showTab(TAB_FILES);
        }).show();
    }

    private void filesSettings() {
        Set<String> hidden = prefs.hiddenPaths();
        Set<String> exts = prefs.excludedExtensions();
        String[] viewNames = musicKind() ? Prefs.MUSIC_VIEW_NAMES : Prefs.FILES_VIEW_NAMES;
        String[] items = {
                "Sort by: " + Prefs.SORT_NAMES[prefs.sortMode()] + (prefs.sortDescending() ? " (descending)" : ""),
                "View: " + viewNames[prefs.currentView()],
                (prefs.musicLibrary() ? "☑" : "☐") + "  Use as music player (Music library)",
                (prefs.showHidden() ? "☑" : "☐") + "  Show hidden items",
                (prefs.thumbnails() ? "☑" : "☐") + "  Thumbnails and album art",
                "Hidden items (" + hidden.size() + ")…",
                "Video file types…",
                "Music file types…",
                "Other excluded extensions: " + (exts.isEmpty() ? "none" : TextUtils.join(", ", exts)) + "…"};
        Ui.dialog(this).setTitle("Files settings").setItems(items, (d, w) -> {
            switch (w) {
                case 0: chooseSort(); break;
                case 1: chooseFilesView(); break;
                case 2: setMusicLibrary(!prefs.musicLibrary()); break;
                case 3:
                    prefs.setShowHidden(!prefs.showHidden());
                    filterChanged();
                    break;
                case 4:
                    prefs.setThumbnails(!prefs.thumbnails());
                    showTab(TAB_FILES);
                    break;
                case 5: manageHidden(); break;
                case 6: chooseFileTypes(false); break;
                case 7: chooseFileTypes(true); break;
                default: editExcludedExtensions(); break;
            }
        }).show();
    }

    private void setMusicLibrary(boolean on) {
        prefs.setMusicLibrary(on);
        prefs.setFilesKind(on ? Prefs.KIND_MUSIC : Prefs.KIND_VIDEO);
        folder = null;
        filesQuery = "";
        prefs.setLastFolder(null);
        Ui.toast(this, on ? "Music library on: switch between Videos and Music at the top of Files" : "Music library off");
        showTab(TAB_FILES);
        if (on && prefs.currentView() != Prefs.FILES_BROWSE) rescan();
    }

    private void setKind(int k) {
        if (k == kind()) return;
        prefs.setFilesKind(k);
        folder = null;
        filesQuery = "";
        prefs.setLastFolder(null);
        showTab(TAB_FILES);
        if (prefs.currentView() != Prefs.FILES_BROWSE && SystemClock.elapsedRealtime() - lastScan[k] > SCAN_INTERVAL_MS) rescan();
    }

    /** Checkbox list of video or audio extensions; unticked ones join the excluded set. */
    private void chooseFileTypes(boolean audio) {
        List<String> all = audio ? MediaFiles.audioExtensions() : MediaFiles.videoExtensions();
        Set<String> excluded = prefs.excludedExtensions();
        String[] labels = new String[all.size()];
        boolean[] on = new boolean[all.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = "." + all.get(i);
            on[i] = !excluded.contains(all.get(i));
        }
        Ui.dialog(this).setTitle(audio ? "Music file types to show" : "Video file types to show")
                .setMultiChoiceItems(labels, on, (d, w, checked) -> on[w] = checked)
                .setPositiveButton("Save", (d, w) -> {
                    Set<String> out = prefs.excludedExtensions();
                    for (int i = 0; i < labels.length; i++) {
                        if (on[i]) out.remove(all.get(i));
                        else out.add(all.get(i));
                    }
                    prefs.setExcludedExtensions(out);
                    filterChanged();
                })
                .setNeutralButton("Show all", (d, w) -> {
                    Set<String> out = prefs.excludedExtensions();
                    out.removeAll(all);
                    prefs.setExcludedExtensions(out);
                    filterChanged();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void manageHidden() {
        List<String> paths = new ArrayList<>(prefs.hiddenPaths());
        if (paths.isEmpty()) {
            Ui.dialog(this).setTitle("Hidden items")
                    .setMessage("Nothing is hidden. Use ⋮ or long-press on a file or folder and choose \"Hide\".")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] labels = new String[paths.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = prettyPath(new File(paths.get(i)));
        boolean[] checked = new boolean[labels.length];
        Ui.dialog(this).setTitle("Tick items to show again")
                .setMultiChoiceItems(labels, checked, (d, w, on) -> checked[w] = on)
                .setPositiveButton("Unhide", (d, w) -> {
                    Set<String> keep = new java.util.TreeSet<>();
                    for (int i = 0; i < labels.length; i++) if (!checked[i]) keep.add(paths.get(i));
                    prefs.setHiddenPaths(keep);
                    filterChanged();
                })
                .setNeutralButton("Unhide all", (d, w) -> {
                    prefs.setHiddenPaths(new java.util.TreeSet<>());
                    filterChanged();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void editExcludedExtensions() {
        Set<String> cur = prefs.excludedExtensions();
        EditText e = Ui.input(this, "e.g. 3gp, ts, wmv", TextUtils.join(", ", cur));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(Ui.text(this, "Files with these extensions are left out of every Files view. Separate them with commas or spaces.", 13, getColor(R.color.text_dim)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        box.addView(e, lp);
        Ui.dialog(this).setTitle("Excluded extensions").setView(Ui.padded(this, box))
                .setPositiveButton("Save", (d, w) -> {
                    Set<String> out = new java.util.TreeSet<>();
                    for (String part : e.getText().toString().split("[,;\\s]+")) {
                        String x = part.trim().toLowerCase(java.util.Locale.ROOT);
                        while (x.startsWith(".") || x.startsWith("*")) x = x.substring(1);
                        if (!x.isEmpty() && x.matches("[a-z0-9]{1,10}")) out.add(x);
                    }
                    prefs.setExcludedExtensions(out);
                    filterChanged();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Hidden/excluded settings changed: drop both cached scans and look again. */
    private void filterChanged() {
        for (int k = 0; k < 2; k++) {
            allFiles[k] = null;
            allGen[k]++;
            foldersJson[k] = null;
            lastScan[k] = 0;
            prefs.setFolderCache(k, null);
        }
        if (folder != null && prefs.filter().insideHidden(folder)) {
            folder = null;
            prefs.setLastFolder(null);
        }
        showTab(TAB_FILES);
        if (prefs.currentView() != Prefs.FILES_BROWSE) rescan();
    }

    /** Scans storage for video (or music) folders on the scanner thread; the result replaces the cache. */
    private void rescan() {
        final int k = kind();
        if (scanning[k] || !hasStorageAccess()) return;
        scanning[k] = true;
        lastScan[k] = SystemClock.elapsedRealtime();
        updateFilesStatus();
        List<File> roots = storageRoots();
        MediaFiles.Filter filter = prefs.filter();
        scanner.execute(() -> {
            List<MediaFiles.VideoFolder> found = MediaFiles.findMediaFolders(roots, filter, k == Prefs.KIND_MUSIC);
            String json = MediaFiles.toJson(found);
            main.post(() -> {
                if (destroyed) return;
                scanning[k] = false;
                boolean changed = !json.equals(foldersJson[k]);
                if (changed) {
                    folders[k] = found;
                    foldersJson[k] = json;
                    prefs.setFolderCache(k, json);
                    allFiles[k] = null;
                    allGen[k]++;
                }
                // Rebuild only on a real change so the scroll position is kept.
                boolean showing = tab == TAB_FILES && kind() == k && prefs.currentView() != Prefs.FILES_BROWSE && folder == null;
                if (showing && changed) showTab(TAB_FILES);
                else updateFilesStatus();
            });
        });
    }

    private boolean isInternalRoot(File f) {
        return f.equals(Environment.getExternalStorageDirectory());
    }

    /** Cached folders of the current library minus anything hidden since the last scan. */
    private List<MediaFiles.VideoFolder> visibleFolders() {
        MediaFiles.Filter f = prefs.filter();
        List<MediaFiles.VideoFolder> out = new ArrayList<>();
        for (MediaFiles.VideoFolder vf : folders[kind()]) if (!f.insideHidden(vf.dir)) out.add(vf);
        return out;
    }

    private void dimIfHidden(Ui.Row row, File f) {
        row.setAlpha(prefs.showHidden() && prefs.filter().isHidden(f) ? 0.5f : 1f);
    }

    // ---- Files tab: folder list ---------------------------------------------------------

    private View buildVideoFolders(LinearLayout box) {
        final boolean music = musicKind();
        final List<MediaFiles.VideoFolder> all = visibleFolders();
        MediaFiles.sortFolders(all, prefs.sortMode(), prefs.sortDescending());
        final List<MediaFiles.VideoFolder> rows = new ArrayList<>();
        ListView list = listView();
        RowAdapter<MediaFiles.VideoFolder> adapter = new RowAdapter<>(rows, (row, vf) -> {
            row.setIcon(music ? R.drawable.ic_playlist : R.drawable.ic_folder, Ui.paletteColor(this, vf.dir.getAbsolutePath()));
            row.title.setText(isInternalRoot(vf.dir) ? "Internal storage" : vf.dir.getName());
            row.sub.setSingleLine(false);
            row.sub.setMaxLines(3);
            row.sub.setText(vf.dir.getAbsolutePath());
            row.trailing.setText(count(vf.videos, music ? "song" : "video", music ? "songs" : "videos"));
            row.setMore(v -> folderMenu(v, vf.dir));
            dimIfHidden(row, vf.dir);
        });
        java.util.function.Consumer<String> filter = q -> {
            rows.clear();
            for (MediaFiles.VideoFolder vf : all) if (matches(q, vf.dir.getName(), vf.dir.getAbsolutePath())) rows.add(vf);
            adapter.notifyDataSetChanged();
        };
        filter.accept(filesQuery);
        addFilesToolbar(box, true, filter);
        addStatusLine(box);
        list.setAdapter(adapter);
        filesAdapter = adapter;
        list.setOnItemClickListener((p, v, pos, id) -> openVideoFolder(rows.get(pos).dir));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            folderMenu(v, rows.get(pos).dir);
            return true;
        });
        if (all.isEmpty()) box.addView(emptyText(scanning[kind()] ? lookingText() : noneFoundText()));
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return box;
    }

    private void openVideoFolder(File dir) {
        folder = dir;
        filesQuery = "";
        prefs.setLastFolder(dir.getAbsolutePath());
        showTab(TAB_FILES);
    }

    private void closeVideoFolder() {
        folder = null;
        filesQuery = "";
        prefs.setLastFolder(null);
        showTab(TAB_FILES);
    }

    /** Media of the current library directly inside {@code dir}, sorted as chosen. */
    private List<File> folderMedia(File dir) {
        List<File> files = MediaFiles.mediaFiles(dir, prefs.filter(), musicKind());
        MediaFiles.sortFiles(files, prefs.sortMode(), prefs.sortDescending());
        return files;
    }

    /** One folder: only its videos (or songs), with the name, full path and Play folder. */
    private View buildVideoFolder(LinearLayout box, File dir) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(12), dp(6));
        ImageButton back = iconButton(R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> closeVideoFolder());
        bar.addView(back);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.text(this, isInternalRoot(dir) ? "Internal storage" : dir.getName(), 17, Ui.paletteColor(this, dir.getAbsolutePath()));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        TextView path = Ui.text(this, dir.getAbsolutePath(), 12, getColor(R.color.text_dim));
        path.setMaxLines(2);
        path.setEllipsize(TextUtils.TruncateAt.START);
        names.addView(name);
        names.addView(path);
        bar.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final List<File> media = folderMedia(dir);
        if (!media.isEmpty()) {
            TextView playAll = Ui.button(this, "▶ Play folder", true);
            playAll.setOnClickListener(v -> play(toItems(media), 0));
            playAll.setOnLongClickListener(v -> {
                folderMenu(v, dir);
                return true;
            });
            bar.addView(playAll);
        }
        box.addView(bar);

        final List<File> rows = new ArrayList<>();
        RowAdapter<File> adapter = mediaAdapter(rows, false);
        java.util.function.Consumer<String> filter = q -> {
            rows.clear();
            for (File f : media) if (matches(q, f.getName())) rows.add(f);
            adapter.notifyDataSetChanged();
        };
        filter.accept(filesQuery);
        addFilesToolbar(box, false, filter);
        ListView list = listView();
        list.setAdapter(adapter);
        filesAdapter = adapter;
        list.setOnItemClickListener((p, v, pos, id) -> playFrom(rows.get(pos), media));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            fileMenu(v, rows.get(pos));
            return true;
        });
        if (media.isEmpty()) box.addView(emptyText(musicKind() ? "No music in this folder any more" : "No videos in this folder any more"));
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return box;
    }

    /** Rows for local media: thumbnail or album art, name, size/date/resume (and folder), duration, ⋮. */
    private RowAdapter<File> mediaAdapter(List<File> rows, boolean withFolder) {
        boolean thumbs = prefs.thumbnails();
        return new RowAdapter<>(rows, (row, f) -> {
            if (MediaFiles.isAudio(f.getName())) row.setSong(f, thumbs);
            else row.setVideo(f, thumbs);
            row.title.setText(f.getName());
            File parent = f.getParentFile();
            if (withFolder) {
                row.sub.setSingleLine(false);
                row.sub.setMaxLines(3);
                row.sub.setText(fileInfo(f) + (parent == null ? "" : "\n" + parent.getAbsolutePath()));
            } else {
                row.sub.setText(fileInfo(f));
            }
            row.setMore(v -> fileMenu(v, f));
            dimIfHidden(row, f);
        });
    }

    private static List<MediaItem> toItems(List<File> files) {
        List<MediaItem> out = new ArrayList<>();
        for (File f : files) out.add(MediaItem.file(f));
        return out;
    }

    private String fileInfo(File f) {
        long pos = prefs.resumePosition(f.getAbsolutePath());
        String date = android.text.format.DateUtils.getRelativeTimeSpanString(f.lastModified(), System.currentTimeMillis(),
                android.text.format.DateUtils.DAY_IN_MILLIS, android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE).toString();
        return size(f.length()) + "  ·  " + date + (pos > 0 ? "  ·  ▶ " + MediaFiles.formatTime(pos) : "");
    }

    /** Queues the file's folder (same library), starting at that file, like PotPlayer. */
    private void playFrom(File file) {
        File dir = file.getParentFile();
        if (dir == null) {
            play(Collections.singletonList(MediaItem.file(file)), 0);
            return;
        }
        List<File> files;
        if (prefs.currentView() == Prefs.FILES_BROWSE) {
            files = new ArrayList<>();
            for (File f : MediaFiles.list(dir, prefs.filter(), prefs.sortMode(), prefs.sortDescending())) if (f.isFile()) files.add(f);
        } else {
            files = MediaFiles.mediaFiles(dir, prefs.filter(), MediaFiles.isAudio(file.getName()));
            MediaFiles.sortFiles(files, prefs.sortMode(), prefs.sortDescending());
        }
        playFrom(file, files);
    }

    /** Plays {@code files} in the shown order, starting at {@code file}. */
    private void playFrom(File file, List<File> files) {
        List<MediaItem> items = toItems(files);
        int start = 0;
        for (int i = 0; i < items.size(); i++) if (items.get(i).uri.equals(file.getAbsolutePath())) start = i;
        if (items.isEmpty()) items = Collections.singletonList(MediaItem.file(file));
        play(items, start);
    }

    // ---- Files tab: all videos / all songs ----------------------------------------------

    private View buildAllVideos(LinearLayout box) {
        final int k = kind();
        final List<File> rows = new ArrayList<>();
        RowAdapter<File> adapter = mediaAdapter(rows, true);
        final List<File> all = new ArrayList<>();
        if (allFiles[k] != null) {
            MediaFiles.Filter f = prefs.filter();
            for (File v : allFiles[k]) if (f.allowFile(v) && !f.insideHidden(v)) all.add(v);
            MediaFiles.sortFiles(all, prefs.sortMode(), prefs.sortDescending());
        }
        java.util.function.Consumer<String> filter = q -> {
            rows.clear();
            for (File v : all) if (matches(q, v.getName(), v.getParent())) rows.add(v);
            adapter.notifyDataSetChanged();
        };
        filter.accept(filesQuery);
        addFilesToolbar(box, true, filter);
        addStatusLine(box);
        if (allFiles[k] == null) {
            buildAllVideosList();
            box.addView(emptyText(lookingText()));
            return box;
        }
        ListView list = listView();
        list.setAdapter(adapter);
        filesAdapter = adapter;
        list.setOnItemClickListener((p, v, pos, id) -> playFrom(rows.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            fileMenu(v, rows.get(pos));
            return true;
        });
        if (all.isEmpty()) box.addView(emptyText(scanning[k] ? lookingText() : noneFoundText()));
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return box;
    }

    /** Lists every cached folder's files of the current library in the background. */
    private void buildAllVideosList() {
        final int k = kind();
        if (allBuilding[k]) return;
        allBuilding[k] = true;
        final int gen = allGen[k];
        final List<MediaFiles.VideoFolder> src = new ArrayList<>(folders[k]);
        final MediaFiles.Filter filter = prefs.filter();
        scanner.execute(() -> {
            List<File> all = new ArrayList<>();
            for (MediaFiles.VideoFolder vf : src) all.addAll(MediaFiles.mediaFiles(vf.dir, filter, k == Prefs.KIND_MUSIC));
            main.post(() -> {
                if (destroyed) return;
                allBuilding[k] = false;
                boolean showing = tab == TAB_FILES && kind() == k && prefs.currentView() == Prefs.FILES_ALL && folder == null;
                if (gen != allGen[k]) {
                    // A rescan replaced the folders meanwhile; build again from the new ones.
                    if (showing) buildAllVideosList();
                    return;
                }
                allFiles[k] = all;
                if (showing) showTab(TAB_FILES);
            });
        });
    }

    // ---- Files tab: Browse storage ------------------------------------------------------

    /** Browse storage: folders plus every playable file, from the storage roots. */
    private View buildBrowse(LinearLayout box) {
        List<File> roots = storageRoots();
        boolean atRoots = folder == null;
        final List<File> entries = atRoots ? roots : MediaFiles.list(folder, prefs.filter(), prefs.sortMode(), prefs.sortDescending());

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(12), dp(6));
        ImageButton up = iconButton(R.drawable.ic_back, "Up");
        up.setEnabled(!atRoots);
        up.setAlpha(atRoots ? 0.3f : 1f);
        up.setOnClickListener(v -> goUp());
        bar.addView(up);
        TextView path = Ui.text(this, atRoots ? "Storage" : prettyPath(folder), 13, getColor(R.color.text_dim));
        path.setSingleLine(true);
        path.setEllipsize(TextUtils.TruncateAt.START);
        bar.addView(path, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (atRoots) {
            bar.addView(viewSwitchButton());
        } else {
            List<MediaItem> here = MediaFiles.folderItems(folder, prefs.filter());
            if (!here.isEmpty()) {
                TextView playAll = Ui.button(this, "▶ Play folder", true);
                playAll.setOnClickListener(v -> play(here, 0));
                playAll.setOnLongClickListener(v -> {
                    folderMenu(v, folder);
                    return true;
                });
                bar.addView(playAll);
            }
        }
        box.addView(bar);

        final List<File> rows = new ArrayList<>();
        boolean thumbs = prefs.thumbnails();
        RowAdapter<File> adapter = new RowAdapter<>(rows, (row, f) -> {
            if (atRoots) {
                row.setIcon(R.drawable.ic_folder, getColor(R.color.accent));
                row.title.setText(isInternalRoot(f) ? "Internal storage" : f.getName());
                row.sub.setText(f.getAbsolutePath());
                row.trailing.setText("");
                row.setMore(null);
            } else if (f.isDirectory()) {
                row.setIcon(R.drawable.ic_folder, Ui.paletteColor(this, f.getAbsolutePath()));
                row.title.setText(f.getName());
                String[] kids = f.list();
                row.sub.setText(kids == null ? "" : kids.length + " items");
                row.trailing.setText("");
                row.setMore(v -> folderMenu(v, f));
            } else {
                boolean audio = MediaFiles.isAudio(f.getName());
                if (audio) {
                    row.setIcon(R.drawable.ic_music_file, Ui.paletteColor(this, f.getParent()));
                    row.trailing.setText("");
                } else {
                    row.setVideo(f, thumbs);
                }
                row.title.setText(f.getName());
                row.sub.setText(fileInfo(f));
                row.setMore(v -> fileMenu(v, f));
            }
            dimIfHidden(row, f);
        });
        java.util.function.Consumer<String> filter = q -> {
            rows.clear();
            for (File f : entries) if (matches(q, f.getName())) rows.add(f);
            adapter.notifyDataSetChanged();
        };
        filter.accept(filesQuery);
        if (!atRoots) addFilesToolbar(box, false, filter);
        ListView list = listView();
        list.setAdapter(adapter);
        filesAdapter = adapter;
        list.setOnItemClickListener((p, v, pos, id) -> {
            File f = rows.get(pos);
            if (atRoots || f.isDirectory()) {
                folder = f;
                filesQuery = "";
                prefs.setLastFolder(f.getAbsolutePath());
                showTab(TAB_FILES);
            } else {
                playFrom(f);
            }
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            File f = rows.get(pos);
            if (atRoots) return false;
            if (f.isDirectory()) folderMenu(v, f);
            else fileMenu(v, f);
            return true;
        });
        if (entries.isEmpty()) {
            box.addView(emptyText("No playable files in this folder"));
        }
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return box;
    }

    // ---- Files tab: menus ---------------------------------------------------------------

    /** Re-reads the Files tab after a change on disk (delete, rename, hide). */
    private void filesChangedOnDisk() {
        for (int k = 0; k < 2; k++) {
            allFiles[k] = null;
            allGen[k]++;
            lastScan[k] = 0;
        }
        if (folder != null && !folder.isDirectory()) {
            folder = null;
            prefs.setLastFolder(null);
        }
        showTab(TAB_FILES);
        if (prefs.currentView() != Prefs.FILES_BROWSE) rescan();
    }

    private void fileMenu(View anchor, File f) {
        boolean hidden = prefs.filter().isHidden(f);
        PopupMenu m = new PopupMenu(this, anchor, Gravity.END);
        m.getMenu().add(0, 1, 0, "Play");
        m.getMenu().add(0, 2, 0, "Play next in queue");
        m.getMenu().add(0, 3, 0, "Add to queue");
        m.getMenu().add(0, 4, 0, "Add to playlist…");
        m.getMenu().add(0, 5, 0, "Rename…");
        m.getMenu().add(0, 6, 0, "Share");
        m.getMenu().add(0, 7, 0, "Details");
        m.getMenu().add(0, 8, 0, hidden ? "Unhide" : "Hide from Astra");
        m.getMenu().add(0, 9, 0, "Delete from device…");
        m.setOnMenuItemClickListener(i -> {
            MediaItem it = MediaItem.file(f);
            switch (i.getItemId()) {
                case 1: playFrom(f); break;
                case 2:
                    if (service != null && service.current() != null) {
                        service.playNext(Collections.singletonList(it));
                        Ui.toast(this, "Plays next");
                    } else {
                        enqueue(Collections.singletonList(it));
                    }
                    break;
                case 3: enqueue(Collections.singletonList(it)); break;
                case 4: addToPlaylist(Collections.singletonList(it)); break;
                case 5: FileActions.rename(this, f, this::filesChangedOnDisk); break;
                case 6: FileActions.share(this, f); break;
                case 7: FileActions.details(this, f); break;
                case 8:
                    FileActions.setHidden(prefs, f, !hidden);
                    Ui.toast(this, hidden ? "Shown again" : "Hidden. Undo in Files settings › Hidden items.");
                    filterChanged();
                    break;
                default:
                    FileActions.confirmDelete(this, Collections.singletonList(f), f.getName(), service, this::filesChangedOnDisk);
                    break;
            }
            return true;
        });
        m.show();
    }

    private void folderMenu(View anchor, File dir) {
        boolean hidden = prefs.filter().isHidden(dir);
        PopupMenu m = new PopupMenu(this, anchor, Gravity.END);
        m.getMenu().add(0, 1, 0, "Play folder");
        m.getMenu().add(0, 2, 0, "Shuffle folder");
        m.getMenu().add(0, 3, 0, "Add folder to queue");
        m.getMenu().add(0, 4, 0, "Save folder as playlist…");
        m.getMenu().add(0, 5, 0, hidden ? "Unhide folder" : "Hide folder from Astra");
        m.getMenu().add(0, 6, 0, musicKind() ? "Delete music in folder…" : "Delete videos in folder…");
        m.setOnMenuItemClickListener(i -> {
            if (i.getItemId() == 5) {
                FileActions.setHidden(prefs, dir, !hidden);
                Ui.toast(this, hidden ? "Folder shown again" : "Folder hidden. Undo in Files settings › Hidden items.");
                if (!hidden && dir.equals(folder)) {
                    folder = null;
                    prefs.setLastFolder(null);
                }
                filterChanged();
                return true;
            }
            if (i.getItemId() == 6) {
                List<File> media = MediaFiles.mediaFiles(dir, MediaFiles.Filter.NONE, musicKind());
                if (media.isEmpty()) Ui.toast(this, (musicKind() ? "No music directly in " : "No videos directly in ") + dir.getName());
                else FileActions.confirmDelete(this, media, "\"" + dir.getName() + "\"", service, this::filesChangedOnDisk);
                return true;
            }
            List<MediaItem> items = prefs.currentView() == Prefs.FILES_BROWSE
                    ? MediaFiles.folderItems(dir, prefs.filter()) : toItems(folderMedia(dir));
            if (items.isEmpty()) {
                Ui.toast(this, "No playable files in " + dir.getName());
                return true;
            }
            switch (i.getItemId()) {
                case 1: play(items, 0); break;
                case 2:
                    Collections.shuffle(items);
                    play(items, 0);
                    break;
                case 3: enqueue(items); break;
                case 4:
                    Ui.prompt(this, "Save as playlist", "Playlist name", dir.getName(), name -> {
                        playlists.save(name, items);
                        Ui.toast(this, "Saved \"" + name + "\" (" + items.size() + ")");
                    });
                    break;
                default: break;
            }
            return true;
        });
        m.show();
    }

    private String prettyPath(File f) {
        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
        String p = f.getAbsolutePath();
        return p.startsWith(root) ? "Internal" + p.substring(root.length()) : p;
    }

    private void goUp() {
        if (folder == null) return;
        filesQuery = "";
        for (File r : storageRoots()) {
            if (r.equals(folder)) {
                folder = null;
                prefs.setLastFolder(null);
                showTab(TAB_FILES);
                return;
            }
        }
        File parent = folder.getParentFile();
        folder = parent != null && parent.canRead() ? parent : null;
        prefs.setLastFolder(folder == null ? null : folder.getAbsolutePath());
        showTab(TAB_FILES);
    }

    private static String size(long b) {
        return FileActions.size(b);
    }

    // ---- Playlists tab --------------------------------------------------------------------

    private View buildPlaylists() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        if (openPlaylist != null) {
            PlaylistStore.Playlist p = playlists.get(openPlaylist);
            if (p != null) return buildPlaylistDetail(p);
            openPlaylist = null;
        }
        // YouTube playlists live in the YouTube tab; this tab is for your own media.
        List<PlaylistStore.Playlist> all = new ArrayList<>();
        for (PlaylistStore.Playlist p : playlists.all()) if (!isYouTubePlaylist(p)) all.add(p);
        List<MediaItem> queue = service == null ? new ArrayList<>() : service.queue();

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), dp(4), dp(12), dp(8));
        bar.addView(Ui.text(this, all.size() + " saved playlists", 13, getColor(R.color.text_dim)), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!queue.isEmpty()) {
            TextView save = Ui.button(this, "Save queue", false);
            save.setOnClickListener(v -> Ui.prompt(this, "Save queue as playlist", "Playlist name", "", name -> {
                playlists.save(name, queue);
                CacheService.onAddedToPlaylist(this, queue);
                showTab(TAB_PLAYLISTS);
            }));
            bar.addView(save);
        }
        TextView add = Ui.button(this, "+ New", true);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.setMarginStart(dp(8));
        add.setOnClickListener(v -> Ui.prompt(this, "New playlist", "Playlist name", "", name -> {
            if (playlists.get(name) == null) playlists.save(name, new ArrayList<>());
            openPlaylist = name;
            showTab(TAB_PLAYLISTS);
        }));
        bar.addView(add, alp);
        box.addView(bar);

        // Entry 0 is the live queue when there is one.
        final boolean hasQueue = !queue.isEmpty();
        List<Object> rows = new ArrayList<>();
        if (hasQueue) rows.add("queue");
        List<YouTubeCache.Entry> offlineEntries = new ArrayList<>();
        rows.addAll(all);
        ListView list = listView();
        RowAdapter<Object> overview = new RowAdapter<>(rows, (row, o) -> {
            if ("offline".equals(o)) {
                row.setIcon(R.drawable.ic_save, getColor(R.color.c_orange));
                row.title.setText("⬇ Offline videos");
                long bytes = 0;
                for (YouTubeCache.Entry e : offlineEntries) bytes += e.bytes;
                row.sub.setText(offlineEntries.size() + (offlineEntries.size() == 1 ? " video" : " videos")
                        + " · " + YouTubeCache.Entry.formatBytes(bytes) + " · plays without a connection");
            } else if (o instanceof String) {
                row.setIcon(R.drawable.ic_play, getColor(R.color.accent));
                row.title.setText("Now playing queue");
                row.sub.setText(queue.size() + (queue.size() == 1 ? " item · " : " items · ") + new String[]{"play once", "repeat file", "repeat all"}[service.repeatMode()] + (service.shuffle() ? " · shuffle" : ""));
            } else {
                PlaylistStore.Playlist p = (PlaylistStore.Playlist) o;
                row.setIcon(R.drawable.ic_playlist, Ui.paletteColor(this, p.name));
                row.title.setText(p.name);
                int offline = cachedCount(p.items);
                row.sub.setText(p.items.size() + (p.items.size() == 1 ? " item" : " items")
                        + (offline > 0 ? "  ·  ⬇ " + offline + " offline" : ""));
            }
            row.trailing.setText("");
        });
        list.setAdapter(overview);
        cacheAdapter = overview;
        list.setOnItemClickListener((parent, v, pos, id) -> {
            Object o = rows.get(pos);
            if ("offline".equals(o)) {
                openOffline = true;
                showTab(TAB_PLAYLISTS);
            } else if (o instanceof String) PlayerActivity.open(this);
            else {
                openPlaylist = ((PlaylistStore.Playlist) o).name;
                showTab(TAB_PLAYLISTS);
            }
        });
        list.setOnItemLongClickListener((parent, v, pos, id) -> {
            Object o = rows.get(pos);
            if (!(o instanceof PlaylistStore.Playlist)) return false;
            PlaylistStore.Playlist p = (PlaylistStore.Playlist) o;
            PopupMenu m = new PopupMenu(this, v, Gravity.END);
            m.getMenu().add(0, 1, 0, "Play");
            m.getMenu().add(0, 2, 0, "Rename…");
            m.getMenu().add(0, 3, 0, "Delete playlist");
            if (hasYouTube(p.items)) m.getMenu().add(0, 4, 0, "Cache YouTube videos for offline…");
            if (cachedCount(p.items) > 0) m.getMenu().add(0, 5, 0, "Delete offline copies…");
            m.setOnMenuItemClickListener(i -> {
                if (i.getItemId() == 4) {
                    chooseCacheHeight(p.items);
                    return true;
                }
                if (i.getItemId() == 5) {
                    confirmDeleteCached(p.items, "\"" + p.name + "\"");
                    return true;
                }
                if (i.getItemId() == 1) play(p.items, 0);
                else if (i.getItemId() == 2) Ui.prompt(this, "Rename playlist", "Name", p.name, name -> {
                    playlists.save(name, p.items);
                    if (!name.equals(p.name)) playlists.delete(p.name);
                    showTab(TAB_PLAYLISTS);
                });
                else confirmDelete(p.name);
                return true;
            });
            m.show();
            return true;
        });
        if (rows.isEmpty()) box.addView(emptyText("No playlists yet.\nLong-press a file or folder and choose \"Add to playlist\", or save the queue from the player menu.\nYouTube playlists are in the YouTube tab."));
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return box;
    }

    /** Every cached YouTube video, newest first, as an automatic playlist. */
    private View buildOfflinePlaylist() {
        return buildOfflinePlaylist(false);
    }

    /** @param inSection shown as the YouTube tab's Offline section (no back button). */
    private View buildOfflinePlaylist(boolean inSection) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        YouTubeCache st = CacheService.store(this);
        List<YouTubeCache.Entry> entries = st.all();
        offlineShown = entries.size();
        final List<MediaItem> items = new ArrayList<>();
        for (YouTubeCache.Entry e : entries) {
            items.add(new MediaItem(e.url, e.title, MediaItem.YOUTUBE, e.uploader == null ? "" : e.uploader, e.thumbUrl, e.durationMs));
        }
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(12), dp(8));
        ImageButton back = iconButton(R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> { openOffline = false; showTab(tab); });
        if (!inSection) bar.addView(back);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.text(this, "⬇ Offline videos", 17, getColor(R.color.c_orange));
        name.setTypeface(Typeface.DEFAULT_BOLD);
        names.addView(name);
        names.addView(Ui.text(this, items.size() + (items.size() == 1 ? " video · " : " videos · ")
                + YouTubeCache.Entry.formatBytes(st.totalBytes()), 12, getColor(R.color.text_dim)));
        bar.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton manage = iconButton(R.drawable.ic_delete, "Manage offline copies");
        manage.setOnClickListener(v -> manageCache());
        bar.addView(manage);
        box.addView(bar);

        if (!items.isEmpty()) {
            LinearLayout actions = new LinearLayout(this);
            actions.setPadding(dp(14), 0, dp(14), dp(8));
            TextView playAll = Ui.button(this, "▶ Play all", true);
            playAll.setOnClickListener(v -> play(items, 0));
            TextView shuffle = Ui.button(this, "Shuffle", false);
            shuffle.setOnClickListener(v -> {
                List<MediaItem> sh = new ArrayList<>(items);
                Collections.shuffle(sh);
                play(sh, 0);
            });
            TextView save = Ui.button(this, "Save as playlist", false);
            save.setOnClickListener(v -> Ui.prompt(this, "Save as playlist", "Playlist name", "Offline videos", n -> {
                playlists.save(n, items);
                Ui.toast(this, "Saved \"" + n + "\"");
            }));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMarginEnd(dp(6));
            actions.addView(playAll, lp);
            actions.addView(shuffle, new LinearLayout.LayoutParams(lp));
            actions.addView(save, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            box.addView(actions);
        }

        ListView list = listView();
        RowAdapter<MediaItem> adapter = new RowAdapter<>(items, this::bindMedia);
        list.setAdapter(adapter);
        cacheAdapter = adapter;
        list.setOnItemClickListener((p, v, pos, id) -> play(items, pos));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            MediaItem m = items.get(pos);
            PopupMenu pm = new PopupMenu(this, v, Gravity.END);
            pm.getMenu().add(0, 1, 0, "Play from here");
            pm.getMenu().add(0, 2, 0, "Add to queue");
            pm.getMenu().add(0, 3, 0, "Add to playlist…");
            pm.getMenu().add(0, 4, 0, "Delete offline copy");
            pm.setOnMenuItemClickListener(it -> {
                switch (it.getItemId()) {
                    case 1: play(items, pos); break;
                    case 2: enqueue(Collections.singletonList(m)); break;
                    case 3: addToPlaylist(Collections.singletonList(m)); break;
                    default:
                        deleteCached(Collections.singletonList(m));
                        Ui.toast(this, "Offline copy deleted");
                        showTab(tab);
                        break;
                }
                return true;
            });
            pm.show();
            return true;
        });
        if (items.isEmpty()) box.addView(emptyText("No offline videos yet.\nCache a playlist, long-press a YouTube result, or turn on Auto-cache in Settings."));
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return box;
    }

    private void confirmDelete(String name) {
        PlaylistStore.Playlist p = playlists.get(name);
        int offline = p == null ? 0 : cachedCount(p.items);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(Ui.text(this, "\"" + name + "\" will be removed. The media files are not touched.", 14, getColor(R.color.text)));
        android.widget.CheckBox alsoCache = new android.widget.CheckBox(this);
        alsoCache.setText("Also delete its " + offline + " offline YouTube " + (offline == 1 ? "copy" : "copies"));
        alsoCache.setTextColor(getColor(R.color.text));
        if (offline > 0) box.addView(alsoCache);
        Ui.dialog(this).setTitle("Delete playlist?").setView(Ui.padded(this, box))
                .setPositiveButton("Delete", (d, w) -> {
                    if (alsoCache.isChecked() && p != null) deleteCached(p.items);
                    playlists.delete(name);
                    openPlaylist = null;
                    showTab(tab);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private View buildPlaylistDetail(PlaylistStore.Playlist p) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(12), dp(8));
        ImageButton back = iconButton(R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> { openPlaylist = null; showTab(tab); });
        bar.addView(back);
        TextView name = Ui.text(this, p.name, 17, getColor(R.color.text));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton del = iconButton(R.drawable.ic_delete, "Delete playlist");
        del.setOnClickListener(v -> confirmDelete(p.name));
        bar.addView(del);
        box.addView(bar);

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(dp(14), 0, dp(14), dp(8));
        TextView playAll = Ui.button(this, "▶ Play all", true);
        playAll.setOnClickListener(v -> play(p.items, 0));
        TextView shuffle = Ui.button(this, "Shuffle", false);
        shuffle.setOnClickListener(v -> {
            List<MediaItem> s = new ArrayList<>(p.items);
            Collections.shuffle(s);
            play(s, 0);
        });
        TextView queue = Ui.button(this, "Add to queue", false);
        queue.setOnClickListener(v -> enqueue(p.items));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMarginEnd(dp(6));
        actions.addView(playAll, lp);
        actions.addView(shuffle, new LinearLayout.LayoutParams(lp));
        actions.addView(queue, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!p.items.isEmpty()) box.addView(actions);

        if (hasYouTube(p.items)) {
            LinearLayout offline = new LinearLayout(this);
            offline.setGravity(Gravity.CENTER_VERTICAL);
            offline.setPadding(dp(14), 0, dp(14), dp(8));
            cacheStatus = Ui.text(this, "", 12, getColor(R.color.text_dim));
            cacheStatus.setMaxLines(2);
            offline.addView(cacheStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView cache = Ui.button(this, "⬇ Cache offline", false);
            cache.setOnClickListener(v -> chooseCacheHeight(p.items));
            offline.addView(cache);
            TextView drop = Ui.button(this, "Delete offline", false);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.setMarginStart(dp(6));
            drop.setOnClickListener(v -> {
                if (cachedCount(p.items) == 0) Ui.toast(this, "Nothing from this playlist is cached");
                else confirmDeleteCached(p.items, "\"" + p.name + "\"");
            });
            offline.addView(drop, dlp);
            box.addView(offline);
            cacheStatusItems = p.items;
            updateCacheStatus();
        }

        ListView list = listView();
        RowAdapter<MediaItem> detail = new RowAdapter<>(p.items, this::bindMedia);
        list.setAdapter(detail);
        cacheAdapter = detail;
        list.setOnItemClickListener((parent, v, pos, id) -> play(p.items, pos));
        list.setOnItemLongClickListener((parent, v, pos, id) -> {
            PopupMenu m = new PopupMenu(this, v, Gravity.END);
            m.getMenu().add(0, 1, 0, "Play from here");
            m.getMenu().add(0, 2, 0, "Add to queue");
            m.getMenu().add(0, 3, 0, "Remove from playlist");
            addCacheMenuItems(m, p.items.get(pos));
            m.setOnMenuItemClickListener(i -> {
                if (handleCacheMenuItem(i.getItemId(), p.items.get(pos))) return true;
                if (i.getItemId() == 1) play(p.items, pos);
                else if (i.getItemId() == 2) enqueue(Collections.singletonList(p.items.get(pos)));
                else {
                    playlists.removeItem(p.name, pos);
                    showTab(tab);
                }
                return true;
            });
            m.show();
            return true;
        });
        if (p.items.isEmpty()) box.addView(emptyText("This playlist is empty.\nLong-press files or YouTube results to add them."));
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return box;
    }

    private void bindMedia(Ui.Row row, MediaItem m) {
        YouTubeCache.Entry cached = m.kind == MediaItem.YOUTUBE ? cachedEntry(m) : null;
        File localThumb = cached == null ? null : CacheService.store(this).thumbFile(cached);
        if (localThumb != null) row.setThumb(localThumb.getAbsolutePath());
        else if (m.kind == MediaItem.YOUTUBE && m.thumb != null) row.setThumb(m.thumb);
        else if (m.kind == MediaItem.STREAM) row.setIcon(R.drawable.ic_link, getColor(R.color.c_teal));
        else if (MediaFiles.isAudio(m.title)) row.setIcon(R.drawable.ic_music_file, getColor(R.color.c_violet));
        else row.setIcon(R.drawable.ic_movie, getColor(R.color.c_orange));
        row.title.setText(m.title);
        String status = m.kind == MediaItem.YOUTUBE ? cacheText(m, cached) : "";
        row.sub.setText(status.isEmpty() ? m.subtitle : (m.subtitle.isEmpty() ? status : m.subtitle + "  ·  " + status));
        row.trailing.setText(m.durationMs > 0 ? MediaFiles.formatTime(m.durationMs) : "");
    }

    // ---- offline YouTube cache ----------------------------------------------------------

    private List<MediaItem> cacheStatusItems;

    private YouTubeCache.Entry cachedEntry(MediaItem m) {
        String id = YouTubeCache.videoId(m.uri);
        return id == null ? null : CacheService.store(this).get(id);
    }

    /** "⬇ 720p · 38 MB", "Caching 45%", "Waiting to cache", "Cache failed: …" or "". */
    private String cacheText(MediaItem m, YouTubeCache.Entry cached) {
        CacheService.Job j = CacheService.job(YouTubeCache.videoId(m.uri));
        if (j != null && j.state == CacheService.RUNNING) {
            int pct = j.percent();
            return "Caching " + (pct >= 0 ? pct + "%" : "…");
        }
        if (j != null && j.state == CacheService.QUEUED) return "Waiting to cache";
        if (cached != null) return "⬇ " + (cached.height > 0 ? cached.height + "p" : "audio") + " · " + YouTubeCache.Entry.formatBytes(cached.bytes);
        if (j != null && j.state == CacheService.FAILED) return "Cache failed: " + j.error;
        return "";
    }

    private static boolean hasYouTube(List<MediaItem> items) {
        for (MediaItem m : items) if (m.kind == MediaItem.YOUTUBE) return true;
        return false;
    }

    private int cachedCount(List<MediaItem> items) {
        int n = 0;
        for (MediaItem m : items) if (m.kind == MediaItem.YOUTUBE && cachedEntry(m) != null) n++;
        return n;
    }

    private void addCacheMenuItems(PopupMenu m, MediaItem it) {
        if (it.kind != MediaItem.YOUTUBE || YouTubeCache.videoId(it.uri) == null) return;
        m.getMenu().add(0, 90, 0, "Cache for offline…");
        if (cachedEntry(it) != null) m.getMenu().add(0, 91, 0, "Delete offline copy");
    }

    private boolean handleCacheMenuItem(int id, MediaItem it) {
        if (id == 90) {
            chooseCacheHeight(Collections.singletonList(it));
            return true;
        }
        if (id == 91) {
            deleteCached(Collections.singletonList(it));
            Ui.toast(this, "Offline copy deleted");
            onCacheChanged();
            return true;
        }
        return false;
    }

    /** Asks for the quality, then queues every YouTube video of {@code items} for offline. */
    private void chooseCacheHeight(List<MediaItem> items) {
        List<MediaItem> yt = new ArrayList<>();
        for (MediaItem m : items) if (m.kind == MediaItem.YOUTUBE && YouTubeCache.videoId(m.uri) != null) yt.add(m);
        if (yt.isEmpty()) {
            Ui.toast(this, "No YouTube videos to cache here");
            return;
        }
        Runnable pick = () -> {
            int h = prefs.effectiveCacheHeight();
            int added = CacheService.enqueue(this, yt, h);
            if (added == 0) Ui.toast(this, "Already cached at " + h + "p");
            else Ui.toast(this, "Caching " + added + (added == 1 ? " video" : " videos") + " at up to " + h + "p"
                    + (prefs.cacheWifiOnly() ? " on Wi-Fi" : "") + " · change quality in Settings");
            onCacheChanged();
        };
        withCacheNotice(pick);
    }

    /** Shows the one-time offline-cache explanation, then runs {@code next}. */
    private void withCacheNotice(Runnable next) {
        if (prefs.cacheNoticeShown()) {
            next.run();
            return;
        }
        Ui.dialog(this).setTitle("Offline cache")
                .setMessage("Astra keeps a private copy inside the app, so you can watch later without streaming.\n\n"
                        + "Copies stay in Astra: they are not saved to your gallery, cannot be shared and are removed with the app. "
                        + "Live streams cannot be cached. Downloads run on Wi-Fi only unless you change it in Settings.\n\n"
                        + "Only cache videos you are allowed to keep offline; YouTube's terms apply.")
                .setPositiveButton("Continue", (d, w) -> {
                    prefs.setCacheNoticeShown();
                    next.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteCached(List<MediaItem> items) {
        YouTubeCache st = CacheService.store(this);
        for (MediaItem m : items) {
            if (m.kind != MediaItem.YOUTUBE) continue;
            String id = YouTubeCache.videoId(m.uri);
            if (id == null) continue;
            CacheService.forget(this, id);
            st.delete(id);
        }
    }

    private void confirmDeleteCached(List<MediaItem> items, String what) {
        long bytes = 0;
        int n = 0;
        for (MediaItem m : items) {
            YouTubeCache.Entry e = m.kind == MediaItem.YOUTUBE ? cachedEntry(m) : null;
            if (e == null) continue;
            n++;
            bytes += e.bytes;
        }
        if (n == 0) return;
        Ui.confirm(this, "Delete offline copies?", n + (n == 1 ? " cached video" : " cached videos") + " of " + what
                + " (" + YouTubeCache.Entry.formatBytes(bytes) + ") will be removed. The playlist stays.", "Delete", () -> {
            deleteCached(items);
            Ui.toast(this, "Offline copies deleted");
            onCacheChanged();
        });
    }

    /** Settings › Cached videos: each cached video with a delete button; tap a row to play it offline. */
    private void manageCache() {
        YouTubeCache st = CacheService.store(this);
        final List<YouTubeCache.Entry> rows = st.all();
        if (rows.isEmpty()) {
            Ui.dialog(this).setTitle("Offline cache")
                    .setMessage("Nothing is cached. Open a playlist with YouTube videos and tap \"⬇ Cache offline\", long-press a YouTube result, or turn on Auto-cache.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        ListView list = listView();
        final android.app.AlertDialog[] dialog = new android.app.AlertDialog[1];
        final RowAdapter<YouTubeCache.Entry>[] adapter = new RowAdapter[1];
        adapter[0] = new RowAdapter<>(rows, (row, e) -> {
            File thumb = st.thumbFile(e);
            if (thumb != null) row.setThumb(thumb.getAbsolutePath());
            else row.setIcon(R.drawable.ic_save, getColor(R.color.c_orange));
            row.title.setText(e.title == null ? e.id : e.title);
            String ago = android.text.format.DateUtils.getRelativeTimeSpanString(e.cachedAt, System.currentTimeMillis(),
                    android.text.format.DateUtils.MINUTE_IN_MILLIS, android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE).toString();
            row.sub.setText((e.height > 0 ? e.height + "p" : "audio") + " · " + YouTubeCache.Entry.formatBytes(e.bytes) + " · " + ago);
            row.trailing.setText(e.durationMs > 0 ? MediaFiles.formatTime(e.durationMs) : "");
            row.setMore(v -> {
                CacheService.forget(this, e.id);
                st.delete(e.id);
                rows.remove(e);
                adapter[0].notifyDataSetChanged();
                Ui.toast(this, "Deleted offline copy");
                if (dialog[0] != null) dialog[0].setTitle(cacheManagerTitle(st, rows.size()));
                if (rows.isEmpty() && dialog[0] != null) dialog[0].dismiss();
                onCacheChanged();
            });
            row.more.setImageResource(R.drawable.ic_delete);
            row.more.setContentDescription("Delete offline copy");
        });
        list.setAdapter(adapter[0]);
        list.setOnItemClickListener((p, v, pos, id) -> {
            YouTubeCache.Entry e = rows.get(pos);
            if (dialog[0] != null) dialog[0].dismiss();
            play(Collections.singletonList(new MediaItem(e.url, e.title, MediaItem.YOUTUBE, e.uploader, e.thumbUrl, e.durationMs)), 0);
        });
        list.setMinimumHeight(dp(360));
        dialog[0] = Ui.dialog(this).setTitle(cacheManagerTitle(st, rows.size())).setView(list)
                .setNeutralButton("Delete all", (d, w) -> Ui.confirm(this, "Delete all offline copies?",
                        rows.size() + (rows.size() == 1 ? " video" : " videos") + " (" + YouTubeCache.Entry.formatBytes(st.totalBytes()) + ") will be removed.",
                        "Delete all", () -> {
                            CacheService.cancelAll(this);
                            int n = st.deleteAll();
                            Ui.toast(this, "Deleted " + n + (n == 1 ? " video" : " videos"));
                            onCacheChanged();
                        }))
                .setPositiveButton("Close", null)
                .show();
    }

    private static String cacheManagerTitle(YouTubeCache st, int n) {
        return "Offline cache · " + n + (n == 1 ? " video · " : " videos · ") + YouTubeCache.Entry.formatBytes(st.totalBytes());
    }

    private void updateCacheStatus() {
        if (cacheStatus == null || cacheStatusItems == null) return;
        int yt = 0, offline = 0, busy = 0;
        long bytes = 0;
        for (MediaItem m : cacheStatusItems) {
            if (m.kind != MediaItem.YOUTUBE) continue;
            yt++;
            YouTubeCache.Entry e = cachedEntry(m);
            if (e != null) {
                offline++;
                bytes += e.bytes;
            }
            CacheService.Job j = CacheService.job(YouTubeCache.videoId(m.uri));
            if (j != null && (j.state == CacheService.QUEUED || j.state == CacheService.RUNNING)) busy++;
        }
        String text = "Offline: " + offline + " of " + yt + (offline > 0 ? " · " + YouTubeCache.Entry.formatBytes(bytes) : "");
        if (busy > 0) text += "\nCaching " + busy + " more" + (prefs.cacheWifiOnly() ? " (Wi-Fi only)" : "");
        cacheStatus.setText(text);
    }

    /** A download started, moved on or ended: refresh visible rows without rebuilding. */
    private void onCacheChanged() {
        if (destroyed) return;
        if (tab == TAB_YOUTUBE && ytSection == 1 && offlineShown != CacheService.store(this).all().size()) {
            showTab(TAB_YOUTUBE);
            return;
        }
        if (cacheAdapter != null) cacheAdapter.notifyDataSetChanged();
        updateCacheStatus();
        if (tab == TAB_SETTINGS && cacheSettingsRow != null) cacheSettingsRow.sub.setText(cacheSummary());
    }

    private Ui.Row cacheSettingsRow;

    private String cacheSummary() {
        YouTubeCache st = CacheService.store(this);
        int n = st.all().size();
        String s = n + (n == 1 ? " video" : " videos") + " · " + YouTubeCache.Entry.formatBytes(st.totalBytes());
        int pending = CacheService.pending();
        if (pending > 0) s += " · caching " + pending;
        return s;
    }

    /** Saves a YouTube playlist link as an Astra playlist (first 500 videos). */
    private void importYouTubePlaylist(String url) {
        if (!YouTube.isPlaylistUrl(url)) {
            Ui.toast(this, "That is not a YouTube playlist link");
            return;
        }
        Ui.toast(this, "Reading playlist…");
        worker.execute(() -> {
            try {
                YouTube.ImportedPlaylist ip = YouTube.importPlaylist(url, 500);
                main.post(() -> {
                    if (destroyed) return;
                    String name = ip.name;
                    for (int k = 2; playlists.get(name) != null; k++) name = ip.name + " (" + k + ")";
                    playlists.save(name, ip.items);
                    CacheService.onAddedToPlaylist(this, ip.items);
                    Ui.toast(this, "Imported " + ip.items.size() + " videos" + (ip.truncated ? " (first 500)" : ""));
                    ytSection = 2;
                    openPlaylist = name;
                    showTab(TAB_YOUTUBE);
                });
            } catch (Exception e) {
                main.post(() -> Ui.toast(this, "Could not read playlist: " + (e instanceof java.io.IOException ? "check your connection" : String.valueOf(e.getMessage()))));
            }
        });
    }

    // ---- Streams tab ----------------------------------------------------------------------

    private View buildStreams() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), 0, dp(14), 0);
        EditText url = Ui.input(this, "https://… · rtsp://… · .m3u8 · .mpd · YouTube link", "");
        url.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        url.setImeOptions(EditorInfo.IME_ACTION_GO);
        url.setTag(BOTTOMABLE);
        box.addView(url);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(10), 0, dp(6));
        TextView playBtn = Ui.button(this, "▶ Play", true);
        TextView queueBtn = Ui.button(this, "Add to queue", false);
        TextView saveBtn = Ui.button(this, "Add to playlist", false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMarginEnd(dp(6));
        row.addView(playBtn, lp);
        row.addView(queueBtn, new LinearLayout.LayoutParams(lp));
        row.addView(saveBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setTag(BOTTOMABLE);
        box.addView(row);
        Runnable go = () -> {
            String u = url.getText().toString().trim();
            if (!validUrl(u)) { Ui.toast(this, "Enter a http(s), rtsp, rtmp, mms or udp address"); return; }
            hideKeyboard(url);
            if (YouTube.isPlaylistUrl(u)) {
                importYouTubePlaylist(u);
                return;
            }
            prefs.addHistory(u);
            play(Collections.singletonList(MediaItem.fromUrl(u)), 0);
        };
        playBtn.setOnClickListener(v -> go.run());
        url.setOnEditorActionListener((v, a, e) -> { go.run(); return true; });
        queueBtn.setOnClickListener(v -> {
            String u = url.getText().toString().trim();
            if (!validUrl(u)) return;
            prefs.addHistory(u);
            enqueue(Collections.singletonList(MediaItem.fromUrl(u)));
        });
        saveBtn.setOnClickListener(v -> {
            String u = url.getText().toString().trim();
            if (validUrl(u)) addToPlaylist(Collections.singletonList(MediaItem.fromUrl(u)));
        });
        TextView hint = Ui.text(this, "Plays HTTP files, HLS, DASH, RTSP/RTMP and YouTube links. Long-press a recent address for more.", 12, getColor(R.color.text_dim));
        hint.setPadding(dp(2), dp(4), 0, dp(10));
        box.addView(hint);
        box.addView(Ui.text(this, "Recent", 13, getColor(R.color.accent)));

        List<String> history = prefs.history();
        ListView list = listView();
        list.setPadding(0, dp(6), 0, 0);
        list.setAdapter(new RowAdapter<>(history, (r, h) -> {
            r.setIcon(YouTube.isYouTubeUrl(h) ? R.drawable.ic_play : R.drawable.ic_link, YouTube.isYouTubeUrl(h) ? getColor(R.color.c_rose) : Ui.paletteColor(this, Uri.parse(h).getHost()));
            r.title.setText(MediaItem.guessTitle(h));
            r.sub.setText(h);
            r.trailing.setText("");
        }));
        list.setOnItemClickListener((p, v, pos, id) -> play(Collections.singletonList(MediaItem.fromUrl(history.get(pos))), 0));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            String h = history.get(pos);
            PopupMenu m = new PopupMenu(this, v, Gravity.END);
            m.getMenu().add(0, 1, 0, "Edit address");
            m.getMenu().add(0, 2, 0, "Add to queue");
            m.getMenu().add(0, 3, 0, "Add to playlist…");
            m.getMenu().add(0, 4, 0, "Remove from recent");
            m.setOnMenuItemClickListener(i -> {
                switch (i.getItemId()) {
                    case 1: url.setText(h); url.setSelection(h.length()); break;
                    case 2: enqueue(Collections.singletonList(MediaItem.fromUrl(h))); break;
                    case 3: addToPlaylist(Collections.singletonList(MediaItem.fromUrl(h))); break;
                    default: prefs.removeHistory(h); showTab(TAB_STREAMS); break;
                }
                return true;
            });
            m.show();
            return true;
        });
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return box;
    }

    private static boolean validUrl(String u) {
        return u.matches("(?i)^(https?|rtsp|rtsps|rtmp|rtmps|mms|mmsh|udp|rtp|srt)://\\S+") || YouTube.isYouTubeUrl(u);
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    // ---- YouTube tab ----------------------------------------------------------------------

    /** A playlist made only of YouTube videos; shown in the YouTube tab, not in Playlists. */
    private static boolean isYouTubePlaylist(PlaylistStore.Playlist p) {
        if (p.items.isEmpty()) return false;
        for (MediaItem m : p.items) if (m.kind != MediaItem.YOUTUBE) return false;
        return true;
    }

    private View buildYouTube() {
        if (ytSection == 2 && openPlaylist != null) {
            PlaylistStore.Playlist p = playlists.get(openPlaylist);
            if (p != null) return buildPlaylistDetail(p);
            openPlaylist = null;
        }
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(dp(14), 0, dp(14), dp(8));
        String[] labels = {"Search", "⬇ Offline", "Playlists"};
        int[] colors = {getColor(R.color.c_rose), getColor(R.color.c_orange), getColor(R.color.c_violet)};
        for (int k = 0; k < labels.length; k++) {
            TextView chip = Ui.text(this, labels[k], 14, 0);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(16), dp(7), dp(16), dp(7));
            chip.setBackgroundResource(R.drawable.bg_chip);
            boolean on = ytSection == k;
            chip.setBackgroundTintList(ColorStateList.valueOf(on ? colors[k] : Ui.soft(colors[k])));
            chip.setTextColor(on ? 0xFFFFFFFF : getColor(R.color.text));
            if (on) chip.setTypeface(Typeface.DEFAULT_BOLD);
            final int kk = k;
            chip.setOnClickListener(v -> {
                ytSection = kk;
                openPlaylist = null;
                showTab(TAB_YOUTUBE);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            chips.addView(chip, lp);
        }
        outer.addView(chips);
        View body = ytSection == 1 ? buildOfflinePlaylist(true) : ytSection == 2 ? buildYouTubePlaylists() : buildYouTubeSearch();
        outer.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return outer;
    }

    /** Saved playlists made of YouTube videos, with import, offline counts and caching. */
    private View buildYouTubePlaylists() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        List<PlaylistStore.Playlist> yt = new ArrayList<>();
        for (PlaylistStore.Playlist p : playlists.all()) if (isYouTubePlaylist(p)) yt.add(p);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), dp(2), dp(12), dp(8));
        bar.addView(Ui.text(this, yt.size() + (yt.size() == 1 ? " YouTube playlist" : " YouTube playlists"), 13, getColor(R.color.text_dim)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView imp = Ui.button(this, "+ Import playlist link", true);
        imp.setOnClickListener(v -> Ui.prompt(this, "Import YouTube playlist", "Playlist link (youtube.com/playlist?list=…)", "", this::importYouTubePlaylist));
        bar.addView(imp);
        box.addView(bar);
        ListView list = listView();
        RowAdapter<PlaylistStore.Playlist> adapter = new RowAdapter<>(yt, (row, p) -> {
            row.setIcon(R.drawable.ic_playlist, Ui.paletteColor(this, p.name));
            row.title.setText(p.name);
            int offline = cachedCount(p.items);
            row.sub.setText(p.items.size() + (p.items.size() == 1 ? " video" : " videos")
                    + (offline > 0 ? "  ·  ⬇ " + offline + " offline" : ""));
            row.trailing.setText("");
        });
        list.setAdapter(adapter);
        cacheAdapter = adapter;
        list.setOnItemClickListener((parent, v, pos, id) -> {
            openPlaylist = yt.get(pos).name;
            showTab(TAB_YOUTUBE);
        });
        list.setOnItemLongClickListener((parent, v, pos, id) -> {
            PlaylistStore.Playlist p = yt.get(pos);
            PopupMenu m = new PopupMenu(this, v, Gravity.END);
            m.getMenu().add(0, 1, 0, "Play");
            m.getMenu().add(0, 2, 0, "Cache for offline");
            if (cachedCount(p.items) > 0) m.getMenu().add(0, 3, 0, "Delete offline copies…");
            m.getMenu().add(0, 4, 0, "Rename…");
            m.getMenu().add(0, 5, 0, "Delete playlist");
            m.setOnMenuItemClickListener(i -> {
                switch (i.getItemId()) {
                    case 1: play(p.items, 0); break;
                    case 2: chooseCacheHeight(p.items); break;
                    case 3: confirmDeleteCached(p.items, "\"" + p.name + "\""); break;
                    case 4:
                        Ui.prompt(this, "Rename playlist", "Name", p.name, name -> {
                            playlists.save(name, p.items);
                            if (!name.equals(p.name)) playlists.delete(p.name);
                            showTab(TAB_YOUTUBE);
                        });
                        break;
                    default: confirmDelete(p.name); break;
                }
                return true;
            });
            m.show();
            return true;
        });
        if (yt.isEmpty()) box.addView(emptyText("No YouTube playlists yet.\nImport a playlist link, or long-press a search result and choose \"Add to playlist\"."));
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return box;
    }

    private View buildYouTubeSearch() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText q = Ui.input(this, "Search YouTube", ytQuery);
        q.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchRow.addView(q, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton go = iconButton(R.drawable.ic_search, "Search");
        go.setBackgroundResource(R.drawable.bg_play);
        go.setImageTintList(ColorStateList.valueOf(0xFF101013));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(44), dp(44));
        glp.setMarginStart(dp(8));
        searchRow.addView(go, glp);
        searchRow.setTag(BOTTOMABLE);
        searchRow.setPadding(0, dp(4), 0, dp(4));
        box.addView(searchRow);
        Runnable search = () -> {
            String s = q.getText().toString().trim();
            if (s.isEmpty()) return;
            hideKeyboard(q);
            if (YouTube.isPlaylistUrl(s)) {
                importYouTubePlaylist(s);
                return;
            }
            if (YouTube.isYouTubeUrl(s)) {
                play(Collections.singletonList(MediaItem.fromUrl(s)), 0);
                return;
            }
            runSearch(s);
        };
        go.setOnClickListener(v -> search.run());
        q.setOnEditorActionListener((v, a, e) -> {
            if (a == EditorInfo.IME_ACTION_SEARCH || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) search.run();
            return true;
        });

        TextView note = Ui.text(this, "No account and no API key. Long-press a video, or open a playlist, to cache it for offline viewing. Paste a playlist link to import it.", 12, getColor(R.color.text_dim));
        note.setPadding(dp(2), dp(6), 0, dp(6));
        box.addView(note);

        if (ytLoading && ytResults.isEmpty()) {
            ProgressBar pb = new ProgressBar(this);
            pb.setIndeterminateTintList(ColorStateList.valueOf(getColor(R.color.amber)));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(40), dp(40));
            plp.gravity = Gravity.CENTER_HORIZONTAL;
            plp.topMargin = dp(40);
            box.addView(pb, plp);
            return box;
        }
        if (ytError != null) box.addView(emptyText(ytError));

        ListView list = listView();
        list.setAdapter(new RowAdapter<>(ytResults, this::bindMedia));
        list.setOnItemClickListener((p, v, pos, id) -> play(Collections.singletonList(ytResults.get(pos)), 0));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            itemMenu(v, ytResults, pos);
            return true;
        });
        if (ytPage != null && ytPage.hasMore()) {
            TextView more = Ui.button(this, ytLoading ? "Loading…" : "More results", false);
            more.setOnClickListener(v -> loadMore());
            FrameLayout f = new FrameLayout(this);
            f.setPadding(0, dp(8), 0, dp(16));
            f.addView(more);
            list.addFooterView(f, null, false);
        }
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return box;
    }

    private void runSearch(String query) {
        ytSection = 0;
        ytQuery = query;
        ytResults.clear();
        ytPage = null;
        ytError = null;
        ytLoading = true;
        showTab(TAB_YOUTUBE);
        worker.execute(() -> {
            try {
                YouTube.Results r = YouTube.search(query);
                main.post(() -> {
                    if (!query.equals(ytQuery)) return;
                    ytLoading = false;
                    ytPage = r;
                    ytResults.addAll(r.items);
                    if (r.items.isEmpty()) ytError = "No results for \"" + query + "\"";
                    if (tab == TAB_YOUTUBE) showTab(TAB_YOUTUBE);
                });
            } catch (Exception e) {
                main.post(() -> {
                    ytLoading = false;
                    ytError = "Search failed: " + (e instanceof java.io.IOException ? "check your connection" : String.valueOf(e.getMessage()));
                    if (tab == TAB_YOUTUBE) showTab(TAB_YOUTUBE);
                });
            }
        });
    }

    private void loadMore() {
        if (ytLoading || ytPage == null) return;
        ytLoading = true;
        YouTube.Results prev = ytPage;
        worker.execute(() -> {
            try {
                YouTube.Results r = YouTube.more(prev);
                main.post(() -> {
                    ytLoading = false;
                    ytPage = r;
                    ytResults.addAll(r.items);
                    if (tab == TAB_YOUTUBE) showTab(TAB_YOUTUBE);
                });
            } catch (Exception e) {
                main.post(() -> {
                    ytLoading = false;
                    Ui.toast(this, "Could not load more results");
                });
            }
        });
    }

    // ---- shared list plumbing -------------------------------------------------------------

    private ListView listView() {
        ListView l = new ListView(this);
        l.setDivider(null);
        l.setDividerHeight(dp(6));
        l.setSelector(android.R.color.transparent);
        l.setClipToPadding(false);
        l.setPadding(dp(10), 0, dp(10), dp(10));
        l.setFastScrollEnabled(true);
        return l;
    }

    private TextView emptyText(String s) {
        TextView t = Ui.text(this, s, 14, getColor(R.color.text_dim));
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(24), dp(32), dp(24), dp(8));
        return t;
    }

    private static final class RowAdapter<T> extends BaseAdapter {
        private final List<T> items;
        private final BiConsumer<Ui.Row, T> binder;

        RowAdapter(List<T> items, BiConsumer<Ui.Row, T> binder) {
            this.items = items;
            this.binder = binder;
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Ui.Row row = convert instanceof Ui.Row ? (Ui.Row) convert : new Ui.Row(parent.getContext());
            binder.accept(row, items.get(i));
            return row;
        }
    }

    @Override
    public void onBackPressed() {
        if (tab == TAB_FILES && folder != null && hasStorageAccess()) {
            if (prefs.currentView() == Prefs.FILES_BROWSE) goUp();
            else closeVideoFolder();
            return;
        }
        if (tab == TAB_YOUTUBE && openPlaylist != null) {
            openPlaylist = null;
            showTab(TAB_YOUTUBE);
            return;
        }
        if (tab == TAB_PLAYLISTS && openPlaylist != null) {
            openPlaylist = null;
            showTab(TAB_PLAYLISTS);
            return;
        }
        super.onBackPressed();
    }
}
