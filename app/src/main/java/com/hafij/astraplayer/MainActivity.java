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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/** Library screen: Files, Playlists, Streams and YouTube tabs plus a now-playing bar. */
public class MainActivity extends Activity implements PlaybackService.Listener {
    private static final int TAB_FILES = 0, TAB_PLAYLISTS = 1, TAB_STREAMS = 2, TAB_YOUTUBE = 3;
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
    private List<MediaFiles.VideoFolder> videoFolders = new ArrayList<>();
    private String videoFoldersJson;
    private boolean scanning;
    private long lastScan;
    private List<File> allVideos; // null until built from videoFolders
    private boolean allVideosBuilding;
    private int allVideosGen;
    private boolean hadStorageAccess;
    private boolean destroyed;
    private BaseAdapter filesAdapter;
    private TextView filesStatus;
    // Playlists tab: null = overview, otherwise the open playlist
    private String openPlaylist;
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
        videoFoldersJson = prefs.videoFolders();
        videoFolders = MediaFiles.foldersFromJson(videoFoldersJson);
        hadStorageAccess = hasStorageAccess();
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
            else if (filesAdapter != null) filesAdapter.notifyDataSetChanged();
        } else if (tab == TAB_PLAYLISTS) {
            showTab(TAB_PLAYLISTS);
        }
        hadStorageAccess = access;
        if (access && prefs.filesView() != Prefs.FILES_BROWSE && SystemClock.elapsedRealtime() - lastScan > SCAN_INTERVAL_MS) {
            rescan();
        }
        refreshNowBar();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
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
        nowBar.setBackgroundResource(R.drawable.bg_card);
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
        String[] names = {"Files", "Playlists", "Streams", "YouTube"};
        int[] icons = {R.drawable.ic_folder, R.drawable.ic_playlist, R.drawable.ic_link, R.drawable.ic_search};
        for (int i = 0; i < names.length; i++) {
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(6), 0, dp(6));
            t.setBackgroundResource(R.drawable.bg_round_btn);
            ImageView iv = new ImageView(this);
            iv.setImageResource(icons[i]);
            t.addView(iv, new LinearLayout.LayoutParams(dp(22), dp(22)));
            TextView tv = Ui.text(this, names[i], 11, getColor(R.color.text_dim));
            tv.setGravity(Gravity.CENTER);
            t.addView(tv);
            final int idx = i;
            t.setOnClickListener(v -> {
                if (idx == TAB_PLAYLISTS && tab == TAB_PLAYLISTS) openPlaylist = null;
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
        for (int i = 0; i < tabs.size(); i++) {
            LinearLayout l = tabs.get(i);
            int color = i == t ? getColor(R.color.accent) : getColor(R.color.text_dim);
            ((ImageView) l.getChildAt(0)).setImageTintList(ColorStateList.valueOf(color));
            ((TextView) l.getChildAt(1)).setTextColor(color);
        }
        content.removeAllViews();
        View v;
        switch (t) {
            case TAB_PLAYLISTS: v = buildPlaylists(); break;
            case TAB_STREAMS: v = buildStreams(); break;
            case TAB_YOUTUBE: v = buildYouTube(); break;
            default: v = buildFiles(); break;
        }
        content.addView(v);
    }

    private void showAppMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Play in background").setCheckable(true).setChecked(prefs.backgroundPlay());
        m.getMenu().add(0, 2, 0, "Resume where I left off").setCheckable(true).setChecked(prefs.resumeEnabled());
        m.getMenu().add(0, 4, 0, "Theme: " + Prefs.THEME_NAMES[prefs.theme()]);
        m.getMenu().add(0, 5, 0, "Files view: " + Prefs.FILES_VIEW_NAMES[prefs.filesView()]);
        m.getMenu().add(0, 3, 0, "About Astra Player");
        m.setOnMenuItemClickListener(i -> {
            if (i.getItemId() == 1) prefs.setBackgroundPlay(!prefs.backgroundPlay());
            else if (i.getItemId() == 2) prefs.setResumeEnabled(!prefs.resumeEnabled());
            else if (i.getItemId() == 4) chooseTheme();
            else if (i.getItemId() == 5) chooseFilesView();
            else {
                String v = "";
                try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
                Ui.dialog(this).setTitle("Astra Player " + v)
                        .setMessage("A free media player with no ads, no account and no subscription.\n\n"
                                + "Playback: libVLC (LGPL 2.1+), VideoLAN.\n"
                                + "YouTube: NewPipeExtractor (GPL 3.0+), Team NewPipe.\n\n"
                                + "Astra Player is free software under the GNU GPL 3.0 or later.\n"
                                + "github.com/Hafij-DEU/astra-player-android")
                        .setPositiveButton("OK", null).show();
            }
            return true;
        });
        m.show();
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
            android.text.SpannableString sp = new android.text.SpannableString(Prefs.FILES_VIEW_NAMES[i] + "\n" + Prefs.FILES_VIEW_HINTS[i]);
            int start = Prefs.FILES_VIEW_NAMES[i].length() + 1;
            sp.setSpan(new android.text.style.RelativeSizeSpan(0.8f), start, sp.length(), 0);
            sp.setSpan(new android.text.style.ForegroundColorSpan(getColor(R.color.text_dim)), start, sp.length(), 0);
            items[i] = sp;
        }
        Ui.dialog(this).setTitle("Files view").setSingleChoiceItems(items, prefs.filesView(), (d, w) -> {
            d.dismiss();
            setFilesView(w);
        }).show();
    }

    private void setFilesView(int view) {
        prefs.setFilesView(view);
        folder = null;
        prefs.setLastFolder(null);
        showTab(TAB_FILES);
        if (view != Prefs.FILES_BROWSE && lastScan == 0) rescan();
    }

    // ---- playback helpers -----------------------------------------------------------------

    private void play(List<MediaItem> items, int start) {
        if (items.isEmpty()) return;
        PlaybackService.start(this);
        if (service != null) {
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
        service.enqueue(items);
        Ui.toast(this, items.size() == 1 ? "Added to queue" : items.size() + " items added to queue");
        if (!was) PlayerActivity.open(this);
    }

    private void addToPlaylist(List<MediaItem> items) {
        Ui.choosePlaylist(this, playlists, name -> {
            playlists.append(name, items);
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
        m.setOnMenuItemClickListener(i -> {
            MediaItem it = items.get(pos);
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
        int view = prefs.filesView();
        if (view == Prefs.FILES_BROWSE) return buildBrowse(box);
        if (folder != null) return buildVideoFolder(box, folder);
        return view == Prefs.FILES_ALL ? buildAllVideos(box) : buildVideoFolders(box);
    }

    /** Status text, view switch and refresh, shared by the folder and all-videos views. */
    private void addScanBar(LinearLayout box) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), 0, dp(8), dp(6));
        filesStatus = Ui.text(this, "", 13, getColor(R.color.text_dim));
        filesStatus.setSingleLine(true);
        bar.addView(filesStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(viewSwitchButton());
        ImageButton refresh = iconButton(R.drawable.ic_refresh, "Look for videos again");
        refresh.setOnClickListener(v -> rescan());
        bar.addView(refresh);
        box.addView(bar);
        updateFilesStatus();
    }

    private ImageButton viewSwitchButton() {
        ImageButton b = iconButton(R.drawable.ic_view_list, "Change files view");
        b.setOnClickListener(v -> chooseFilesView());
        return b;
    }

    private void updateFilesStatus() {
        if (filesStatus == null) return;
        String text;
        if (prefs.filesView() == Prefs.FILES_ALL) {
            text = allVideos == null || (scanning && allVideos.isEmpty()) ? "Looking for videos…" : count(allVideos.size(), "video", "videos");
        } else {
            text = scanning && videoFolders.isEmpty() ? "Looking for videos…" : count(videoFolders.size(), "folder with videos", "folders with videos");
        }
        filesStatus.setText(text);
    }

    private static String count(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    /** Scans storage for video folders on the scanner thread; the result replaces the cache. */
    private void rescan() {
        if (scanning || !hasStorageAccess()) return;
        scanning = true;
        lastScan = SystemClock.elapsedRealtime();
        updateFilesStatus();
        List<File> roots = storageRoots();
        scanner.execute(() -> {
            List<MediaFiles.VideoFolder> found = MediaFiles.findVideoFolders(roots);
            String json = MediaFiles.toJson(found);
            main.post(() -> {
                if (destroyed) return;
                scanning = false;
                boolean changed = !json.equals(videoFoldersJson);
                if (changed) {
                    videoFolders = found;
                    videoFoldersJson = json;
                    prefs.setVideoFolders(json);
                    allVideos = null;
                    allVideosGen++;
                }
                // Rebuild only on a real change so the scroll position is kept.
                if (tab == TAB_FILES && prefs.filesView() != Prefs.FILES_BROWSE && folder == null && changed) showTab(TAB_FILES);
                else updateFilesStatus();
            });
        });
    }

    private boolean isInternalRoot(File f) {
        return f.equals(Environment.getExternalStorageDirectory());
    }

    private View buildVideoFolders(LinearLayout box) {
        addScanBar(box);
        final List<MediaFiles.VideoFolder> rows = videoFolders;
        ListView list = listView();
        RowAdapter<MediaFiles.VideoFolder> adapter = new RowAdapter<>(rows, (row, vf) -> {
            row.setIcon(R.drawable.ic_folder, getColor(R.color.accent));
            row.title.setText(isInternalRoot(vf.dir) ? "Internal storage" : vf.dir.getName());
            row.sub.setSingleLine(false);
            row.sub.setMaxLines(3);
            row.sub.setText(vf.dir.getAbsolutePath());
            row.trailing.setText(count(vf.videos, "video", "videos"));
        });
        list.setAdapter(adapter);
        filesAdapter = adapter;
        list.setOnItemClickListener((p, v, pos, id) -> openVideoFolder(rows.get(pos).dir));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            folderMenu(v, rows.get(pos).dir);
            return true;
        });
        if (rows.isEmpty()) box.addView(emptyText(scanning ? "Looking for videos…" : "No videos found on this device.\nTap refresh to look again."));
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return box;
    }

    private void openVideoFolder(File dir) {
        folder = dir;
        prefs.setLastFolder(dir.getAbsolutePath());
        showTab(TAB_FILES);
    }

    private void closeVideoFolder() {
        folder = null;
        prefs.setLastFolder(null);
        showTab(TAB_FILES);
    }

    /** One video folder: only its videos, with the name, full path and Play folder. */
    private View buildVideoFolder(LinearLayout box, File dir) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(12), dp(6));
        ImageButton back = iconButton(R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> closeVideoFolder());
        bar.addView(back);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.text(this, isInternalRoot(dir) ? "Internal storage" : dir.getName(), 17, getColor(R.color.text));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        TextView path = Ui.text(this, dir.getAbsolutePath(), 12, getColor(R.color.text_dim));
        path.setMaxLines(2);
        path.setEllipsize(TextUtils.TruncateAt.START);
        names.addView(name);
        names.addView(path);
        bar.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final List<File> videos = MediaFiles.videoFiles(dir);
        if (!videos.isEmpty()) {
            TextView playAll = Ui.button(this, "▶ Play folder", true);
            playAll.setOnClickListener(v -> play(MediaFiles.videoItems(dir), 0));
            playAll.setOnLongClickListener(v -> {
                folderMenu(v, dir);
                return true;
            });
            bar.addView(playAll);
        }
        box.addView(bar);

        ListView list = listView();
        RowAdapter<File> adapter = new RowAdapter<>(videos, (row, f) -> {
            row.setIcon(R.drawable.ic_movie, getColor(R.color.text));
            row.title.setText(f.getName());
            row.sub.setText(fileInfo(f));
            row.trailing.setText("");
        });
        list.setAdapter(adapter);
        filesAdapter = adapter;
        list.setOnItemClickListener((p, v, pos, id) -> playFrom(videos.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            itemMenu(v, Collections.singletonList(MediaItem.file(videos.get(pos))), 0);
            return true;
        });
        if (videos.isEmpty()) box.addView(emptyText("No videos in this folder any more"));
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return box;
    }

    private String fileInfo(File f) {
        long pos = prefs.resumePosition(f.getAbsolutePath());
        return size(f.length()) + (pos > 0 ? "  ·  resume " + MediaFiles.formatTime(pos) : "");
    }

    /** Queues the video's folder, starting at that video, like PotPlayer. */
    private void playFrom(File video) {
        File dir = video.getParentFile();
        if (dir == null) {
            play(Collections.singletonList(MediaItem.file(video)), 0);
            return;
        }
        List<MediaItem> items = prefs.filesView() == Prefs.FILES_BROWSE ? MediaFiles.folderItems(dir) : MediaFiles.videoItems(dir);
        int start = 0;
        for (int i = 0; i < items.size(); i++) if (items.get(i).uri.equals(video.getAbsolutePath())) start = i;
        if (items.isEmpty()) items = Collections.singletonList(MediaItem.file(video));
        play(items, start);
    }

    private View buildAllVideos(LinearLayout box) {
        addScanBar(box);
        if (allVideos == null) {
            buildAllVideosList();
            box.addView(emptyText("Looking for videos…"));
            return box;
        }
        final List<File> rows = allVideos;
        ListView list = listView();
        RowAdapter<File> adapter = new RowAdapter<>(rows, (row, f) -> {
            row.setIcon(R.drawable.ic_movie, getColor(R.color.text));
            row.title.setText(f.getName());
            row.sub.setSingleLine(false);
            row.sub.setMaxLines(3);
            File parent = f.getParentFile();
            row.sub.setText(fileInfo(f) + (parent == null ? "" : "\n" + parent.getAbsolutePath()));
            row.trailing.setText("");
        });
        list.setAdapter(adapter);
        filesAdapter = adapter;
        list.setOnItemClickListener((p, v, pos, id) -> playFrom(rows.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            itemMenu(v, Collections.singletonList(MediaItem.file(rows.get(pos))), 0);
            return true;
        });
        if (rows.isEmpty()) box.addView(emptyText(scanning ? "Looking for videos…" : "No videos found on this device.\nTap refresh to look again."));
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return box;
    }

    /** Lists every cached folder's videos in the background. */
    private void buildAllVideosList() {
        if (allVideosBuilding) return;
        allVideosBuilding = true;
        final int gen = allVideosGen;
        final List<MediaFiles.VideoFolder> src = new ArrayList<>(videoFolders);
        scanner.execute(() -> {
            List<File> all = new ArrayList<>();
            for (MediaFiles.VideoFolder vf : src) all.addAll(MediaFiles.videoFiles(vf.dir));
            all.sort((a, b) -> {
                int c = MediaFiles.naturalCompare(a.getName(), b.getName());
                return c != 0 ? c : a.getAbsolutePath().compareTo(b.getAbsolutePath());
            });
            main.post(() -> {
                if (destroyed) return;
                allVideosBuilding = false;
                if (gen != allVideosGen) {
                    // A rescan replaced the folders meanwhile; build again from the new ones.
                    if (tab == TAB_FILES && prefs.filesView() == Prefs.FILES_ALL && folder == null) buildAllVideosList();
                    return;
                }
                allVideos = all;
                if (tab == TAB_FILES && prefs.filesView() == Prefs.FILES_ALL && folder == null) showTab(TAB_FILES);
            });
        });
    }

    /** Browse storage: folders plus every playable file, from the storage roots. */
    private View buildBrowse(LinearLayout box) {
        List<File> roots = storageRoots();
        boolean atRoots = folder == null;
        final List<File> entries = atRoots ? roots : MediaFiles.list(folder);

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
            List<MediaItem> here = MediaFiles.folderItems(folder);
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

        ListView list = listView();
        RowAdapter<File> adapter = new RowAdapter<>(entries, (row, f) -> {
            if (atRoots) {
                row.setIcon(R.drawable.ic_folder, getColor(R.color.accent));
                row.title.setText(isInternalRoot(f) ? "Internal storage" : f.getName());
                row.sub.setText(f.getAbsolutePath());
                row.trailing.setText("");
            } else if (f.isDirectory()) {
                row.setIcon(R.drawable.ic_folder, getColor(R.color.accent));
                row.title.setText(f.getName());
                String[] kids = f.list();
                row.sub.setText(kids == null ? "" : kids.length + " items");
                row.trailing.setText("");
            } else {
                boolean audio = MediaFiles.isAudio(f.getName());
                row.setIcon(audio ? R.drawable.ic_music_file : R.drawable.ic_movie, audio ? getColor(R.color.blue) : getColor(R.color.text));
                row.title.setText(f.getName());
                row.sub.setText(fileInfo(f));
                row.trailing.setText("");
            }
        });
        list.setAdapter(adapter);
        filesAdapter = adapter;
        list.setOnItemClickListener((p, v, pos, id) -> {
            File f = entries.get(pos);
            if (atRoots || f.isDirectory()) {
                folder = f;
                prefs.setLastFolder(f.getAbsolutePath());
                showTab(TAB_FILES);
            } else {
                playFrom(f);
            }
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            File f = entries.get(pos);
            if (atRoots) return false;
            if (f.isDirectory()) folderMenu(v, f);
            else itemMenu(v, Collections.singletonList(MediaItem.file(f)), 0);
            return true;
        });
        if (entries.isEmpty()) {
            box.addView(emptyText("No playable files in this folder"));
        }
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return box;
    }

    private void folderMenu(View anchor, File dir) {
        PopupMenu m = new PopupMenu(this, anchor, Gravity.END);
        m.getMenu().add(0, 1, 0, "Play folder");
        m.getMenu().add(0, 2, 0, "Shuffle folder");
        m.getMenu().add(0, 3, 0, "Add folder to queue");
        m.getMenu().add(0, 4, 0, "Save folder as playlist…");
        m.setOnMenuItemClickListener(i -> {
            List<MediaItem> items = prefs.filesView() == Prefs.FILES_BROWSE ? MediaFiles.folderItems(dir) : MediaFiles.videoItems(dir);
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
        if (b >= 1L << 30) return String.format(java.util.Locale.ROOT, "%.1f GB", b / (double) (1L << 30));
        if (b >= 1L << 20) return String.format(java.util.Locale.ROOT, "%.0f MB", b / (double) (1L << 20));
        return Math.max(1, b / 1024) + " KB";
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
        List<PlaylistStore.Playlist> all = playlists.all();
        List<MediaItem> queue = service == null ? new ArrayList<>() : service.queue();

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), dp(4), dp(12), dp(8));
        bar.addView(Ui.text(this, all.size() + " saved playlists", 13, getColor(R.color.text_dim)), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!queue.isEmpty()) {
            TextView save = Ui.button(this, "Save queue", false);
            save.setOnClickListener(v -> Ui.prompt(this, "Save queue as playlist", "Playlist name", "", name -> {
                playlists.save(name, queue);
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
        rows.addAll(all);
        ListView list = listView();
        list.setAdapter(new RowAdapter<>(rows, (row, o) -> {
            if (o instanceof String) {
                row.setIcon(R.drawable.ic_play, getColor(R.color.accent));
                row.title.setText("Now playing queue");
                row.sub.setText(queue.size() + (queue.size() == 1 ? " item · " : " items · ") + new String[]{"play once", "repeat file", "repeat all"}[service.repeatMode()] + (service.shuffle() ? " · shuffle" : ""));
            } else {
                PlaylistStore.Playlist p = (PlaylistStore.Playlist) o;
                row.setIcon(R.drawable.ic_playlist, getColor(R.color.accent));
                row.title.setText(p.name);
                row.sub.setText(p.items.size() + (p.items.size() == 1 ? " item" : " items"));
            }
            row.trailing.setText("");
        }));
        list.setOnItemClickListener((parent, v, pos, id) -> {
            Object o = rows.get(pos);
            if (o instanceof String) PlayerActivity.open(this);
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
            m.setOnMenuItemClickListener(i -> {
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
        if (rows.isEmpty()) box.addView(emptyText("No playlists yet.\nLong-press a file, folder or YouTube result and choose \"Add to playlist\", or save the queue from the player menu."));
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return box;
    }

    private void confirmDelete(String name) {
        Ui.confirm(this, "Delete playlist?", "\"" + name + "\" will be removed. The media files are not touched.", "Delete", () -> {
            playlists.delete(name);
            openPlaylist = null;
            showTab(TAB_PLAYLISTS);
        });
    }

    private View buildPlaylistDetail(PlaylistStore.Playlist p) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(12), dp(8));
        ImageButton back = iconButton(R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> { openPlaylist = null; showTab(TAB_PLAYLISTS); });
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

        ListView list = listView();
        list.setAdapter(new RowAdapter<>(p.items, this::bindMedia));
        list.setOnItemClickListener((parent, v, pos, id) -> play(p.items, pos));
        list.setOnItemLongClickListener((parent, v, pos, id) -> {
            PopupMenu m = new PopupMenu(this, v, Gravity.END);
            m.getMenu().add(0, 1, 0, "Play from here");
            m.getMenu().add(0, 2, 0, "Add to queue");
            m.getMenu().add(0, 3, 0, "Remove from playlist");
            m.setOnMenuItemClickListener(i -> {
                if (i.getItemId() == 1) play(p.items, pos);
                else if (i.getItemId() == 2) enqueue(Collections.singletonList(p.items.get(pos)));
                else {
                    playlists.removeItem(p.name, pos);
                    showTab(TAB_PLAYLISTS);
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
        if (m.kind == MediaItem.YOUTUBE && m.thumb != null) row.setThumb(m.thumb);
        else if (m.kind == MediaItem.STREAM) row.setIcon(R.drawable.ic_link, getColor(R.color.blue));
        else if (MediaFiles.isAudio(m.title)) row.setIcon(R.drawable.ic_music_file, getColor(R.color.blue));
        else row.setIcon(R.drawable.ic_movie, getColor(R.color.text));
        row.title.setText(m.title);
        row.sub.setText(m.subtitle);
        row.trailing.setText(m.durationMs > 0 ? MediaFiles.formatTime(m.durationMs) : "");
    }

    // ---- Streams tab ----------------------------------------------------------------------

    private View buildStreams() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), 0, dp(14), 0);
        EditText url = Ui.input(this, "https://… · rtsp://… · .m3u8 · .mpd · YouTube link", "");
        url.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        url.setImeOptions(EditorInfo.IME_ACTION_GO);
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
        box.addView(row);
        Runnable go = () -> {
            String u = url.getText().toString().trim();
            if (!validUrl(u)) { Ui.toast(this, "Enter a http(s), rtsp, rtmp, mms or udp address"); return; }
            prefs.addHistory(u);
            hideKeyboard(url);
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
            r.setIcon(YouTube.isYouTubeUrl(h) ? R.drawable.ic_play : R.drawable.ic_link, getColor(R.color.accent));
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

    private View buildYouTube() {
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
        box.addView(searchRow);
        Runnable search = () -> {
            String s = q.getText().toString().trim();
            if (s.isEmpty()) return;
            hideKeyboard(q);
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

        TextView note = Ui.text(this, "No account and no API key. Streams play inside Astra; YouTube videos cannot be downloaded.", 12, getColor(R.color.text_dim));
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
            if (prefs.filesView() == Prefs.FILES_BROWSE) goUp();
            else closeVideoFolder();
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
