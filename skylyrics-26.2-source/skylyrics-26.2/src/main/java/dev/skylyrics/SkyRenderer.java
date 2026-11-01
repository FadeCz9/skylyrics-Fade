package dev.skylyrics;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Draws the lyrics as a big billboard floating in the sky.
 * Minecraft 26.x renders through "submit nodes": we hand text to the SubmitNodeCollector
 * during LevelRenderEvents.COLLECT_SUBMITS instead of drawing immediately.
 */
public final class SkyRenderer {
    private static float skyYaw;
    private static boolean init;

    private SkyRenderer() {}

    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(SkyRenderer::submit);
    }

    private static void submit(LevelRenderContext ctx) {
        Config cfg = Config.get();
        Minecraft mc = Minecraft.getInstance();
        List<LyricsState.Line> lines = LyricsState.lines();
        if (!cfg.enabled || lines.isEmpty() || mc.player == null) return;

        long pos = LyricsState.positionMs();
        int idx = LyricsState.currentIndex(pos);

        // The panel hangs in the sky and only glides around when you turn far away from it.
        float target = mc.player.getYRot();
        if (!init) { skyYaw = target; init = true; }
        float diff = Mth.wrapDegrees(target - skyYaw);
        float deadZone = 30f;
        if (Math.abs(diff) > deadZone) skyYaw += (diff - Math.signum(diff) * deadZone) * 0.08f;

        var cam = ctx.levelState().cameraRenderState;
        Vec3 cp = cam.pos;
        double rad = Math.toRadians(skyYaw);
        double bob = Math.sin(System.nanoTime() / 1.2e9) * 0.6;
        double x = cp.x - Math.sin(rad) * cfg.distance;
        double z = cp.z + Math.cos(rad) * cfg.distance;
        double y = cp.y + cfg.height + bob;

        PoseStack pose = ctx.poseStack();
        SubmitNodeCollector collector = ctx.submitNodeCollector();
        Font font = mc.font;

        float hue = (float) ((System.nanoTime() / 6_000_000_000.0) % 1.0);
        int rgb = Mth.hsvToRgb(hue, 0.55f, 1f);

        pose.pushPose();
        pose.translate(x - cp.x, y - cp.y, z - cp.z);
        pose.mulPose(cam.orientation); // billboard: always face the camera

        // song title above the lyrics
        drawLine(pose, collector, font, "♪ " + LyricsState.title() + " - " + LyricsState.artist(),
                12.5, 0.10f, 0.5f, 0xBBBBBB);

        for (int row = -2; row <= 3; row++) {
            int li = idx + row;
            if (li < 0 || li >= lines.size()) continue;
            LyricsState.Line line = lines.get(li);
            boolean cur = row == 0;

            float scale = cur ? 0.22f : 0.13f;
            float alpha;
            if (cur) {
                float k = Mth.clamp((pos - line.timeMs()) / 300f, 0f, 1f);
                alpha = k;
                scale *= 1f + 0.3f * (1f - k) * (1f - k); // little "pop" when the line starts
            } else if (row < 0) {
                alpha = 0.35f;
            } else {
                alpha = Math.max(0.2f, 0.6f - 0.15f * row);
            }

            String text = line.text().isBlank() ? "♪" : line.text();
            double yOff = -row * 4.0 + (row > 0 ? -1.0 : 0.0);
            drawLine(pose, collector, font, text, yOff, scale, alpha, cur ? rgb : 0xDDDDDD);
        }
        pose.popPose();
    }

    private static void drawLine(PoseStack pose, SubmitNodeCollector collector, Font font,
                                 String text, double yOff, float scale, float alpha, int rgb) {
        int w = Math.max(1, font.width(text));
        float s = Math.min(scale, 46f / w); // never wider than ~46 blocks
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
