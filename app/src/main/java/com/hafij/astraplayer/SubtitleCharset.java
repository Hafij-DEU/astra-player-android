package com.hafij.astraplayer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * libVLC is told to read text subtitles as UTF-8, which turns legacy 8-bit files (Turkish
 * Windows-1254, Western Windows-1252) into "?" marks. Files that are already valid UTF-8, carry
 * a BOM, or look binary are left untouched; legacy files get a UTF-8 copy for libVLC instead.
 */
final class SubtitleCharset {
    /** Text subtitle formats (binary ones such as .sup and .idx are never converted). */
    private static final Set<String> TEXT = new HashSet<>(Arrays.asList(
            "srt", "ass", "ssa", "vtt", "sub", "smi", "sami", "usf", "jss", "psb", "rt", "mpl2", "ttml"));
    /** Bigger files are left alone; real text subtitles are a few hundred KB. */
    static final long MAX_BYTES = 8L * 1024 * 1024;

    private SubtitleCharset() {
    }

    static boolean isText(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && TEXT.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * The legacy charset to decode {@code b} with, or null when the bytes must be passed through
     * unchanged (valid UTF-8 including plain ASCII, a UTF-8/UTF-16 BOM, or NUL bytes = binary).
     */
    static String legacyCharset(byte[] b) {
        if (b.length >= 2 && ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE
                || (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF)) return null;
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) return null;
        for (byte x : b) if (x == 0) return null;
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b));
            return null;
        } catch (CharacterCodingException notUtf8) {
            // fall through to the legacy guess
        }
        // Ğ İ Ş ğ ı ş live at these bytes in Windows-1254; in Windows-1252 they are the rare
        // Ð Ý Þ ð ý þ. All other letters (ç ö ü ...) are the same in both.
        for (byte x : b) {
            int v = x & 0xFF;
            if (v == 0xD0 || v == 0xDD || v == 0xDE || v == 0xF0 || v == 0xFD || v == 0xFE) {
                return supported("windows-1254", "ISO-8859-9");
            }
        }
        if ("tr".equals(Locale.getDefault().getLanguage())) return supported("windows-1254", "ISO-8859-9");
        return supported("windows-1252", "ISO-8859-1");
    }

    private static String supported(String name, String fallback) {
        try {
            if (Charset.isSupported(name)) return name;
        } catch (IllegalArgumentException ignored) {
            // fall back
        }
        return fallback;
    }

    /** UTF-8 bytes for a legacy-encoded subtitle, or null when it is fine as it is. */
    static byte[] toUtf8(byte[] b) {
        String cs = legacyCharset(b);
        if (cs == null) return null;
        return new String(b, Charset.forName(cs)).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns the file libVLC should load: {@code src} itself, or a UTF-8 copy in {@code outDir}
     * when {@code src} is a legacy-encoded text subtitle. Never throws; on any problem the
     * original file is returned, exactly as before.
     */
    static File forLibVlc(File src, File outDir) {
        try {
            if (!isText(src.getName())) return src;
            long len = src.length();
            if (len <= 0 || len > MAX_BYTES) return src;
            byte[] raw = read(src, (int) len);
            byte[] utf8 = toUtf8(raw);
            if (utf8 == null) return src;
            // Same file name in a per-source folder, so libVLC shows the same track name.
            String key = Integer.toHexString((src.getAbsolutePath() + "|" + len + "|" + src.lastModified()).hashCode());
            File dir = new File(outDir, key);
            if (!dir.isDirectory() && !dir.mkdirs()) return src;
            File out = new File(dir, src.getName());
            if (out.length() != utf8.length) {
                try (OutputStream o = new FileOutputStream(out)) {
                    o.write(utf8);
                }
            }
            return out;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return src;
        }
    }

    /** Rewrites {@code f} as UTF-8 in place when it is a legacy-encoded text subtitle. */
    static void convertInPlace(File f) {
        try {
            if (!isText(f.getName())) return;
            long len = f.length();
            if (len <= 0 || len > MAX_BYTES) return;
            byte[] utf8 = toUtf8(read(f, (int) len));
            if (utf8 == null) return;
            File tmp = new File(f.getPath() + ".utf8");
            try (OutputStream o = new FileOutputStream(tmp)) {
                o.write(utf8);
            }
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
            // keep the copy as it is
        }
    }

    private static byte[] read(File f, int len) throws IOException {
        byte[] b = new byte[len];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < len) {
                int n = in.read(b, off, len - off);
                if (n < 0) break;
                off += n;
            }
            return off == len ? b : Arrays.copyOf(b, off);
        }
    }
}
