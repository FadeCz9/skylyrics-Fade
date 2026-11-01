package dev.skylyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Fetches time-synced lyrics from LRCLIB (https://lrclib.net), a free community lyrics API. */
public final class LyricsFetcher {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private LyricsFetcher() {}

    public static List<LyricsState.Line> fetch(String title, String artist, long durationMs) {
        try {
            String q = "track_name=" + enc(title) + "&artist_name=" + enc(artist);

            HttpResponse<String> r = get("https://lrclib.net/api/get?" + q + "&duration=" + (durationMs / 1000));
            if (r.statusCode() == 200) {
                String synced = synced(JsonParser.parseString(r.body()).getAsJsonObject());
                if (synced != null) return LrcParser.parse(synced);
            }

            // fallback: fuzzy search
            r = get("https://lrclib.net/api/search?" + q);
            if (r.statusCode() == 200) {
                JsonArray arr = JsonParser.parseString(r.body()).getAsJsonArray();
                for (JsonElement e : arr) {
                    String synced = synced(e.getAsJsonObject());
                    if (synced != null) return LrcParser.parse(synced);
                }
            }
        } catch (Exception e) {
            SkyLyricsMod.LOG.warn("Lyrics lookup failed", e);
        }
        return List.of();
    }

    private static String synced(JsonObject o) {
        JsonElement s = o.get("syncedLyrics");
        return (s == null || s.isJsonNull() || s.getAsString().isBlank()) ? null : s.getAsString();
    }

    private static HttpResponse<String> get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "SkyLyrics/1.0 (Minecraft Fabric mod)")
                .GET().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
