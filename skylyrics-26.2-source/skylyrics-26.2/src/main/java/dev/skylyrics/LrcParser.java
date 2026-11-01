package dev.skylyrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LrcParser {
    private static final Pattern TS = Pattern.compile("\\[(\\d+):(\\d+(?:[.:]\\d+)?)]");

    private LrcParser() {}

    public static List<LyricsState.Line> parse(String lrc) {
        List<LyricsState.Line> out = new ArrayList<>();
        for (String raw : lrc.split("\\R")) {
            Matcher m = TS.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find(end) && m.start() == end) {
                long min = Long.parseLong(m.group(1));
                double sec = Double.parseDouble(m.group(2).replace(':', '.'));
                times.add((long) (min * 60_000L + sec * 1000.0));
                end = m.end();
            }
            if (times.isEmpty()) continue;
            String text = raw.substring(end).trim();
            for (long t : times) out.add(new LyricsState.Line(t, text));
        }
        out.sort(Comparator.comparingLong(LyricsState.Line::timeMs));
        return out;
    }
}
