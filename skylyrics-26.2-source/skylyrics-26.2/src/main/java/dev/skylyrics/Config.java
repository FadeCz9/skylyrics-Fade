package dev.skylyrics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config instance;

    public String clientId = "";
    public int port = 8888;          // must match the redirect URI in your Spotify app
    public int offsetMs = 0;         // + = lyrics appear later, - = earlier
    public boolean enabled = true;
    public double distance = 40;     // blocks in front of you
    public double height = 18;       // blocks above you

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("skylyrics.json");
    }

    public static synchronized Config get() {
        if (instance == null) {
            try {
                if (Files.exists(path())) instance = GSON.fromJson(Files.readString(path()), Config.class);
            } catch (Exception ignored) {}
            if (instance == null) {
                instance = new Config();
                instance.save();
            }
        }
        return instance;
    }

    public synchronized void save() {
        try {
            Files.writeString(path(), GSON.toJson(this));
        } catch (Exception e) {
            SkyLyricsMod.LOG.error("Could not save config", e);
        }
    }
}
