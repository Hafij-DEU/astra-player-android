package com.hafij.astraplayer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ChunkedDownloaderTest {
    private static final int CHUNK = 64 * 1024;
    private static final String UA = "AstraTest/1.0";
    private static final int STALL_PIECE = 1000;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private HttpServer server;
    private String url;

    /** Served bytes; swapped by the "stream changed" test. */
    private volatile byte[] data = bytes(300_000, 7);
    private volatile boolean ignoreRange;
    /** Requests numbered above this get 403; -1 disables. */
    private volatile int forbidAfter = -1;
    /** This many upcoming requests get 503, once more than fail503After requests were served. */
    private final AtomicInteger fail503 = new AtomicInteger();
    private volatile int fail503After;
    /** This request number sends a little of its body, sets cancelFlag, sends a little more, then stalls until released. */
    private volatile int stallAt = -1;
    private final CountDownLatch stallRelease = new CountDownLatch(1);
    /** Set this flag once the given number of requests has been served; -1 disables. */
    private volatile int cancelAfter = -1;
    private volatile AtomicBoolean cancelFlag;

    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> ranges = Collections.synchronizedList(new ArrayList<>());
    private final List<String> agents = Collections.synchronizedList(new ArrayList<>());

    @Before
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v", this::handle);
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v";
    }

    @After
    public void stop() {
        stallRelease.countDown();
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            int n = requests.incrementAndGet();
            String range = ex.getRequestHeaders().getFirst("Range");
            ranges.add(range);
            agents.add(ex.getRequestHeaders().getFirst("User-Agent"));
            if (forbidAfter >= 0 && n > forbidAfter) {
                ex.sendResponseHeaders(403, -1);
                return;
            }
            if (n > fail503After && fail503.get() > 0 && fail503.getAndDecrement() > 0) {
                ex.sendResponseHeaders(503, -1);
                return;
            }
            if (cancelAfter >= 0 && n > cancelAfter && cancelFlag != null) cancelFlag.set(true);
            byte[] d = data;
            if (range == null || ignoreRange) {
                send(ex, 200, d, 0, d.length);
                return;
            }
            String spec = range.substring("bytes=".length());
            int dash = spec.indexOf('-');
            long a = Long.parseLong(spec.substring(0, dash));
            long b = dash == spec.length() - 1 ? d.length - 1 : Long.parseLong(spec.substring(dash + 1));
            if (a >= d.length) {
                ex.getResponseHeaders().set("Content-Range", "bytes */" + d.length);
                ex.sendResponseHeaders(416, -1);
                return;
            }
            b = Math.min(b, d.length - 1);
            ex.getResponseHeaders().set("Content-Range", "bytes " + a + "-" + b + "/" + d.length);
            if (n == stallAt) {
                stall(ex, d, (int) a, (int) (b - a + 1));
                return;
            }
            send(ex, 206, d, (int) a, (int) (b - a + 1));
        } finally {
            ex.close();
        }
    }

    private static void send(HttpExchange ex, int code, byte[] d, int off, int len) throws IOException {
        ex.sendResponseHeaders(code, len);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(d, off, len);
        }
    }

    /**
     * The second piece is written only after the cancel flag is set, so the client must see the flag
     * inside its copy loop; a loop that never checked would block here until the latch times out
     * and then receive the full chunk.
     */
    private void stall(HttpExchange ex, byte[] d, int off, int len) throws IOException {
        ex.sendResponseHeaders(206, len);
        OutputStream os = ex.getResponseBody();
        os.write(d, off, STALL_PIECE);
        os.flush();
        cancelFlag.set(true);
        os.write(d, off + STALL_PIECE, STALL_PIECE);
        os.flush();
        try {
            stallRelease.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // A client that cancelled has hung up by now, so this write usually fails; that is fine.
        os.write(d, off + 2 * STALL_PIECE, len - 2 * STALL_PIECE);
        os.close();
    }

    private static byte[] bytes(int n, int seed) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * 31 + seed + (i >> 8));
        return b;
    }

    private ChunkedDownloader dl() {
        return new ChunkedDownloader(UA).chunkSize(CHUNK).retryDelayMs(5);
    }

    private static void writePrefix(File f, byte[] src, int n) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(src, 0, n);
        }
    }

    private static File sidecar(File f) {
        return new File(f.getPath() + ".total");
    }

    @Test
    public void freshDownloadInChunks() throws IOException {
        File f = new File(tmp.getRoot(), "a.mp4");
        long[] last = {-2, -2};
        long len = dl().download(url, f, (done, total) -> {
            last[0] = done;
            last[1] = total;
        }, new AtomicBoolean());
        assertEquals(data.length, len);
        assertArrayEquals(data, Files.readAllBytes(f.toPath()));
        int expectedRequests = (data.length + CHUNK - 1) / CHUNK;
        assertEquals(expectedRequests, requests.get());
        assertEquals("bytes=0-" + (CHUNK - 1), ranges.get(0));
        assertEquals("bytes=" + CHUNK + "-" + (2 * CHUNK - 1), ranges.get(1));
        assertEquals(UA, agents.get(0));
        assertEquals(data.length, last[0]);
        assertEquals(data.length, last[1]);
        assertFalse(sidecar(f).exists());
    }

    @Test
    public void resumesFromPartialFile() throws IOException {
        File f = new File(tmp.getRoot(), "b.mp4");
        int k = 100_000;
        writePrefix(f, data, k);
        long len = dl().download(url, f, null, new AtomicBoolean());
        assertEquals(data.length, len);
        assertEquals("bytes=" + k + "-" + (k + CHUNK - 1), ranges.get(0));
        assertArrayEquals(data, Files.readAllBytes(f.toPath()));
        assertFalse(sidecar(f).exists());
    }

    @Test
    public void serverIgnoringRangeFresh() throws IOException {
        ignoreRange = true;
        File f = new File(tmp.getRoot(), "c.mp4");
        long len = dl().download(url, f, null, new AtomicBoolean());
        assertEquals(data.length, len);
        assertEquals(1, requests.get());
        assertArrayEquals(data, Files.readAllBytes(f.toPath()));
        assertFalse(sidecar(f).exists());
    }

    @Test
    public void serverIgnoringRangeOnResumeRestartsFromZero() throws IOException {
        ignoreRange = true;
        File f = new File(tmp.getRoot(), "d.mp4");
        writePrefix(f, bytes(120_000, 99), 120_000);
        long len = dl().download(url, f, null, new AtomicBoolean());
        assertEquals(data.length, len);
        assertEquals(1, requests.get());
        assertArrayEquals(data, Files.readAllBytes(f.toPath()));
    }

    @Test
    public void changedTotalRestartsDownload() throws IOException {
        File f = new File(tmp.getRoot(), "e.mp4");
        AtomicBoolean cancel = new AtomicBoolean();
        cancelFlag = cancel;
        cancelAfter = 2;
        try {
            dl().download(url, f, null, cancel);
            fail("expected cancel");
        } catch (ChunkedDownloader.CancelledException expected) {
        }
        assertTrue(f.length() > 0);
        assertTrue(f.length() < data.length);
        assertEquals(Long.toString(data.length),
                new String(Files.readAllBytes(sidecar(f).toPath()), StandardCharsets.UTF_8).trim());

        byte[] other = bytes(250_000, 42);
        data = other;
        cancelAfter = -1;
        cancel.set(false);
        long len = dl().download(url, f, null, cancel);
        assertEquals(other.length, len);
        assertArrayEquals(other, Files.readAllBytes(f.toPath()));
        assertFalse(sidecar(f).exists());
    }

    @Test
    public void forbiddenThrowsExpiredThenResumeCompletes() throws IOException {
        File f = new File(tmp.getRoot(), "g.mp4");
        forbidAfter = 2;
        try {
            dl().download(url, f, null, new AtomicBoolean());
            fail("expected ExpiredException");
        } catch (ChunkedDownloader.ExpiredException expected) {
        }
        assertEquals(2L * CHUNK, f.length());
        assertEquals(3, requests.get());

        forbidAfter = -1;
        ranges.clear();
        long len = dl().download(url, f, null, new AtomicBoolean());
        assertEquals(data.length, len);
        assertEquals("bytes=" + (2 * CHUNK) + "-" + (3 * CHUNK - 1), ranges.get(0));
        assertArrayEquals(data, Files.readAllBytes(f.toPath()));
        assertFalse(sidecar(f).exists());
    }

    @Test
    public void transientErrorsRetryTheSameChunk() throws IOException {
        File f = new File(tmp.getRoot(), "h.mp4");
        // First chunk succeeds, the next two requests fail, so the retries must ask for chunk 2 again.
        fail503After = 1;
        fail503.set(2);
        long len = dl().download(url, f, null, new AtomicBoolean());
        assertEquals(data.length, len);
        assertArrayEquals(data, Files.readAllBytes(f.toPath()));
        String second = "bytes=" + CHUNK + "-" + (2 * CHUNK - 1);
        assertEquals(second, ranges.get(1));
        assertEquals(second, ranges.get(2));
        assertEquals(second, ranges.get(3));
        assertEquals("bytes=" + (2 * CHUNK) + "-" + (3 * CHUNK - 1), ranges.get(4));
        assertEquals((data.length + CHUNK - 1) / CHUNK + 2, requests.get());
    }

    @Test
    public void persistentErrorsGiveUpAfterThreeRetries() {
        File f = new File(tmp.getRoot(), "i.mp4");
        fail503.set(100);
        try {
            dl().download(url, f, null, new AtomicBoolean());
            fail("expected IOException");
        } catch (ChunkedDownloader.ExpiredException | ChunkedDownloader.CancelledException e) {
            fail("wrong exception " + e);
        } catch (IOException expected) {
        }
        assertEquals(4, requests.get());
    }

    @Test
    public void cancelKeepsPartialAndLaterCallFinishes() throws IOException {
        File f = new File(tmp.getRoot(), "j.mp4");
        AtomicBoolean cancel = new AtomicBoolean();
        cancelFlag = cancel;
        cancelAfter = 2;
        try {
            dl().download(url, f, null, cancel);
            fail("expected cancel");
        } catch (ChunkedDownloader.CancelledException expected) {
        }
        long partial = f.length();
        assertTrue(partial >= 2L * CHUNK);
        assertTrue(partial < data.length);
        byte[] got = Files.readAllBytes(f.toPath());
        assertArrayEquals(Arrays.copyOf(data, (int) partial), got);

        cancelAfter = -1;
        cancel.set(false);
        long len = dl().download(url, f, null, cancel);
        assertEquals(data.length, len);
        assertArrayEquals(data, Files.readAllBytes(f.toPath()));
    }

    @Test
    public void cancelInsideChunkKeepsBytesWritten() throws IOException {
        File f = new File(tmp.getRoot(), "m.mp4");
        AtomicBoolean cancel = new AtomicBoolean();
        cancelFlag = cancel;
        stallAt = 2;
        try {
            dl().download(url, f, null, cancel);
            fail("expected cancel");
        } catch (ChunkedDownloader.CancelledException expected) {
        } finally {
            stallRelease.countDown();
        }
        long partial = f.length();
        // Stopped inside chunk 2, not at a chunk boundary after finishing it.
        assertTrue("partial " + partial, partial >= CHUNK && partial <= CHUNK + 2 * STALL_PIECE);
        assertArrayEquals(Arrays.copyOf(data, (int) partial), Files.readAllBytes(f.toPath()));

        cancel.set(false);
        ranges.clear();
        long len = dl().download(url, f, null, cancel);
        assertEquals(data.length, len);
        assertEquals("bytes=" + partial + "-" + (partial + CHUNK - 1), ranges.get(0));
        assertArrayEquals(data, Files.readAllBytes(f.toPath()));
    }

    @Test
    public void rangeNotSatisfiableAtTheEndMeansComplete() throws IOException {
        File f = new File(tmp.getRoot(), "n.mp4");
        // Complete file but no sidecar, so a request goes out and the server answers 416.
        writePrefix(f, data, data.length);
        long len = dl().download(url, f, null, new AtomicBoolean());
        assertEquals(data.length, len);
        assertEquals(1, requests.get());
        assertEquals("bytes=" + data.length + "-" + (data.length + CHUNK - 1), ranges.get(0));
        assertArrayEquals(data, Files.readAllBytes(f.toPath()));
        assertFalse(sidecar(f).exists());
    }

    @Test
    public void alreadyCompleteFileNeedsNoRequest() throws IOException {
        File f = new File(tmp.getRoot(), "k.mp4");
        writePrefix(f, data, data.length);
        Files.write(sidecar(f).toPath(), Long.toString(data.length).getBytes(StandardCharsets.UTF_8));
        long len = dl().download(url, f, null, new AtomicBoolean());
        assertEquals(data.length, len);
        assertEquals(0, requests.get());
        assertFalse(sidecar(f).exists());
    }

    @Test
    public void probeLengthReadsContentRangeOrLength() throws IOException {
        assertEquals(data.length, dl().probeLength(url));
        assertEquals("bytes=0-0", ranges.get(0));
        ignoreRange = true;
        assertEquals(data.length, dl().probeLength(url));
    }

    @Test
    public void parsesContentRangeForms() {
        assertArrayEquals(new long[]{0, 99, 1000}, ChunkedDownloader.parseContentRange("bytes 0-99/1000"));
        assertArrayEquals(new long[]{-1, -1, 1000}, ChunkedDownloader.parseContentRange("bytes */1000"));
        assertArrayEquals(new long[]{5, 9, -1}, ChunkedDownloader.parseContentRange("bytes 5-9/*"));
        assertEquals(null, ChunkedDownloader.parseContentRange("garbage"));
    }
}
