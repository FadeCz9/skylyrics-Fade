package dev.skylyrics;

import java.util.List;

/** Shared, thread-safe-ish playback + lyrics state (written by the poller, read by the renderer). */
public final class LyricsState {
    public record Line(long timeMs, String text) {}

    private static volatile String trackId = "";
    private static volatile String title = "";
    private static volatile String artist = "";
    private static volatile List<Line> lines = List.of();
    private static volatile long baseProgressMs;
    private static volatile long baseNanos;
    private static volatile boolean playing;

    private LyricsState() {}

    /** @return true if this sample is a different track than before. */
    public static synchronized boolean sample(String id, String t, String a, long progressMs, boolean isPlaying) {
        boolean changed = !id.equals(trackId);
        if (changed) {
            trackId = id;
            lines = List.of();
        }
        title = t;
        artist = a;
        baseProgressMs = progressMs;
        baseNanos = System.nanoTime();
        playing = isPlaying;
        return changed;
    }

    public static synchronized void setLines(String id, List<Line> l) {
        if (id.equals(trackId)) lines = l;
    }

    public static synchronized void clear() {
        trackId = "";
        title = "";
        artist = "";
        lines = List.of();
        playing = false;
    }

    public static long positionMs() {
        long elapsed = playing ? (System.nanoTime() - baseNanos) / 1_000_000L : 0;
        return baseProgressMs + elapsed + Config.get().offsetMs;
    }

    public static int currentIndex(long pos) {
        List<Line> l = lines;
        int idx = -1;
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).timeMs() <= pos) idx = i; else break;
        }
        return idx;
    }

    public static List<Line> lines() { return lines; }
    public static String title() { return title; }
    public static String artist() { return artist; }
    public static boolean isPlaying() { return playing; }
}
