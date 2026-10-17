package com.hafij.astraplayer;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** YouTube search and stream lookup through NewPipeExtractor (no API key, no account). */
public final class YouTube {
    private YouTube() {
    }

    private static final Pattern YT_URL = Pattern.compile(
            "^(https?://)?((www|m|music)\\.)?(youtube\\.com|youtu\\.be|youtube-nocookie\\.com)/.+", Pattern.CASE_INSENSITIVE);
    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";

    private static volatile boolean initialised;

    public static boolean isYouTubeUrl(String url) {
        return url != null && YT_URL.matcher(url.trim()).matches();
    }

    public static synchronized void init() {
        if (initialised) return;
        NewPipe.init(new HttpDownloader());
        initialised = true;
    }

    /** A page of search results plus the handle for the next page. */
    public static final class Results {
        public final List<MediaItem> items;
        final SearchExtractor extractor;
        final Page next;

        Results(List<MediaItem> items, SearchExtractor extractor, Page next) {
            this.items = items;
            this.extractor = extractor;
            this.next = next;
        }

        public boolean hasMore() {
            return next != null;
        }
    }

    public static Results search(String query) throws Exception {
        init();
        SearchExtractor ex = ServiceList.YouTube.getSearchExtractor(query);
        ex.fetchPage();
        ListExtractor.InfoItemsPage<InfoItem> page = ex.getInitialPage();
        return new Results(convert(page.getItems()), ex, page.hasNextPage() ? page.getNextPage() : null);
    }

    public static Results more(Results prev) throws Exception {
        ListExtractor.InfoItemsPage<InfoItem> page = prev.extractor.getPage(prev.next);
        return new Results(convert(page.getItems()), prev.extractor, page.hasNextPage() ? page.getNextPage() : null);
    }

    private static List<MediaItem> convert(List<InfoItem> items) {
        List<MediaItem> out = new ArrayList<>();
        for (InfoItem it : items) {
            if (!(it instanceof StreamInfoItem)) continue;
            StreamInfoItem s = (StreamInfoItem) it;
            // Live streams in search results often come back as VIDEO_STREAM with no duration.
            boolean live = s.getStreamType() == StreamType.LIVE_STREAM || s.getStreamType() == StreamType.AUDIO_LIVE_STREAM
                    || s.getDuration() < 0;
            String sub = s.getUploaderName() == null ? "" : s.getUploaderName();
            if (live) sub = sub + " · LIVE";
            out.add(new MediaItem(s.getUrl(), s.getName(), MediaItem.YOUTUBE, sub,
                    bestThumb(s.getThumbnails()), Math.max(0, s.getDuration()) * 1000L));
        }
        return out;
    }

    private static String bestThumb(List<Image> images) {
        String best = null;
        int bestW = -1;
        for (Image i : images) {
            int w = i.getWidth();
            // Prefer a medium thumbnail (about 320-480 px wide) to keep lists light.
            int score = w <= 0 ? 1 : (w <= 480 ? w : 480 - (w - 480) / 4);
            if (score > bestW) {
                bestW = score;
                best = i.getUrl();
            }
        }
        return best;
    }

    /** Playable URLs for one video. */
    public static final class Resolved {
        public final String title;
        public final String uploader;
        public final String thumb;
        public final String videoUrl;
        /** Separate audio track to add as a libVLC slave, or null when videoUrl is muxed. */
        public final String audioUrl;
        public final boolean live;
        public final long durationMs;
        public final String quality;

        Resolved(String title, String uploader, String thumb, String videoUrl, String audioUrl, boolean live, long durationMs, String quality) {
            this.title = title;
            this.uploader = uploader;
            this.thumb = thumb;
            this.videoUrl = videoUrl;
            this.audioUrl = audioUrl;
            this.live = live;
            this.durationMs = durationMs;
            this.quality = quality;
        }
    }

    public static Resolved resolve(String pageUrl, int maxHeight, boolean audioOnly) throws Exception {
        init();
        StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, pageUrl);
        String title = info.getName();
        String uploader = info.getUploaderName();
        String thumb = bestThumb(info.getThumbnails());
        long dur = Math.max(0, info.getDuration()) * 1000L;
        boolean live = info.getStreamType() == StreamType.LIVE_STREAM || info.getStreamType() == StreamType.AUDIO_LIVE_STREAM;

        if (live) {
            String hls = info.getHlsUrl();
            if (hls != null && !hls.isEmpty()) return new Resolved(title, uploader, thumb, hls, null, true, 0, "live");
            String dash = info.getDashMpdUrl();
            if (dash != null && !dash.isEmpty()) return new Resolved(title, uploader, thumb, dash, null, true, 0, "live");
        }

        AudioStream audio = pickAudio(info.getAudioStreams());
        if (audioOnly && audio != null) {
            return new Resolved(title, uploader, thumb, audio.getContent(), null, live, dur, "audio");
        }
        VideoStream video = pickVideo(info.getVideoOnlyStreams(), maxHeight);
        if (video != null && audio != null) {
            return new Resolved(title, uploader, thumb, video.getContent(), audio.getContent(), live, dur, video.getResolution());
        }
        // Fall back to a muxed stream (usually 360p) when split streams are missing.
        VideoStream muxed = null;
        for (VideoStream v : info.getVideoStreams()) {
            if (!v.isUrl()) continue;
            if (muxed == null || v.getHeight() > muxed.getHeight()) muxed = v;
        }
        if (muxed != null) return new Resolved(title, uploader, thumb, muxed.getContent(), null, live, dur, muxed.getResolution());
        String hls = info.getHlsUrl();
        if (hls != null && !hls.isEmpty()) return new Resolved(title, uploader, thumb, hls, null, live, dur, "auto");
        throw new IOException("No playable stream found for this video");
    }

    private static VideoStream pickVideo(List<VideoStream> streams, int maxHeight) {
        VideoStream best = null;
        int bestScore = Integer.MIN_VALUE;
        for (VideoStream v : streams) {
            if (!v.isUrl()) continue;
            String codec = v.getCodec() == null ? "" : v.getCodec().toLowerCase(Locale.ROOT);
            if (codec.startsWith("av01")) continue; // few phones decode AV1 in hardware
            int h = v.getHeight();
            if (h <= 0) h = parseHeight(v.getResolution());
            if (h > maxHeight) continue;
            // Height first, then prefer H.264 (hardware decoding everywhere), then frame rate.
            int score = h * 100 + (codec.startsWith("avc") ? 20 : 0) + Math.min(v.getFps(), 60) / 10;
            if (score > bestScore) {
                bestScore = score;
                best = v;
            }
        }
        return best;
    }

    private static int parseHeight(String res) {
        if (res == null) return 0;
        StringBuilder sb = new StringBuilder();
        for (char c : res.toCharArray()) {
            if (Character.isDigit(c)) sb.append(c);
            else break;
        }
        try {
            return Integer.parseInt(sb.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static AudioStream pickAudio(List<AudioStream> streams) {
        AudioStream best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AudioStream a : streams) {
            if (!a.isUrl()) continue;
            // Skip dubbed or descriptive tracks when the original is available.
            String type = a.getAudioTrackType() == null ? "" : a.getAudioTrackType().name();
            int score = a.getAverageBitrate();
            if (type.equals("ORIGINAL")) score += 1000;
            else if (!type.isEmpty()) score -= 1000;
            String codec = a.getCodec() == null ? "" : a.getCodec().toLowerCase(Locale.ROOT);
            if (codec.startsWith("mp4a")) score += 5;
            if (score > bestScore) {
                bestScore = score;
                best = a;
            }
        }
        return best;
    }

    /** Minimal HttpURLConnection downloader for NewPipeExtractor. */
    static final class HttpDownloader extends Downloader {
        @Override
        public Response execute(Request request) throws IOException, org.schabi.newpipe.extractor.exceptions.ReCaptchaException {
            HttpURLConnection c = (HttpURLConnection) new URL(request.url()).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestMethod(request.httpMethod());
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", USER_AGENT);
            for (Map.Entry<String, List<String>> e : request.headers().entrySet()) {
                boolean first = true;
                for (String v : e.getValue()) {
                    if (first) c.setRequestProperty(e.getKey(), v);
                    else c.addRequestProperty(e.getKey(), v);
                    first = false;
                }
            }
            byte[] data = request.dataToSend();
            if (data != null) {
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(data);
                }
            }
            int code = c.getResponseCode();
            String body = "";
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null && !"HEAD".equals(request.httpMethod())) {
                try (InputStream s = in) {
                    body = new String(readAll(s), StandardCharsets.UTF_8);
                }
            }
            if (code == 429) {
                throw new org.schabi.newpipe.extractor.exceptions.ReCaptchaException("YouTube rate limit (HTTP 429)", request.url());
            }
            return new Response(code, c.getResponseMessage(), c.getHeaderFields(), body, c.getURL().toString());
        }

        private static byte[] readAll(InputStream in) throws IOException {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }
}
