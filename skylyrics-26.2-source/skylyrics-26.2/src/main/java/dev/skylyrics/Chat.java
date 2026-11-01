package dev.skylyrics;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

final class Chat {
    private Chat() {}

    static void send(String msg) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendSystemMessage(Component.literal("§a[SkyLyrics] §r" + msg));
        });
    }
}
