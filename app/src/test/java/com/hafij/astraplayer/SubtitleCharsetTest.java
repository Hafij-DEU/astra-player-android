package com.hafij.astraplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class SubtitleCharsetTest {
    private static final String TR = "1\n00:00:01,000 --> 00:00:02,000\nĞüzel şarkı, İstanbul'da ığdır çok öç\n";
    private static final String FR = "1\n00:00:01,000 --> 00:00:02,000\nCafé à la crème, garçon\n";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void utf8AndAsciiAndBomAndBinaryArePassedThrough() {
        assertNull(SubtitleCharset.toUtf8(TR.getBytes(StandardCharsets.UTF_8)));
        assertNull(SubtitleCharset.toUtf8("plain ascii".getBytes(StandardCharsets.US_ASCII)));
        assertNull(SubtitleCharset.toUtf8(TR.getBytes(StandardCharsets.UTF_16LE)));
        byte[] bom16 = new byte[]{(byte) 0xFF, (byte) 0xFE, 'a', 0};
        assertNull(SubtitleCharset.toUtf8(bom16));
        assertNull(SubtitleCharset.toUtf8(new byte[]{0, 0, 1, (byte) 0xBA, (byte) 0xE7}));
    }

    @Test
    public void turkishWindows1254BecomesUtf8() {
        byte[] legacy = TR.getBytes(Charset.forName("windows-1254"));
        assertEquals("windows-1254", SubtitleCharset.legacyCharset(legacy));
        assertEquals(TR, new String(SubtitleCharset.toUtf8(legacy), StandardCharsets.UTF_8));
    }

    @Test
    public void westernWindows1252BecomesUtf8() {
        byte[] legacy = FR.getBytes(Charset.forName("windows-1252"));
        assertEquals(FR, new String(SubtitleCharset.toUtf8(legacy), StandardCharsets.UTF_8));
    }

    @Test
    public void forLibVlcKeepsUtf8FilesAndCopiesLegacyOnes() throws IOException {
        File out = tmp.newFolder("out");
        File utf8 = write("a.srt", TR.getBytes(StandardCharsets.UTF_8));
        assertSame(utf8, SubtitleCharset.forLibVlc(utf8, out));
        File sup = write("b.sup", TR.getBytes(Charset.forName("windows-1254")));
        assertSame(sup, SubtitleCharset.forLibVlc(sup, out));

        File legacy = write("Film.tr.srt", TR.getBytes(Charset.forName("windows-1254")));
        File copy = SubtitleCharset.forLibVlc(legacy, out);
        assertEquals("Film.tr.srt", copy.getName());
        assertTrue(copy.getPath().startsWith(out.getPath()));
        assertEquals(TR, new String(Files.readAllBytes(copy.toPath()), StandardCharsets.UTF_8));

        File picked = write("picked.srt", TR.getBytes(Charset.forName("windows-1254")));
        SubtitleCharset.convertInPlace(picked);
        assertEquals(TR, new String(Files.readAllBytes(picked.toPath()), StandardCharsets.UTF_8));
    }

    private File write(String name, byte[] b) throws IOException {
        File f = tmp.newFile(name);
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(b);
        }
        return f;
    }
}
