package dev.skylyrics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Polls Spotify every few seconds; the renderer interpolates progress in between. */
public final class SpotifyPoller {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ScheduledExecutorService EX = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "skylyrics-poller");
        t.setDaemon(true);
        return t;
    });

    private SpotifyPoller() {}

    public static void start() {
        EX.scheduleWithFixedDelay(SpotifyPoller::poll, 2, 3, TimeUnit.SECONDS);
    }

    private static void poll() {
        try {
            if (!Config.get().enabled || !SpotifyAuth.isLoggedIn()) return;
            String token = SpotifyAuth.getAccessToken();
            if (token == null) return;

            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1/me/player/currently-playing"))
                    .header("Authorization", "Bearer " + token)
                    .GET().build();
            HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

            int code = r.statusCode();
            if (code == 204) { LyricsState.clear(); return; }
            if (code == 401) { SpotifyAuth.invalidate(); return; }
            if (code != 200) { SkyLyricsMod.LOG.warn("Spotify returned {}", code); return; }

            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            JsonElement item = o.get("item");
            JsonElement type = o.get("currently_playing_type");
            if (item == null || item.isJsonNull() || type == null || !"track".equals(type.getAsString())) {
                LyricsState.clear();
                return;
            }
            JsonObject t = item.getAsJsonObject();
            String title = t.get("name").getAsString();
            String artist = t.getAsJsonArray("artists").size() > 0
                    ? t.getAsJsonArray("artists").get(0).getAsJsonObject().get("name").getAsString() : "";
            JsonElement idEl = t.get("id");
            String id = (idEl == null || idEl.isJsonNull()) ? title + "|" + artist : idEl.getAsString();
            long duration = t.get("duration_ms").getAsLong();
            long progress = o.get("progress_ms").getAsLong();
            boolean playing = o.get("is_playing").getAsBoolean();

            boolean newTrack = LyricsState.sample(id, title, artist, progress, playing);
            if (newTrack) {
                CompletableFuture.runAsync(() -> {
                    List<LyricsState.Line> lines = LyricsFetcher.fetch(title, artist, duration);
                    LyricsState.setLines(id, lines);
                    if (lines.isEmpty()) Chat.send("No synced lyrics found for " + title + " - " + artist);
                });
            }
        } catch (Exception e) {
            SkyLyricsMod.LOG.warn("Spotify poll failed", e);
        }
    }
}
