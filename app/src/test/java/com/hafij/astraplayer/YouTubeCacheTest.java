package com.hafij.astraplayer;

import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class YouTubeCacheTest {
    private static final String ID = "dQw4w9WgXcQ";
    private static final String ID2 = "abcDEF_12-3";
    private static final String ID3 = "ZZZZZZZZZZZ";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private YouTubeCache cache() throws IOException {
        return new YouTubeCache(new File(tmp.getRoot(), "yt"));
    }

    private static void writeBytes(File f, int n) throws IOException {
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[n]);
        }
    }

    private static YouTubeCache.Entry entry(String id, long cachedAt) {
        YouTubeCache.Entry e = new YouTubeCache.Entry();
        e.id = id;
        e.url = "https://www.youtube.com/watch?v=" + id;
        e.title = "Title " + id;
        e.uploader = "Uploader";
        e.thumbUrl = "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
        e.durationMs = 212000;
        e.height = 720;
        e.videoFile = "video.mp4";
        e.audioFile = "audio.m4a";
        e.thumbFile = "thumb.jpg";
        e.bytes = 300;
        e.cachedAt = cachedAt;
        e.complete = true;
        return e;
    }

    /** Saves the entry and creates its media files. */
    private static void store(YouTubeCache c, YouTubeCache.Entry e) throws IOException {
        writeBytes(new File(c.dir(e.id), e.videoFile), 200);
        if (e.audioFile != null) writeBytes(new File(c.dir(e.id), e.audioFile), 100);
        c.save(e);
    }

    @Test
    public void videoIdFromManyUrlShapes() {
        assertEquals(ID, YouTubeCache.videoId("https://www.youtube.com/watch?v=" + ID));
        assertEquals(ID, YouTubeCache.videoId("http://youtube.com/watch?v=" + ID));
        assertEquals(ID, YouTubeCache.videoId("youtube.com/watch?v=" + ID));
        assertEquals(ID, YouTubeCache.videoId("https://www.youtube.com/watch?v=" + ID + "&t=42s"));
        assertEquals(ID, YouTubeCache.videoId("https://www.youtube.com/watch?feature=share&v=" + ID + "&list=PLx"));
        assertEquals(ID, YouTubeCache.videoId("https://www.youtube.com/watch?v=" + ID + "#t=1m"));
        assertEquals(ID, YouTubeCache.videoId("https://m.youtube.com/watch?v=" + ID));
        assertEquals(ID, YouTubeCache.videoId("https://music.youtube.com/watch?v=" + ID + "&si=abc"));
        assertEquals(ID, YouTubeCache.videoId("https://youtu.be/" + ID));
        assertEquals(ID, YouTubeCache.videoId("https://youtu.be/" + ID + "?t=30"));
        assertEquals(ID, YouTubeCache.videoId("https://youtu.be/" + ID + "?si=xyz&t=30"));
        assertEquals(ID, YouTubeCache.videoId("https://www.youtube.com/shorts/" + ID));
        assertEquals(ID, YouTubeCache.videoId("https://youtube.com/shorts/" + ID + "?feature=share"));
        assertEquals(ID, YouTubeCache.videoId("https://www.youtube.com/embed/" + ID + "?autoplay=1"));
        assertEquals(ID, YouTubeCache.videoId("https://www.youtube-nocookie.com/embed/" + ID));
        assertEquals(ID, YouTubeCache.videoId("https://www.youtube.com/live/" + ID + "?feature=shared"));
        assertEquals(ID, YouTubeCache.videoId("  https://WWW.YouTube.com/watch?v=" + ID + "  "));
        assertEquals(ID2, YouTubeCache.videoId("https://www.youtube.com/watch?v=" + ID2));
    }

    @Test
    public void videoIdRejects() {
        assertNull(YouTubeCache.videoId(null));
        assertNull(YouTubeCache.videoId(""));
        assertNull(YouTubeCache.videoId("not a url"));
        assertNull(YouTubeCache.videoId("https://vimeo.com/watch?v=" + ID));
        assertNull(YouTubeCache.videoId("https://notyoutube.com/watch?v=" + ID));
        assertNull(YouTubeCache.videoId("https://www.youtube.com.evil.com/watch?v=" + ID));
        assertNull(YouTubeCache.videoId("https://www.youtube.com/watch?v=short"));
        assertNull(YouTubeCache.videoId("https://www.youtube.com/watch?v=" + ID + "X"));
        assertNull(YouTubeCache.videoId("https://youtu.be/abc"));
        assertNull(YouTubeCache.videoId("https://www.youtube.com/playlist?list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"));
        assertNull(YouTubeCache.videoId("https://www.youtube.com/"));
        assertNull(YouTubeCache.videoId("https://www.youtube.com/@channel"));
        assertNull(YouTubeCache.videoId("https://www.youtube.com/shorts/"));
        assertNull(YouTubeCache.videoId("https://www.youtube.com/watch?v=../../etc/x"));
        // Playlist embed player: "videoseries" is 11 valid id characters but not a video.
        assertNull(YouTubeCache.videoId("https://www.youtube.com/embed/videoseries?list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"));
        assertNull(YouTubeCache.videoId("https://www.youtube.com/watch?list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"));
    }

    @Test
    public void saveGetRoundTrip() throws IOException {
        YouTubeCache c = cache();
        YouTubeCache.Entry e = entry(ID, 1000);
        store(c, e);
        assertTrue(new File(c.dir(ID), "meta.json").isFile());
        assertFalse(new File(c.dir(ID), "meta.json.tmp").exists());

        YouTubeCache.Entry g = c.get(ID);
        assertNotNull(g);
        assertEquals(ID, g.id);
        assertEquals(e.url, g.url);
        assertEquals(e.title, g.title);
        assertEquals(e.uploader, g.uploader);
        assertEquals(e.thumbUrl, g.thumbUrl);
        assertEquals(212000, g.durationMs);
        assertEquals(720, g.height);
        assertEquals("video.mp4", g.videoFile);
        assertEquals("audio.m4a", g.audioFile);
        assertEquals("thumb.jpg", g.thumbFile);
        assertEquals(300, g.bytes);
        assertEquals(1000, g.cachedAt);
        assertTrue(g.complete);

        assertEquals(new File(c.dir(ID), "video.mp4"), c.videoFile(g));
        assertEquals(new File(c.dir(ID), "audio.m4a"), c.audioFile(g));
        assertEquals(new File(c.dir(ID), "thumb.jpg"), c.thumbFile(g));
    }

    @Test
    public void saveOverwritesMeta() throws IOException {
        YouTubeCache c = cache();
        YouTubeCache.Entry e = entry(ID, 1000);
        store(c, e);
        e.title = "Renamed";
        c.save(e);
        assertEquals("Renamed", c.get(ID).title);
    }

    @Test
    public void nullOptionalFiles() throws IOException {
        YouTubeCache c = cache();
        YouTubeCache.Entry e = entry(ID, 1000);
        e.audioFile = null;
        e.thumbFile = null;
        store(c, e);
        YouTubeCache.Entry g = c.get(ID);
        assertNotNull(g);
        assertNull(g.audioFile);
        assertNull(g.thumbFile);
        assertNull(c.audioFile(g));
        assertNull(c.thumbFile(g));
    }

    @Test
    public void fromJsonToleratesMissingFields() throws Exception {
        YouTubeCache.Entry e = YouTubeCache.Entry.fromJson(new JSONObject("{\"id\":\"" + ID + "\"}"));
        assertEquals(ID, e.id);
        assertNull(e.title);
        assertNull(e.videoFile);
        assertNull(e.audioFile);
        assertEquals(0, e.height);
        assertEquals(0, e.cachedAt);
        assertFalse(e.complete);
    }

    @Test
    public void getNullWhenMediaMissing() throws IOException {
        YouTubeCache c = cache();
        store(c, entry(ID, 1000));
        assertTrue(new File(c.dir(ID), "audio.m4a").delete());
        assertNull(c.get(ID));

        store(c, entry(ID2, 1000));
        assertTrue(new File(c.dir(ID2), "video.mp4").delete());
        assertNull(c.get(ID2));
        assertTrue(c.all().isEmpty());
    }

    @Test
    public void getNullWhenIncompleteOrBadMeta() throws IOException {
        YouTubeCache c = cache();
        YouTubeCache.Entry e = entry(ID, 1000);
        e.complete = false;
        store(c, e);
        assertNull(c.get(ID));

        writeBytes(new File(c.dir(ID2), "video.mp4"), 10);
        assertNull(c.get(ID2));
        File meta = new File(c.dir(ID2), "meta.json");
        try (FileOutputStream out = new FileOutputStream(meta)) {
            out.write("{not json".getBytes("UTF-8"));
        }
        assertNull(c.get(ID2));
        assertNull(c.get(ID3));
        assertTrue(c.all().isEmpty());
    }

    @Test
    public void allNewestFirst() throws IOException {
        YouTubeCache c = cache();
        store(c, entry(ID, 1000));
        store(c, entry(ID2, 3000));
        store(c, entry(ID3, 2000));
        YouTubeCache.Entry incomplete = entry("incomplete1", 9000);
        incomplete.complete = false;
        store(c, incomplete);
        new File(c.root(), "not-a-video-folder").mkdirs();

        List<YouTubeCache.Entry> all = c.all();
        assertEquals(3, all.size());
        assertEquals(ID2, all.get(0).id);
        assertEquals(ID3, all.get(1).id);
        assertEquals(ID, all.get(2).id);
    }

    @Test
    public void emptyStoreDoesNotThrow() throws IOException {
        YouTubeCache c = cache();
        assertTrue(c.all().isEmpty());
        assertEquals(0, c.totalBytes());
        assertNull(c.get(ID));
        assertEquals(0, c.deleteAll());
        assertEquals(0, c.pruneIncomplete(0, System.currentTimeMillis()));
        assertFalse(c.delete(ID));
    }

    @Test
    public void totalBytesCountsEverything() throws IOException {
        YouTubeCache c = cache();
        store(c, entry(ID, 1000));
        writeBytes(new File(c.dir(ID2), "video.mp4.part"), 50);
        long meta = new File(c.dir(ID), "meta.json").length();
        assertEquals(200 + 100 + 50 + meta, c.totalBytes());
    }

    @Test
    public void deleteOne() throws IOException {
        YouTubeCache c = cache();
        store(c, entry(ID, 1000));
        store(c, entry(ID2, 2000));
        assertTrue(c.delete(ID));
        assertFalse(c.dir(ID).exists());
        assertNull(c.get(ID));
        assertNotNull(c.get(ID2));
        assertFalse(c.delete(ID));
    }

    @Test
    public void deleteAllKeepsRootAndNomedia() throws IOException {
        YouTubeCache c = cache();
        store(c, entry(ID, 1000));
        store(c, entry(ID2, 2000));
        writeBytes(new File(c.dir(ID3), "video.mp4.part"), 10);
        assertEquals(3, c.deleteAll());
        assertTrue(c.root().isDirectory());
        assertTrue(new File(c.root(), ".nomedia").isFile());
        assertTrue(c.all().isEmpty());
        assertEquals(0, c.totalBytes());
    }

    @Test
    public void nomediaCreatedOnSave() throws IOException {
        YouTubeCache c = cache();
        assertFalse(new File(c.root(), ".nomedia").exists());
        store(c, entry(ID, 1000));
        assertTrue(new File(c.root(), ".nomedia").isFile());
    }

    @Test
    public void pruneIncompleteRemovesOnlyStalePartials() throws IOException {
        YouTubeCache c = cache();
        long now = 10_000_000_000L;
        long hour = 3_600_000L;

        // Complete and old: kept.
        store(c, entry(ID, 1000));
        for (File f : c.dir(ID).listFiles()) assertTrue(f.setLastModified(now - 48 * hour));

        // Partial and old: removed.
        File stale = new File(c.dir(ID2), "video.mp4.part");
        writeBytes(stale, 10);
        assertTrue(stale.setLastModified(now - 48 * hour));

        // Partial but recently written: kept (download may still be running).
        File fresh = new File(c.dir(ID3), "video.mp4.part");
        writeBytes(fresh, 10);
        assertTrue(fresh.setLastModified(now - 1000));

        // Incomplete meta and old: removed.
        YouTubeCache.Entry inc = entry("incomplete1", 1000);
        inc.complete = false;
        store(c, inc);
        for (File f : c.dir("incomplete1").listFiles()) assertTrue(f.setLastModified(now - 48 * hour));

        assertEquals(2, c.pruneIncomplete(24 * hour, now));
        assertTrue(c.dir(ID).exists());
        assertFalse(c.dir(ID2).exists());
        assertTrue(c.dir(ID3).exists());
        assertFalse(c.dir("incomplete1").exists());
        assertNotNull(c.get(ID));
    }

    @Test
    public void invalidIdsRefused() throws IOException {
        YouTubeCache c = cache();
        String[] bad = {null, "", "..", "../../etc/x", "abc/def/ghi", "short", "dQw4w9WgXcQX", "dQw4w9WgXc.", "dQw4w9Wg\\cQ"};
        for (String id : bad) {
            try {
                c.dir(id);
                fail("dir accepted " + id);
            } catch (IllegalArgumentException expected) {
            }
            assertFalse(c.delete(id));
            assertNull(c.get(id));
            YouTubeCache.Entry e = entry(ID, 1000);
            e.id = id;
            try {
                c.save(e);
                fail("save accepted " + id);
            } catch (IOException expected) {
            }
        }
        assertFalse(new File(tmp.getRoot(), "etc").exists());
    }

    @Test
    public void deleteDoesNotEscapeRoot() throws IOException {
        File outside = new File(tmp.getRoot(), "keep.txt");
        writeBytes(outside, 5);
        YouTubeCache c = cache();
        assertFalse(c.delete(".."));
        assertTrue(outside.isFile());
    }

    /** Creates a symbolic link, or skips the test on file systems that do not support them. */
    private static void link(File link, File target) throws IOException {
        link.getParentFile().mkdirs();
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath());
        } catch (UnsupportedOperationException | IOException e) {
            Assume.assumeNoException(e);
        }
    }

    @Test
    public void deleteNeverFollowsLinksOutsideRoot() throws IOException {
        File outsideDir = new File(tmp.getRoot(), "outside");
        File precious = new File(outsideDir, "precious.mp4");
        writeBytes(precious, 5);
        YouTubeCache c = cache();

        // The video folder itself is a link to an outside folder.
        link(c.dir(ID), outsideDir);
        // A link inside a real video folder pointing at the outside folder.
        writeBytes(new File(c.dir(ID2), "video.mp4.part"), 10);
        link(new File(c.dir(ID2), "escape"), outsideDir);

        // Links are not followed, so the outside file is not counted either.
        assertEquals(10, c.totalBytes());
        assertTrue(c.delete(ID));
        assertTrue(precious.isFile());
        assertEquals(1, c.deleteAll());
        assertTrue(precious.isFile());
        assertFalse(c.dir(ID2).exists());
    }

    @Test
    public void pruneNeverFollowsLinksOutsideRoot() throws IOException {
        File outsideDir = new File(tmp.getRoot(), "outside");
        File precious = new File(outsideDir, "precious.mp4");
        writeBytes(precious, 5);
        YouTubeCache c = cache();
        long now = System.currentTimeMillis() + 48 * 3_600_000L;
        File part = new File(c.dir(ID2), "video.mp4.part");
        writeBytes(part, 10);
        link(new File(c.dir(ID2), "escape"), outsideDir);
        assertTrue(part.setLastModified(now - 47 * 3_600_000L));

        assertEquals(1, c.pruneIncomplete(3_600_000L, now));
        assertFalse(c.dir(ID2).exists());
        assertTrue(precious.isFile());
    }

    @Test
    public void formatBytes() {
        assertEquals("512 B", YouTubeCache.Entry.formatBytes(512));
        assertEquals("512 KB", YouTubeCache.Entry.formatBytes(512 * 1024L));
        assertEquals("38 MB", YouTubeCache.Entry.formatBytes(38L * 1024 * 1024));
        assertEquals("1.2 GB", YouTubeCache.Entry.formatBytes((long) (1.2 * 1024 * 1024 * 1024)));
        assertEquals("2.0 GB", YouTubeCache.Entry.formatBytes(2L * 1024 * 1024 * 1024));
        // Just below a unit boundary rounds up into the next unit, never "1024 KB" / "1024 MB".
        assertEquals("1 MB", YouTubeCache.Entry.formatBytes(1024L * 1024 - 1));
        assertEquals("1.0 GB", YouTubeCache.Entry.formatBytes(1024L * 1024 * 1024 - 1));
    }

    @Test
    public void unsafeStoredFileNamesRejected() throws IOException {
        YouTubeCache c = cache();
        YouTubeCache.Entry e = entry(ID, 1000);
        store(c, e);
        e.videoFile = "../../etc/passwd";
        e.audioFile = "../x";
        e.thumbFile = "a/b.jpg";
        c.save(e);
        assertNull(c.get(ID));
        assertNull(c.audioFile(e));
        assertNull(c.thumbFile(e));
        try {
            c.videoFile(e);
            fail("videoFile accepted a path outside the folder");
        } catch (IllegalArgumentException expected) {
        }
    }
}
