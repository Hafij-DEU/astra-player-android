package com.hafij.astraplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MediaFilesSubtitleTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void numberContinuationDoesNotMatch() {
        assertTrue(MediaFiles.matchesVideo("ep1", "ep1"));
        assertTrue(MediaFiles.matchesVideo("ep1.en", "ep1"));
        assertTrue(MediaFiles.matchesVideo("ep1_tr", "ep1"));
        assertTrue(MediaFiles.matchesVideo("ep1 english", "ep1"));
        assertTrue(MediaFiles.matchesVideo("movie.forced", "movie"));
        assertTrue(MediaFiles.matchesVideo("movie2", "movie"));
        assertFalse(MediaFiles.matchesVideo("ep10", "ep1"));
        assertFalse(MediaFiles.matchesVideo("ep11.en", "ep1"));
        assertFalse(MediaFiles.matchesVideo("other", "ep1"));
    }

    @Test
    public void findSubtitlesSeparatesEpisodes() throws IOException {
        File dir = tmp.getRoot();
        for (String n : new String[]{"Ep1.mkv", "Ep10.mkv", "Ep1.srt", "Ep1.en.srt", "Ep10.srt", "Ep11.srt"}) {
            new File(dir, n).createNewFile();
        }
        assertEquals(List.of("Ep1.en.srt", "Ep1.srt"), names(MediaFiles.findSubtitles(new File(dir, "Ep1.mkv"))));
        assertEquals(List.of("Ep10.srt"), names(MediaFiles.findSubtitles(new File(dir, "Ep10.mkv"))));
    }

    @Test
    public void singleVideoStillTakesEverySubtitle() throws IOException {
        File dir = tmp.newFolder("one");
        for (String n : new String[]{"Film.mkv", "Film.tr.srt", "Ep10.srt"}) new File(dir, n).createNewFile();
        assertEquals(2, MediaFiles.findSubtitles(new File(dir, "Film.mkv")).size());
    }

    private static List<String> names(List<File> files) {
        List<String> out = new ArrayList<>();
        for (File f : files) out.add(f.getName());
        return out;
    }
}
