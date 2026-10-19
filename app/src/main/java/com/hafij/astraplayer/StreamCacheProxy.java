package com.hafij.astraplayer;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cache while playing. The player reads a YouTube video through this proxy on 127.0.0.1
 * while it downloads the video and audio streams into the offline cache, so watching a video
 * also saves it. Requests for bytes already downloaded are served from the file; a seek far
 * past the downloaded part is passed straight through to YouTube so the player never waits
 * long. Files use the same names as {@link CacheService}, which can finish a partial copy.
 */
final class StreamCacheProxy {
    private static final String TAG = "AstraProxy";
    /** Further ahead than this, a read goes straight to YouTube instead of waiting. */
    private static final long WAIT_WINDOW = 3L * 1024 * 1024;
    private static final int MAX_URL_REFRESHES = 3;
    private static final Set<String> ACTIVE = ConcurrentHashMap.newKeySet();

    interface Refresher {
        YouTube.CacheSource fresh() throws Exception;
    }

    /** True while a video id is being cached by a proxy (CacheService waits for it). */
    static boolean active(String id) {
        return ACTIVE.contains(id);
    }

    private final class Track {
        final int index;
        final File target;
        final File part;
        volatile String url;
        volatile long total = -1;
        volatile boolean complete;
        volatile boolean failed;

        Track(int index, File target, String url) {
            this.index = index;
            this.target = target;
            this.part = new File(target.getPath() + ".part");
            this.url = url;
        }

        long available() {
            return complete ? total : part.length();
        }

        File file() {
            return complete ? target : part;
        }
    }

    private final String id;
    private final Track[] tracks;
    private final Refresher refresher;
    private final Runnable onComplete;
    private final AtomicBoolean stop = new AtomicBoolean(false);
    private final ChunkedDownloader downloader = new ChunkedDownloader(YouTube.USER_AGENT);
    private ServerSocket server;

    StreamCacheProxy(String id, File dir, YouTube.CacheSource src, Refresher refresher, Runnable onComplete) {
        this.id = id;
        this.refresher = refresher;
        this.onComplete = onComplete;
        File video = new File(dir, "v" + src.height + "." + src.videoExt);
        if (src.audioUrl == null) {
            tracks = new Track[]{new Track(0, video, src.videoUrl)};
        } else {
            tracks = new Track[]{new Track(0, video, src.videoUrl), new Track(1, new File(dir, "a." + src.audioExt), src.audioUrl)};
        }
    }

    /**
     * Opens the local server and starts downloading. Call off the main thread (it asks
     * YouTube for the stream sizes). Returns the local URLs: video first, then audio or null.
     */
    String[] start() throws IOException {
        ACTIVE.add(id);
        try {
            for (Track t : tracks) {
                File dir = t.target.getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create cache folder");
                if (t.target.exists()) {
                    t.total = t.target.length();
                    t.complete = true;
                } else {
                    t.total = downloader.probeLength(t.url);
                    if (t.total <= 0) throw new IOException("Unknown stream size");
                }
            }
            server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            ACTIVE.remove(id);
            throw e;
        }
        Thread accept = new Thread(this::acceptLoop, "astra-proxy");
        accept.setDaemon(true);
        accept.start();
        for (Track t : tracks) {
            if (t.complete) continue;
            Thread d = new Thread(() -> download(t), "astra-proxy-dl" + t.index);
            d.setDaemon(true);
            d.start();
        }
        checkComplete();
        String base = "http://127.0.0.1:" + server.getLocalPort() + "/";
        return new String[]{base + "0", tracks.length > 1 ? base + "1" : null};
    }

    boolean complete() {
        for (Track t : tracks) if (!t.complete) return false;
        return true;
    }

    /** Stops serving and downloading; downloaded bytes stay on disk for a later resume. */
    void stop() {
        stop.set(true);
        ACTIVE.remove(id);
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    // ---- downloading --------------------------------------------------------------------

    private void download(Track t) {
        int refreshes = 0;
        while (!stop.get()) {
            try {
                downloader.download(t.url, t.part, null, stop);
                synchronized (this) {
                    if (!t.part.renameTo(t.target)) throw new IOException("Cannot finish " + t.target.getName());
                    t.complete = true;
                }
                checkComplete();
                return;
            } catch (ChunkedDownloader.CancelledException e) {
                return;
            } catch (ChunkedDownloader.ExpiredException e) {
                if (++refreshes > MAX_URL_REFRESHES || !refresh()) {
                    t.failed = true;
                    return;
                }
            } catch (IOException e) {
                Log.w(TAG, "download of track " + t.index + " failed", e);
                t.failed = true;
                return;
            }
        }
    }

    /** Fresh googlevideo links after the old ones expired. */
    private synchronized boolean refresh() {
        try {
            YouTube.CacheSource s = refresher.fresh();
            tracks[0].url = s.videoUrl;
            if (tracks.length > 1 && s.audioUrl != null) tracks[1].url = s.audioUrl;
            return true;
        } catch (Exception e) {
            Log.w(TAG, "refresh failed", e);
            return false;
        }
    }

    private void checkComplete() {
        if (!complete() || stop.get()) return;
        ACTIVE.remove(id);
        onComplete.run();
    }

    // ---- serving --------------------------------------------------------------------------

    private void acceptLoop() {
        while (!stop.get()) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> serve(s), "astra-proxy-req");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!stop.get()) Log.w(TAG, "accept failed", e);
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(30000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
            String request = in.readLine();
            if (request == null) return;
            long start = 0;
            boolean ranged = false;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("range:") && lower.contains("bytes=")) {
                    String spec = lower.substring(lower.indexOf("bytes=") + 6).trim();
                    int dash = spec.indexOf('-');
                    if (dash > 0) {
                        start = Long.parseLong(spec.substring(0, dash).trim());
                        ranged = true;
                    }
                }
            }
            String[] parts = request.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            int idx = path.endsWith("/1") ? 1 : 0;
            OutputStream out = s.getOutputStream();
            if (idx >= tracks.length) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                return;
            }
            Track t = tracks[idx];
            long total = t.total;
            if (start >= total) {
                out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + total
                        + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                return;
            }
            String head = (ranged ? "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes " + start + "-" + (total - 1) + "/" + total + "\r\n"
                    : "HTTP/1.1 200 OK\r\n")
                    + "Content-Type: application/octet-stream\r\nAccept-Ranges: bytes\r\nContent-Length: " + (total - start)
                    + "\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            if (!"HEAD".equals(parts[0])) body(t, start, out);
            out.flush();
        } catch (IOException | NumberFormatException e) {
            // The player closed the connection (seek or stop): normal.
        }
    }

    /** Streams bytes [start, total) of a track: from the file when downloaded, else from YouTube. */
    private void body(Track t, long start, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long pos = start;
        while (pos < t.total && !stop.get()) {
            long avail = t.available();
            if (pos < avail) {
                try (RandomAccessFile f = new RandomAccessFile(t.file(), "r")) {
                    f.seek(pos);
                    while (pos < t.available() && !stop.get()) {
                        int n = f.read(buf, 0, (int) Math.min(buf.length, t.available() - pos));
                        if (n <= 0) break;
                        out.write(buf, 0, n);
                        pos += n;
                    }
                }
            } else if (t.failed || pos > avail + WAIT_WINDOW) {
                passThrough(t, pos, out);
                return;
            } else {
                try {
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** Far seek: read straight from YouTube (those bytes are cached later by the downloader). */
    private void passThrough(Track t, long pos, OutputStream out) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(t.url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", YouTube.USER_AGENT);
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Range", "bytes=" + pos + "-");
            int code = c.getResponseCode();
            if (code != 206 && code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                if (code == 200 && pos > 0) {
                    long skip = pos;
                    while (skip > 0) {
                        long k = in.skip(skip);
                        if (k <= 0) throw new IOException("short skip");
                        skip -= k;
                    }
                }
                byte[] buf = new byte[64 * 1024];
                int n;
                while (!stop.get() && (n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        } finally {
            c.disconnect();
        }
    }
}
