package com.hafij.astraplayer;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Things the Files tab can do to files on the device: delete, rename, share, hide, details. */
public final class FileActions {
    private FileActions() {
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ---- hide -------------------------------------------------------------------------

    public static void setHidden(Prefs prefs, File f, boolean hidden) {
        Set<String> paths = prefs.hiddenPaths();
        if (hidden) paths.add(f.getAbsolutePath());
        else paths.remove(f.getAbsolutePath());
        prefs.setHiddenPaths(paths);
    }

    // ---- delete -----------------------------------------------------------------------

    /**
     * Asks before permanently deleting {@code files} (and, if ticked, their subtitle files),
     * then deletes them in the background and tells the media library.
     */
    public static void confirmDelete(Activity a, List<File> files, String what, PlaybackService service, Runnable done) {
        if (files.isEmpty()) return;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        String msg = files.size() == 1
                ? "\"" + files.get(0).getName() + "\" will be permanently deleted from your device."
                : files.size() + " videos in " + what + " will be permanently deleted from your device. Other files in the folder are not touched.";
        TextView t = Ui.text(a, msg + "\n\nThis cannot be undone.", 14, a.getColor(R.color.text));
        box.addView(t);
        CheckBox subs = new CheckBox(a);
        subs.setText("Also delete matching subtitle files");
        subs.setTextColor(a.getColor(R.color.text));
        subs.setChecked(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, 8);
        box.addView(subs, lp);
        Ui.dialog(a).setTitle("Delete from device?").setView(Ui.padded(a, box))
                .setPositiveButton("Delete", (d, w) -> delete(a, files, subs.isChecked(), service, done))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void delete(Activity a, List<File> files, boolean withSubs, PlaybackService service, Runnable done) {
        // Take deleted files out of the queue first, so the player never opens a missing file.
        if (service != null) {
            List<MediaItem> q = service.queue();
            for (int i = q.size() - 1; i >= 0; i--) {
                for (File f : files) {
                    if (q.get(i).uri.equals(f.getAbsolutePath())) {
                        service.removeFromQueue(i);
                        break;
                    }
                }
            }
        }
        Prefs prefs = new Prefs(a);
        new Thread(() -> {
            List<String> changed = new ArrayList<>();
            int deleted = 0, failed = 0;
            for (File f : files) {
                List<File> extra = withSubs ? MediaFiles.matchingSubtitles(f) : new ArrayList<>();
                Thumbs.forget(f);
                if (f.delete()) {
                    deleted++;
                    changed.add(f.getAbsolutePath());
                    prefs.saveResume(f.getAbsolutePath(), 0);
                    for (File s : extra) if (s.delete()) changed.add(s.getAbsolutePath());
                } else {
                    failed++;
                }
            }
            // Remove the folder too when nothing is left in it.
            File dir = files.get(0).getParentFile();
            if (files.size() > 1 && dir != null) {
                String[] left = dir.list();
                if (left != null && left.length == 0 && dir.delete()) changed.add(dir.getAbsolutePath());
            }
            if (!changed.isEmpty()) MediaScannerConnection.scanFile(a.getApplicationContext(), changed.toArray(new String[0]), null, null);
            final int ok = deleted, bad = failed;
            MAIN.post(() -> {
                if (bad > 0) Ui.toast(a, "Deleted " + ok + ", could not delete " + bad + " (no permission or file in use)");
                else Ui.toast(a, ok == 1 ? "Deleted" : "Deleted " + ok + " videos");
                done.run();
            });
        }, "astra-delete").start();
    }

    // ---- rename -----------------------------------------------------------------------

    public static void rename(Activity a, File f, Runnable done) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        Ui.prompt(a, "Rename", "New name", base, typed -> {
            String clean = typed.trim();
            if (clean.isEmpty() || clean.contains("/") || clean.equals(".") || clean.equals("..")) {
                Ui.toast(a, "That name cannot be used");
                return;
            }
            if (clean.equals(base)) return;
            File dir = f.getParentFile();
            File target = new File(dir, clean + ext);
            if (target.exists()) {
                Ui.toast(a, "A file called \"" + target.getName() + "\" already exists");
                return;
            }
            List<File> subs = MediaFiles.matchingSubtitles(f);
            Thumbs.forget(f);
            if (!f.renameTo(target)) {
                Ui.toast(a, "Could not rename (no permission?)");
                return;
            }
            List<String> changed = new ArrayList<>();
            changed.add(f.getAbsolutePath());
            changed.add(target.getAbsolutePath());
            // Keep subtitles paired: Movie.en.srt becomes New name.en.srt.
            for (File s : subs) {
                String suffix = s.getName().substring(base.length());
                File ns = new File(dir, clean + suffix);
                if (!ns.exists() && s.renameTo(ns)) {
                    changed.add(s.getAbsolutePath());
                    changed.add(ns.getAbsolutePath());
                }
            }
            Prefs prefs = new Prefs(a);
            long pos = prefs.resumePosition(f.getAbsolutePath());
            if (pos > 0) {
                prefs.saveResume(f.getAbsolutePath(), 0);
                prefs.saveResume(target.getAbsolutePath(), pos);
            }
            if (prefs.hiddenPaths().contains(f.getAbsolutePath())) {
                setHidden(prefs, f, false);
                setHidden(prefs, target, true);
            }
            MediaScannerConnection.scanFile(a.getApplicationContext(), changed.toArray(new String[0]), null, null);
            Ui.toast(a, "Renamed to " + target.getName());
            done.run();
        });
    }

    // ---- share ------------------------------------------------------------------------

    /** Shares through the media library's content URI, so no FileProvider is needed. */
    public static void share(Activity a, File f) {
        String mime = MediaFiles.isAudio(f.getName()) ? "audio/*" : "video/*";
        MediaScannerConnection.scanFile(a.getApplicationContext(), new String[]{f.getAbsolutePath()}, new String[]{null}, (path, uri) -> MAIN.post(() -> {
            if (uri == null) {
                Ui.toast(a, "This file cannot be shared");
                return;
            }
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                a.startActivity(Intent.createChooser(send, "Share " + f.getName()));
            } catch (Exception e) {
                Ui.toast(a, "No app can receive this file");
            }
        }));
    }

    // ---- details ----------------------------------------------------------------------

    public static void details(Activity a, File f) {
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            line(sb, "Name", f.getName());
            line(sb, "Folder", f.getParent());
            line(sb, "Size", size(f.length()));
            line(sb, "Modified", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(f.lastModified())));
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(f.getAbsolutePath());
                String dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (dur != null) line(sb, "Duration", MediaFiles.formatTime(Long.parseLong(dur)));
                String w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                if (w != null && h != null) line(sb, "Resolution", w + " × " + h);
                String br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
                if (br != null) line(sb, "Bitrate", String.format(Locale.ROOT, "%.0f kbit/s", Long.parseLong(br) / 1000.0));
                String mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
                if (mime != null) line(sb, "Format", mime);
            } catch (Exception e) {
                line(sb, "Media info", "not readable by Android (Astra may still play it)");
            } finally {
                try {
                    r.release();
                } catch (Exception ignored) {
                }
            }
            List<File> subs = MediaFiles.findSubtitles(f);
            if (!subs.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (File s : subs) names.append(names.length() > 0 ? ", " : "").append(s.getName());
                line(sb, "Subtitles", names.toString());
            }
            long resume = new Prefs(a).resumePosition(f.getAbsolutePath());
            if (resume > 0) line(sb, "Resume at", MediaFiles.formatTime(resume));
            MAIN.post(() -> {
                if (a.isFinishing()) return;
                TextView t = Ui.text(a, sb.toString().trim(), 14, a.getColor(R.color.text));
                t.setTextIsSelectable(true);
                t.setLineSpacing(0, 1.2f);
                Ui.dialog(a).setTitle("Details").setView(Ui.padded(a, t)).setPositiveButton("OK", null).show();
            });
        }, "astra-details").start();
    }

    private static void line(StringBuilder sb, String k, String v) {
        sb.append(k).append(": ").append(v).append('\n');
    }

    public static String size(long b) {
        if (b >= 1L << 30) return String.format(Locale.ROOT, "%.1f GB", b / (double) (1L << 30));
        if (b >= 1L << 20) return String.format(Locale.ROOT, "%.0f MB", b / (double) (1L << 20));
        return Math.max(1, b / 1024) + " KB";
    }
}
