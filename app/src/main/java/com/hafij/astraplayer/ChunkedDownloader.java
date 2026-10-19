package com.hafij.astraplayer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resumable download of one large file in bounded HTTP Range requests.
 * googlevideo throttles a single long request to roughly playback speed, but serves short
 * ranges at full speed, so the file is fetched chunk by chunk and appended to the target.
 * The remote total is kept in a "target.total" sidecar so a later resume can tell whether
 * the stream behind the URL is still the same one.
 */
public final class ChunkedDownloader {
    public static final long DEFAULT_CHUNK = 8L * 1024 * 1024;

    /** Thrown when the URL stopped working (HTTP 403/404/410), so the caller should resolve a fresh URL and call download again with the same target. */
    public static final class ExpiredException extends IOException {
        public ExpiredException(String msg) {
            super(msg);
        }
    }

    /** Thrown when the cancel flag was set. */
    public static final class CancelledException extends IOException {
        public CancelledException() {
            super("cancelled");
        }
    }

    public interface Progress {
        void onProgress(long done, long total);
    }

    private static final int BUFFER = 64 * 1024;
    private static final int MAX_RETRIES = 3;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final long PROGRESS_INTERVAL_NS = 250_000_000L;

    private static final int DONE = 0;
    private static final int MORE = 1;

    private final String userAgent;
    private long chunk = DEFAULT_CHUNK;
    private long retryDelayMs = 1500;

    public ChunkedDownloader(String userAgent) {
        this.userAgent = userAgent;
    }

    /** Overrides the Range size; tests use small chunks to exercise the loop. */
    public ChunkedDownloader chunkSize(long bytes) {
        if (bytes <= 0) throw new IllegalArgumentException("chunk size must be positive");
        chunk = bytes;
        return this;
    }

    /** Backoff base; attempt n waits n times this. */
    public ChunkedDownloader retryDelayMs(long ms) {
        retryDelayMs = Math.max(0, ms);
        return this;
    }

    /** Per-call mutable state, so one downloader can serve several threads. */
    private static final class Run {
        final Progress progress;
        final AtomicBoolean cancel;
        long lastReport = Long.MIN_VALUE;
        /** The previous 206 had an unknown total ("/*") and was full; a 416 next means the end. */
        boolean openEndedFull;

        Run(Progress progress, AtomicBoolean cancel) {
            this.progress = progress;
            this.cancel = cancel;
        }

        void tick(long done, long total) {
            if (progress == null) return;
            long now = System.nanoTime();
            if (lastReport != Long.MIN_VALUE && now - lastReport < PROGRESS_INTERVAL_NS) return;
            lastReport = now;
            progress.onProgress(done, total);
        }

        void finish(long done, long total) {
            if (progress != null) progress.onProgress(done, total);
        }

        void checkCancel() throws CancelledException {
            if (cancel != null && cancel.get()) throw new CancelledException();
        }
    }

    /** Downloads url into target, continuing from target.length(). Returns the final file length. */
    public long download(String url, File target, Progress progress, AtomicBoolean cancel) throws IOException {
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        File sidecar = sidecarOf(target);
        Run run = new Run(progress, cancel);
        while (fetchWithRetry(url, target, sidecar, run) == MORE) {
            // next chunk
        }
        if (sidecar.exists()) sidecar.delete();
        long len = target.length();
        run.finish(len, len);
        return len;
    }

    /** Total size of the remote file via a "Range: bytes=0-0" request (Content-Range), or Content-Length; -1 if unknown. Throws ExpiredException on 403/404/410. */
    public long probeLength(String url) throws IOException {
        HttpURLConnection c = open(url);
        c.setRequestProperty("Range", "bytes=0-0");
        try {
            int code = c.getResponseCode();
            if (isExpired(code)) throw new ExpiredException("HTTP " + code);
            if (code == 206 || code == 416) {
                long[] cr = parseContentRange(c.getHeaderField("Content-Range"));
                return cr == null ? -1 : cr[2];
            }
            if (code == 200) {
                long len = c.getContentLengthLong();
                return len >= 0 ? len : -1;
            }
            return -1;
        } finally {
            c.disconnect();
        }
    }

    /** One chunk, retried on transient failures; each retry resumes from wherever the file got to. */
    private int fetchWithRetry(String url, File target, File sidecar, Run run) throws IOException {
        for (int attempt = 0; ; attempt++) {
            run.checkCancel();
            try {
                return fetchChunk(url, target, sidecar, run);
            } catch (ExpiredException | CancelledException e) {
                throw e;
            } catch (IOException e) {
                if (attempt >= MAX_RETRIES) throw e;
                sleep(retryDelayMs * (attempt + 1));
            }
        }
    }

    private int fetchChunk(String url, File target, File sidecar, Run run) throws IOException {
        long have = target.length();
        long known = readTotal(sidecar);
        if (known >= 0 && have == known) return DONE;
        if (known >= 0 && have > known) {
            // More bytes than the stream has: the file is not what we think, start over.
            truncate(target);
            have = 0;
        }
        long end = have + chunk - 1;
        HttpURLConnection c = open(url);
        c.setRequestProperty("Range", "bytes=" + have + "-" + end);
        try {
            int code = c.getResponseCode();
            if (isExpired(code)) throw new ExpiredException("HTTP " + code);

            if (code == 416) {
                long[] cr = parseContentRange(c.getHeaderField("Content-Range"));
                long t = cr == null ? -1 : cr[2];
                if ((known >= 0 && have == known) || (t >= 0 && have == t)) return DONE;
                if (known < 0 && t < 0 && run.openEndedFull) return DONE;
                if (have > 0) {
                    // Our offset lies past the end of whatever the URL serves now.
                    truncate(target);
                    deleteQuietly(sidecar);
                    return MORE;
                }
                throw new IOException("HTTP 416 at offset 0");
            }

            if (code == 200) {
                // Range ignored: the body is the whole file, so it must never be appended onto old bytes.
                long len = c.getContentLengthLong();
                if (have > 0) truncate(target);
                if (len >= 0) writeTotal(sidecar, len);
                else deleteQuietly(sidecar);
                try (InputStream in = c.getInputStream()) {
                    long wrote = copy(in, target, 0, len, len, run);
                    if (len >= 0 && wrote != len) throw new IOException("short body: " + wrote + " of " + len);
                }
                return DONE;
            }

            if (code == 206) {
                long[] cr = parseContentRange(c.getHeaderField("Content-Range"));
                if (cr == null || cr[0] < 0) throw new IOException("206 without a usable Content-Range");
                long total = cr[2];
                if (known >= 0 && total >= 0 && total != known) {
                    // The stream behind the URL changed (other format or re-encode); old bytes are useless.
                    truncate(target);
                    writeTotal(sidecar, total);
                    if (have > 0) return MORE;
                } else if (total >= 0 && total != known) {
                    writeTotal(sidecar, total);
                }
                long start = cr[0];
                if (start > have) throw new IOException("server skipped to " + start + ", wanted " + have);
                // A range ending before our offset would give a negative limit, which copy() reads as "until EOF".
                if (cr[1] < have) throw new IOException("server sent " + start + "-" + cr[1] + ", wanted " + have);
                long expected = cr[1] - start + 1;
                try (InputStream in = c.getInputStream()) {
                    skipFully(in, have - start);
                    expected -= have - start;
                    long wrote = copy(in, target, have, total, expected, run);
                    if (wrote != expected) throw new IOException("short chunk: " + wrote + " of " + expected);
                    long now = have + wrote;
                    if (total >= 0) {
                        run.openEndedFull = false;
                        return now >= total ? DONE : MORE;
                    }
                    // Unknown total: a range shorter than asked marks the end.
                    run.openEndedFull = cr[1] >= end;
                    return cr[1] < end ? DONE : MORE;
                }
            }

            throw new IOException("HTTP " + code);
        } finally {
            c.disconnect();
        }
    }

    /** Appends up to limit bytes (all when limit < 0) to target; the file is flushed and closed on every exit. */
    private static long copy(InputStream in, File target, long base, long total, long limit, Run run)
            throws IOException {
        byte[] buf = new byte[BUFFER];
        long wrote = 0;
        try (FileOutputStream out = new FileOutputStream(target, true)) {
            try {
                while (limit < 0 || wrote < limit) {
                    run.checkCancel();
                    int want = limit < 0 ? buf.length : (int) Math.min(buf.length, limit - wrote);
                    int n = in.read(buf, 0, want);
                    if (n < 0) break;
                    out.write(buf, 0, n);
                    wrote += n;
                    run.tick(base + wrote, total);
                }
            } finally {
                out.flush();
            }
        }
        return wrote;
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setUseCaches(false);
        if (userAgent != null) c.setRequestProperty("User-Agent", userAgent);
        // Byte offsets only make sense on the raw body, never on a transparently gunzipped one.
        c.setRequestProperty("Accept-Encoding", "identity");
        return c;
    }

    private static boolean isExpired(int code) {
        return code == 403 || code == 404 || code == 410;
    }

    /** Parses "bytes a-b/T", "bytes *&#47;T" or "bytes a-b/*" into {a, b, T}; unknown parts are -1. */
    static long[] parseContentRange(String h) {
        if (h == null) return null;
        String s = h.trim();
        if (s.regionMatches(true, 0, "bytes", 0, 5)) s = s.substring(5).trim();
        int slash = s.indexOf('/');
        if (slash < 0) return null;
        String range = s.substring(0, slash).trim();
        String tot = s.substring(slash + 1).trim();
        try {
            long total = "*".equals(tot) ? -1 : Long.parseLong(tot);
            long a = -1;
            long b = -1;
            if (!"*".equals(range)) {
                int dash = range.indexOf('-');
                if (dash < 0) return null;
                a = Long.parseLong(range.substring(0, dash).trim());
                b = Long.parseLong(range.substring(dash + 1).trim());
                if (b < a) return null;
            }
            return new long[]{a, b, total};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        byte[] buf = null;
        while (n > 0) {
            long s = in.skip(n);
            if (s > 0) {
                n -= s;
                continue;
            }
            if (buf == null) buf = new byte[BUFFER];
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) throw new IOException("body ended while skipping");
            n -= r;
        }
    }

    private void sleep(long ms) throws InterruptedIOException {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted during retry backoff");
        }
    }

    static File sidecarOf(File target) {
        return new File(target.getPath() + ".total");
    }

    private static long readTotal(File sidecar) {
        if (!sidecar.isFile()) return -1;
        try {
            String s = new String(Files.readAllBytes(sidecar.toPath()), StandardCharsets.UTF_8).trim();
            long v = Long.parseLong(s);
            return v >= 0 ? v : -1;
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    private static void writeTotal(File sidecar, long total) throws IOException {
        Files.write(sidecar.toPath(), Long.toString(total).getBytes(StandardCharsets.UTF_8));
    }

    private static void truncate(File f) throws IOException {
        new FileOutputStream(f, false).close();
    }

    private static void deleteQuietly(File f) {
        if (f.exists()) f.delete();
    }
}
