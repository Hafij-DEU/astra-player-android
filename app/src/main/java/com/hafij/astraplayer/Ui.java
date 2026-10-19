package com.hafij.astraplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Small view and dialog helpers shared by the screens. */
public final class Ui {
    private Ui() {
    }


    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService THUMBS = Executors.newFixedThreadPool(3);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    /**
     * Forces light or dark for one activity. Call from attachBaseContext after super. The
     * override is an empty delta carrying only uiMode, so rotation, font size and the rest
     * keep following the system.
     */
    public static void applyTheme(Activity a, int theme) {
        if (theme == Prefs.THEME_SYSTEM) return;
        Configuration c = new Configuration();
        c.uiMode = theme == Prefs.THEME_DARK ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        a.applyOverrideConfiguration(c);
    }

    private static final int[] PALETTE = {R.color.c_violet, R.color.c_teal, R.color.c_rose, R.color.c_blue,
            R.color.c_green, R.color.c_orange, R.color.c_pink, R.color.c_indigo};

    /** A stable colour from the palette for a name (folders keep their colour). */
    public static int paletteColor(Context c, String key) {
        int h = key == null ? 0 : key.hashCode();
        return c.getColor(PALETTE[Math.floorMod(h, PALETTE.length)]);
    }

    /** The colour with a low alpha, for soft chip and icon backgrounds. */
    public static int soft(int color) {
        return (color & 0x00FFFFFF) | 0x30000000;
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static void toast(Context c, String text) {
        Toast.makeText(c, text, Toast.LENGTH_SHORT).show();
    }

    public static AlertDialog.Builder dialog(Context c) {
        return new Builder(c);
    }

    /** Dialogs open at the bottom of the screen in one-hand mode, within thumb reach. */
    private static final class Builder extends AlertDialog.Builder {
        private final boolean bottom;

        Builder(Context c) {
            super(c, R.style.Theme_Astra_Dialog);
            bottom = new Prefs(c).oneHand() && !(c instanceof PlayerActivity);
        }

        @Override
        public AlertDialog create() {
            AlertDialog d = super.create();
            if (bottom && d.getWindow() != null) {
                d.getWindow().setGravity(Gravity.BOTTOM);
                android.view.WindowManager.LayoutParams lp = d.getWindow().getAttributes();
                lp.y = dp(getContext(), 12);
                d.getWindow().setAttributes(lp);
            }
            return d;
        }
    }

    public static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    public static TextView button(Context c, String label, boolean filled) {
        TextView b = new TextView(c);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(14);
        b.setMinHeight(dp(c, 42));
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        b.setBackgroundResource(filled ? R.drawable.bg_button : R.drawable.bg_button_ghost);
        b.setTextColor(filled ? 0xFF101013 : c.getColor(R.color.accent));
        if (filled) b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    public static EditText input(Context c, String hint, String value) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextColor(c.getColor(R.color.text));
        e.setHintTextColor(c.getColor(R.color.text_dim));
        e.setBackgroundResource(R.drawable.bg_input);
        e.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        return e;
    }

    /** Wraps a view with dialog padding. */
    public static View padded(Context c, View v) {
        FrameLayout f = new FrameLayout(c);
        int p = dp(c, 20);
        f.setPadding(p, dp(c, 8), p, 0);
        f.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return f;
    }

    public static void prompt(Activity a, String title, String hint, String value, Consumer<String> done) {
        EditText e = input(a, hint, value);
        dialog(a).setTitle(title).setView(padded(a, e))
                .setPositiveButton("OK", (d, w) -> {
                    String s = e.getText().toString().trim();
                    if (!s.isEmpty()) done.accept(s);
                })
                .setNegativeButton("Cancel", null)
                .show();
        e.requestFocus();
    }

    public static void confirm(Activity a, String title, String message, String yes, Runnable r) {
        dialog(a).setTitle(title).setMessage(message)
                .setPositiveButton(yes, (d, w) -> r.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Picks a saved playlist (or a new one) and passes its name on. */
    public static void choosePlaylist(Activity a, PlaylistStore store, Consumer<String> done) {
        List<PlaylistStore.Playlist> all = store.all();
        String[] names = new String[all.size() + 1];
        names[0] = "+ New playlist…";
        for (int i = 0; i < all.size(); i++) names[i + 1] = all.get(i).name + "  (" + all.get(i).items.size() + ")";
        dialog(a).setTitle("Add to playlist").setItems(names, (d, w) -> {
            if (w == 0) prompt(a, "New playlist", "Name", "", done);
            else done.accept(all.get(w - 1).name);
        }).show();
    }

    /** A two-line list row with an optional leading icon or thumbnail. */
    public static final class Row extends LinearLayout {
        public final ImageView icon;
        public final TextView title;
        public final TextView sub;
        public final TextView trailing;

        public Row(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
            setBackgroundResource(R.drawable.bg_card);
            icon = new ImageView(c);
            icon.setBackgroundResource(R.drawable.bg_thumb);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            icon.setClipToOutline(true);
            addView(icon, new LayoutParams(dp(c, 44), dp(c, 44)));
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(VERTICAL);
            title = text(c, "", 15, c.getColor(R.color.text));
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sub = text(c, "", 12, c.getColor(R.color.text_dim));
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(title);
            texts.addView(sub);
            LayoutParams lp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMarginStart(dp(c, 12));
            addView(texts, lp);
            trailing = text(c, "", 12, c.getColor(R.color.text_dim));
            addView(trailing);
            more = new ImageButton(c);
            more.setImageResource(R.drawable.ic_more);
            more.setBackgroundResource(R.drawable.bg_round_btn);
            more.setContentDescription("More options");
            more.setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 8));
            // Not focusable, so the row itself still receives list item clicks.
            more.setFocusable(false);
            more.setVisibility(GONE);
            LayoutParams mlp = new LayoutParams(dp(c, 36), dp(c, 36));
            mlp.setMarginStart(dp(c, 4));
            addView(more, mlp);
        }

        public final ImageButton more;

        /** Shows the ⋮ button with the given action, or hides it when {@code onMore} is null. */
        public void setMore(View.OnClickListener onMore) {
            more.setVisibility(onMore == null ? GONE : VISIBLE);
            more.setOnClickListener(onMore);
        }

        /** A local video: 16:9 thumbnail (decoded in the background) and its duration. */
        public void setVideo(File f, boolean thumbnails) {
            icon.setTag(null);
            icon.setBackgroundTintList(null);
            icon.setImageResource(R.drawable.ic_movie);
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(getContext().getColor(R.color.text_dim)));
            icon.setScaleType(ImageView.ScaleType.CENTER);
            icon.getLayoutParams().width = dp(getContext(), 80);
            icon.requestLayout();
            trailing.setText("");
            Thumbs.load(icon, trailing, f, thumbnails);
        }

        public void setIcon(int res, int tint) {
            icon.setTag(null);
            icon.setTag(R.id.thumb_key, null);
            trailing.setTag(R.id.thumb_key, null);
            icon.setPadding(0, 0, 0, 0);
            icon.setBackgroundTintList(android.content.res.ColorStateList.valueOf(soft(tint)));
            icon.setImageResource(res);
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(tint));
            icon.setScaleType(ImageView.ScaleType.CENTER);
            icon.getLayoutParams().width = dp(getContext(), 44);
            icon.requestLayout();
        }

        /** Loads a remote thumbnail (16:9) into the icon slot. */
        /** A local song: embedded album art when the file has one, otherwise a coloured note. */
        public void setSong(File f, boolean art) {
            setIcon(R.drawable.ic_music_file, paletteColor(getContext(), f.getParent()));
            trailing.setText("");
            Thumbs.load(icon, trailing, f, art);
        }

        public void setThumb(String url) {
            icon.setBackgroundTintList(null);
            icon.setTag(R.id.thumb_key, null);
            trailing.setTag(R.id.thumb_key, null);
            icon.setImageTintList(null);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            icon.getLayoutParams().width = dp(getContext(), 80);
            icon.requestLayout();
            loadThumb(icon, url);
        }
    }

    public static void loadThumb(ImageView view, String url) {
        view.setTag(url);
        view.setImageDrawable(null);
        if (url == null) return;
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        THUMBS.execute(() -> {
            Bitmap b = null;
            try {
                if (url.startsWith("/")) {
                    // A thumbnail saved with an offline copy.
                    b = BitmapFactory.decodeFile(url);
                } else {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    try (InputStream in = c.getInputStream()) {
                        b = BitmapFactory.decodeStream(in);
                    }
                }
            } catch (Exception ignored) {
            }
            if (b == null) return;
            CACHE.put(url, b);
            Bitmap fb = b;
            MAIN.post(() -> {
                if (url.equals(view.getTag())) view.setImageBitmap(fb);
            });
        });
    }
}
