package dev.skylyrics;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * Lyrics floating close in front of the player. The panel lags behind when you turn
 * (it glides after you) and gently bobs/sways, so it never sits rigidly in one spot.
 */
public final class SkyRenderer {
    private static float panelYaw;
    private static float panelPitch;
    private static long lastNanos;
    private static boolean init;

    private SkyRenderer() {}

    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(SkyRenderer::submit);
    }

    private static float follow(float current, float target, float deadZone, float blend) {
        float diff = Mth.wrapDegrees(target - current);
        if (Math.abs(diff) > deadZone) current += (diff - Math.signum(diff) * deadZone) * blend;
        return current;
    }

    private static void submit(LevelRenderContext ctx) {
        Config cfg = Config.get();
        Minecraft mc = Minecraft.getInstance();
        List<LyricsState.Line> lines = LyricsState.lines();
        if (!cfg.enabled || lines.isEmpty() || mc.player == null) return;

        long pos = LyricsState.positionMs();
        int idx = LyricsState.currentIndex(pos);

        // time-based smoothing so it feels the same at any FPS
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0.016f : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        float blend = 1f - (float) Math.exp(-dt * 5f);

        float targetYaw = mc.player.getYRot();
        float targetPitch = Mth.clamp(mc.player.getXRot() - cfg.panelUpDeg, -85f, 85f);
        if (!init) { panelYaw = targetYaw; panelPitch = targetPitch; init = true; }
        panelYaw = follow(panelYaw, targetYaw, cfg.followDeg, blend);
        panelPitch = follow(panelPitch, targetPitch, cfg.followDeg * 0.6f, blend);

        double d = cfg.panelDistance;
        float k = (float) (d / 40.0); // everything scales with distance, so the apparent size stays the same
        double yawRad = Math.toRadians(panelYaw);
        double pitchRad = Math.toRadians(panelPitch);
        double cosP = Math.cos(pitchRad);
        double dx = -Math.sin(yawRad) * cosP;
        double dy = -Math.sin(pitchRad);
        double dz = Math.cos(yawRad) * cosP;

        double t = System.nanoTime() / 1e9;
        double bob = Math.sin(t / 1.1) * 0.9 * k;   // up/down float
        double sway = Math.sin(t / 1.7) * 0.7 * k;  // side-to-side float

        var cam = ctx.levelState().cameraRenderState;
        PoseStack pose = ctx.poseStack();
        SubmitNodeCollector collector = ctx.submitNodeCollector();
        Font font = mc.font;

        float hue = (float) ((System.nanoTime() / 6_000_000_000.0) % 1.0);
        int rgb = Mth.hsvToRgb(hue, 0.55f, 1f);
        float maxW = 46f * k;

        pose.pushPose();
        pose.translate(dx * d, dy * d, dz * d);   // pose origin is the camera
        pose.mulPose(cam.orientation);            // billboard: always face the camera
        pose.translate(sway, bob, 0);

        drawLine(pose, collector, font, "♪ " + LyricsState.title() + " - " + LyricsState.artist(),
                12.5 * k, 0.10f * k, maxW, 0.5f, 0xBBBBBB);

        for (int row = -2; row <= 3; row++) {
            int li = idx + row;
            if (li < 0 || li >= lines.size()) continue;
            LyricsState.Line line = lines.get(li);
            boolean cur = row == 0;

            float scale = (cur ? 0.22f : 0.13f) * k;
            float alpha;
            if (cur) {
                float p = Mth.clamp((pos - line.timeMs()) / 300f, 0f, 1f);
                alpha = p;
                scale *= 1f + 0.3f * (1f - p) * (1f - p); // little "pop" when the line starts
            } else if (row < 0) {
                alpha = 0.35f;
            } else {
                alpha = Math.max(0.2f, 0.6f - 0.15f * row);
            }

            String text = line.text().isBlank() ? "♪" : line.text();
            double yOff = (-row * 4.0 + (row > 0 ? -1.0 : 0.0)) * k;
            drawLine(pose, collector, font, text, yOff, scale, maxW, alpha, cur ? rgb : 0xDDDDDD);
        }
        pose.popPose();
    }

    private static void drawLine(PoseStack pose, SubmitNodeCollector collector, Font font,
                                 String text, double yOff, float scale, float maxWorldWidth,
                                 float alpha, int rgb) {
        int w = Math.max(1, font.width(text));
        float s = Math.min(scale, maxWorldWidth / w);
        int a = Math.max(5, (int) (Mth.clamp(alpha, 0f, 1f) * 255)); // alpha < 4 renders opaque
        int color = (a << 24) | (rgb & 0xFFFFFF);

        pose.pushPose();
        pose.translate(0, yOff, 0);
        pose.scale(s, -s, s);
        collector.submitText(pose, -w / 2f, -4.5f, Component.literal(text).getVisualOrderText(),
                true, Font.DisplayMode.SEE_THROUGH, 0xF000F0, color, 0, 0);
        pose.popPose();
    }
}
