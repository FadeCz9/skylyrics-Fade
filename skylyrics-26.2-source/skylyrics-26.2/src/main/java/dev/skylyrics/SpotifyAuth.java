package dev.skylyrics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Spotify Authorization Code flow with PKCE (no client secret needed). */
public final class SpotifyAuth {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final SecureRandom RNG = new SecureRandom();
    private static final String SCOPES = "user-read-currently-playing user-read-playback-state";

    private static String accessToken;
    private static String refreshToken;
    private static long expiresAtMs;
    private static HttpServer server;

    private SpotifyAuth() {}

    private static Path tokenPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("skylyrics_tokens.json");
    }

    public static synchronized void loadTokens() {
        try {
            Path p = tokenPath();
            if (!Files.exists(p)) return;
            JsonObject o = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            refreshToken = o.get("refresh_token").getAsString();
        } catch (Exception e) {
            SkyLyricsMod.LOG.warn("Could not read saved Spotify tokens", e);
        }
    }

    private static void saveTokens() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("refresh_token", refreshToken);
            Files.writeString(tokenPath(), o.toString());
        } catch (IOException e) {
            SkyLyricsMod.LOG.warn("Could not save Spotify tokens", e);
        }
    }

    public static synchronized boolean isLoggedIn() { return refreshToken != null; }

    public static synchronized void logout() {
        accessToken = null;
        refreshToken = null;
        expiresAtMs = 0;
        try { Files.deleteIfExists(tokenPath()); } catch (IOException ignored) {}
    }

    public static synchronized void invalidate() { accessToken = null; }

    public static synchronized String getAccessToken() throws IOException, InterruptedException {
        if (refreshToken == null) return null;
        if (accessToken == null || System.currentTimeMillis() > expiresAtMs - 30_000) refresh();
        return accessToken;
    }

    private static void refresh() throws IOException, InterruptedException {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "refresh_token");
        f.put("refresh_token", refreshToken);
        f.put("client_id", Config.get().clientId);
        applyTokenResponse(postToken(f));
    }

    private static JsonObject postToken(Map<String, String> form) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://accounts.spotify.com/api/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
                .build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) {
            throw new IOException("Spotify token endpoint returned " + r.statusCode() + ": " + r.body());
        }
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static synchronized void applyTokenResponse(JsonObject o) {
        accessToken = o.get("access_token").getAsString();
        expiresAtMs = System.currentTimeMillis() + o.get("expires_in").getAsLong() * 1000L;
        if (o.has("refresh_token")) {
            refreshToken = o.get("refresh_token").getAsString();
            saveTokens();
        }
    }

    /** Starts the local callback server and returns the URL the user must open. */
    public static synchronized String beginLogin() throws IOException {
        Config cfg = Config.get();
        if (cfg.clientId.isBlank()) throw new IOException("Set your client ID first: /lyrics setclientid <id>");

        stopServer();
        String verifier = randomUrlSafe(64);
        String challenge = sha256Base64Url(verifier);
        String state = randomUrlSafe(16);
        String redirect = "http://127.0.0.1:" + cfg.port + "/callback";

        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), cfg.port), 0);
        server.createContext("/callback", ex -> handleCallback(ex, state, verifier, redirect));
        server.start();
        CompletableFuture.delayedExecutor(5, TimeUnit.MINUTES).execute(SpotifyAuth::stopServer);

        Map<String, String> p = new LinkedHashMap<>();
        p.put("client_id", cfg.clientId);
        p.put("response_type", "code");
        p.put("redirect_uri", redirect);
        p.put("scope", SCOPES);
        p.put("state", state);
        p.put("code_challenge_method", "S256");
        p.put("code_challenge", challenge);
        return "https://accounts.spotify.com/authorize?" + encode(p);
    }

    private static void handleCallback(HttpExchange ex, String state, String verifier, String redirect) throws IOException {
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        String err = q.get("error");
        boolean ok = err == null && state.equals(q.get("state")) && q.get("code") != null;

        String html = ok
                ? "<h2>SkyLyrics connected! You can close this tab and go back to Minecraft.</h2>"
                : "<h2>Authorization failed. Run /lyrics login again.</h2>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(ok ? 200 : 400, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }

        String code = q.get("code");
        CompletableFuture.runAsync(() -> {
            try {
                if (!ok) {
                    Chat.send("Spotify login failed" + (err != null ? ": " + err : "."));
                } else {
                    Map<String, String> f = new LinkedHashMap<>();
                    f.put("grant_type", "authorization_code");
                    f.put("code", code);
                    f.put("redirect_uri", redirect);
                    f.put("client_id", Config.get().clientId);
                    f.put("code_verifier", verifier);
                    applyTokenResponse(postToken(f));
                    Chat.send("Spotify connected! Play something and look up.");
                }
            } catch (Exception e) {
                Chat.send("Login failed: " + e.getMessage());
            } finally {
                stopServer();
            }
        });
    }

    private static synchronized void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private static String randomUrlSafe(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String sha256Base64Url(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (var e : m.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null) return m;
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            if (i < 0) continue;
            m.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8),
                  URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }
}
