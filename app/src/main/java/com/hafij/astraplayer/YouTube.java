package com.hafij.astraplayer;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
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

    /**
     * True for a link to a playlist page. A watch link that only carries list= (a video
     * played inside a playlist) is a single video.
     */
    public static boolean isPlaylistUrl(String url) {
        if (!isYouTubeUrl(url) || !url.contains("list=")) return false;
        String u = url.toLowerCase(Locale.ROOT);
        return u.contains("/playlist") || (!u.contains("v=") && !u.contains("youtu.be/"));
    }

    /** A YouTube playlist fetched for saving as an Astra playlist. */
    public static final class ImportedPlaylist {
        public final String name;
        public final List<MediaItem> items;
        /** True when the playlist had more than the requested maximum. */
        public final boolean truncated;

        ImportedPlaylist(String name, List<MediaItem> items, boolean truncated) {
            this.name = name;
            this.items = items;
            this.truncated = truncated;
        }
    }

    public static ImportedPlaylist importPlaylist(String url, int max) throws Exception {
        init();
        PlaylistInfo info = PlaylistInfo.getInfo(ServiceList.YouTube, url);
        List<MediaItem> items = new ArrayList<>(convert(new ArrayList<InfoItem>(info.getRelatedItems())));
        Page next = info.hasNextPage() ? info.getNextPage() : null;
        while (next != null && items.size() < max) {
            ListExtractor.InfoItemsPage<StreamInfoItem> page = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, next);
            items.addAll(convert(new ArrayList<InfoItem>(page.getItems())));
            next = page.hasNextPage() ? page.getNextPage() : null;
        }
        boolean truncated = next != null || items.size() > max;
        while (items.size() > max) items.remove(items.size() - 1);
        String name = info.getName() == null || info.getName().isEmpty() ? "YouTube playlist" : info.getName();
        return new ImportedPlaylist(name, items, truncated);
    }

    /** Download sources for caching one video offline. */
    public static final class CacheSource {
        public final String title;
        public final String uploader;
        public final String thumb;
        public final long durationMs;
        public final String videoUrl;
        public final String videoExt;
        public final int height;
        /** Separate audio stream, or null when the video stream already has sound. */
        public final String audioUrl;
        public final String audioExt;

        CacheSource(String title, String uploader, String thumb, long durationMs, String videoUrl, String videoExt,
                    int height, String audioUrl, String audioExt) {
            this.title = title;
            this.uploader = uploader;
            this.thumb = thumb;
            this.durationMs = durationMs;
            this.videoUrl = videoUrl;
            this.videoExt = videoExt;
            this.height = height;
            this.audioUrl = audioUrl;
            this.audioExt = audioExt;
        }
    }

    /**
     * Picks single-file streams for caching at no more than {@code maxHeight}: the best
     * video-only stream plus the best audio stream, or a muxed stream when split streams are
     * missing. Live streams cannot be cached.
     */
    public static CacheSource resolveForCache(String pageUrl, int maxHeight) throws Exception {
        init();
        return cacheSourceFrom(StreamInfo.getInfo(ServiceList.YouTube, pageUrl), maxHeight);
    }

    private static CacheSource cacheSourceFrom(StreamInfo info, int maxHeight) throws Exception {
        StreamType type = info.getStreamType();
        if (type == StreamType.LIVE_STREAM || type == StreamType.AUDIO_LIVE_STREAM) {
            throw new IOException("Live streams cannot be cached");
        }
        String title = info.getName();
        String uploader = info.getUploaderName();
        String thumb = bestThumb(info.getThumbnails());
        long dur = Math.max(0, info.getDuration()) * 1000L;
        VideoStream video = pickVideo(info.getVideoOnlyStreams(), maxHeight, true);
        AudioStream audio = pickAudio(info.getAudioStreams(), true);
        if (video != null && audio != null) {
            return new CacheSource(title, uploader, thumb, dur, video.getContent(), suffix(video, "mp4"),
                    heightOf(video), audio.getContent(), suffix(audio, "m4a"));
        }
        VideoStream muxed = null;
        for (VideoStream v : info.getVideoStreams()) {
            if (!v.isUrl() || v.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
            // Best stream within maxHeight; if none fits, the smallest one.
            if (muxed == null) {
                muxed = v;
                continue;
            }
            boolean vFits = heightOf(v) <= maxHeight, mFits = heightOf(muxed) <= maxHeight;
            if (vFits && (!mFits || heightOf(v) > heightOf(muxed))) muxed = v;
            else if (!vFits && !mFits && heightOf(v) < heightOf(muxed)) muxed = v;
        }
        if (muxed != null) {
            return new CacheSource(title, uploader, thumb, dur, muxed.getContent(), suffix(muxed, "mp4"),
                    heightOf(muxed), null, null);
        }
        throw new IOException("No downloadable stream found for this video");
    }

    private static int heightOf(VideoStream v) {
        return v.getHeight() > 0 ? v.getHeight() : parseHeight(v.getResolution());
    }

    private static String suffix(org.schabi.newpipe.extractor.stream.Stream s, String fallback) {
        MediaFormat f = s.getFormat();
        String x = f == null ? null : f.getSuffix();
        return x == null || x.isEmpty() ? fallback : x;
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

        /** Related videos YouTube suggests (empty when unavailable). */
        public List<MediaItem> related = new ArrayList<>();
        /** Streams for caching while playing, or null. */
        public CacheSource cache;

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
        return resolve(pageUrl, maxHeight, audioOnly, 0);
    }

    /**
     * @param cacheHeight when above 0, also picks single-file streams at that height for
     *                    caching while playing ({@link Resolved#cache}, null when not possible).
     */
    public static Resolved resolve(String pageUrl, int maxHeight, boolean audioOnly, int cacheHeight) throws Exception {
        init();
        StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, pageUrl);
        Resolved r = resolveFrom(info, maxHeight, audioOnly);
        r.related = relatedOf(info);
        if (cacheHeight > 0 && !r.live) {
            try {
                r.cache = cacheSourceFrom(info, cacheHeight);
            } catch (Exception ignored) {
                // No downloadable streams: just stream it.
            }
        }
        return r;
    }

    /** "Up next" videos YouTube suggests for a video (needs network). */
    public static List<MediaItem> related(String pageUrl) throws Exception {
        init();
        return relatedOf(StreamInfo.getInfo(ServiceList.YouTube, pageUrl));
    }

    private static List<MediaItem> relatedOf(StreamInfo info) {
        try {
            List<InfoItem> items = info.getRelatedItems();
            return items == null ? new ArrayList<>() : convert(new ArrayList<>(items));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static Resolved resolveFrom(StreamInfo info, int maxHeight, boolean audioOnly) throws Exception {
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
        return pickVideo(streams, maxHeight, false);
    }

    /** @param progressiveOnly true for caching: only single-file HTTP streams can be downloaded. */
    private static VideoStream pickVideo(List<VideoStream> streams, int maxHeight, boolean progressiveOnly) {
        VideoStream best = null;
        int bestScore = Integer.MIN_VALUE;
        for (VideoStream v : streams) {
            if (!v.isUrl()) continue;
            if (progressiveOnly && v.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
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
        return pickAudio(streams, false);
    }

    private static AudioStream pickAudio(List<AudioStream> streams, boolean progressiveOnly) {
        AudioStream best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AudioStream a : streams) {
            if (!a.isUrl()) continue;
            if (progressiveOnly && a.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
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
