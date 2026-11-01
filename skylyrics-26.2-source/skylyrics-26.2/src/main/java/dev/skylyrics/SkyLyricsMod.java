package dev.skylyrics;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

public class SkyLyricsMod implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("skylyrics");

    @Override
    public void onInitializeClient() {
        Config.get();
        SpotifyAuth.loadTokens();
        SpotifyPoller.start();
        SkyRenderer.register();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
                ClientCommands.literal("lyrics")
                        .then(ClientCommands.literal("setclientid")
                                .then(ClientCommands.argument("id", StringArgumentType.word()).executes(c -> {
                                    Config cfg = Config.get();
                                    cfg.clientId = StringArgumentType.getString(c, "id");
                                    cfg.save();
                                    c.getSource().sendFeedback(Component.literal("Client ID saved. Now run /lyrics login"));
                                    return 1;
                                })))
                        .then(ClientCommands.literal("login").executes(c -> {
                            try {
                                String url = SpotifyAuth.beginLogin();
                                c.getSource().sendFeedback(Component.literal("[Click here to log in to Spotify]")
                                        .setStyle(Style.EMPTY
                                                .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                                                .withUnderlined(true)
                                                .withColor(ChatFormatting.GREEN)));
                            } catch (Exception e) {
                                c.getSource().sendError(Component.literal(String.valueOf(e.getMessage())));
                            }
                            return 1;
                        }))
                        .then(ClientCommands.literal("logout").executes(c -> {
                            SpotifyAuth.logout();
                            LyricsState.clear();
                            c.getSource().sendFeedback(Component.literal("Logged out of Spotify."));
                            return 1;
                        }))
                        .then(ClientCommands.literal("toggle").executes(c -> {
                            Config cfg = Config.get();
                            cfg.enabled = !cfg.enabled;
                            cfg.save();
                            c.getSource().sendFeedback(Component.literal("Sky lyrics " + (cfg.enabled ? "ON" : "OFF")));
                            return 1;
                        }))
                        .then(ClientCommands.literal("offset")
                                .then(ClientCommands.argument("ms", IntegerArgumentType.integer(-10000, 10000)).executes(c -> {
                                    Config cfg = Config.get();
                                    cfg.offsetMs = IntegerArgumentType.getInteger(c, "ms");
                                    cfg.save();
                                    c.getSource().sendFeedback(Component.literal("Offset set to " + cfg.offsetMs + " ms"));
                                    return 1;
                                })))
                        .then(ClientCommands.literal("distance")
                                .then(ClientCommands.argument("blocks", DoubleArgumentType.doubleArg(2, 60)).executes(c -> {
                                    Config cfg = Config.get();
                                    cfg.panelDistance = DoubleArgumentType.getDouble(c, "blocks");
                                    cfg.save();
                                    c.getSource().sendFeedback(Component.literal("Distance set to " + cfg.panelDistance + " blocks"));
                                    return 1;
                                })))
                        .then(ClientCommands.literal("height")
                                .then(ClientCommands.argument("degrees", FloatArgumentType.floatArg(-30, 60)).executes(c -> {
                                    Config cfg = Config.get();
                                    cfg.panelUpDeg = FloatArgumentType.getFloat(c, "degrees");
                                    cfg.save();
                                    c.getSource().sendFeedback(Component.literal("Height set to " + cfg.panelUpDeg + " degrees above crosshair"));
                                    return 1;
                                })))
                        .then(ClientCommands.literal("status").executes(c -> {
                            c.getSource().sendFeedback(Component.literal(
                                    "Logged in: " + SpotifyAuth.isLoggedIn()
                                            + " | Track: " + LyricsState.title() + " - " + LyricsState.artist()
                                            + " | Lyric lines: " + LyricsState.lines().size()
                                            + " | Offset: " + Config.get().offsetMs + " ms"));
                            return 1;
                        }))));
    }
}
