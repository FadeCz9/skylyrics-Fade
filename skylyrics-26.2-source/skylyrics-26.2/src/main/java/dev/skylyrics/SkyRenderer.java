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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Word by word: every lyric word pops up at its own spot in the world near the player,
 * floats for a moment (drifting up a little) and fades out. Words stay anchored in the world.
 *
 * LRCLIB only gives timestamps per LINE, so each line's duration is spread over its words
 * in proportion to word length. That's an estimate, not exact word timing.
 */
public final class SkyRenderer {
    private record Word(long timeMs, long endMs, String text) {}

    private static final class Floating {
        String text;
        double x, y, z;
        long bornNanos;
        float lifeSec;
        float hue;
        double swayPhase;
    }

    private static final List<Floating> FLOATING = new ArrayList<>();
    private static List<LyricsState.Line> lastLines = List.of();
    private static List<Word> words = List.of();
    private static int lastWord = -1;

    private SkyRenderer() {}

    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(SkyRenderer::submit);
    }

    private static List<Word> buildWords(List<LyricsState.Line> lines) {
        List<Word> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            LyricsState.Line l = lines.get(i);
            String[] parts = l.text().isBlank() ? new String[]{"♪"} : l.text().trim().split("\\s+");
            long next = i + 1 < lines.size() ? lines.get(i + 1).timeMs() : l.timeMs() + 5000;
            long gap = Math.max(300, next - l.timeMs());

            int totalW = 0;
            for (String p : parts) totalW += p.length() + 1;
            long dur = (long) Math.min(gap * 0.9, Math.max(800L, totalW * 140L));

            int acc = 0;
            for (String p : parts) {
                long start = l.timeMs() + (dur * acc) / totalW;
                out.add(new Word(start, next, p));
                acc += p.length() + 1;
            }
        }
        return out;
    }

    private static int lastWordAtOrBefore(List<Word> ws, long pos) {
        int lo = 0, hi = ws.size() - 1, res = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (ws.get(mid).timeMs() <= pos) { res = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return res;
    }

    private static void submit(LevelRenderContext ctx) {
        Config cfg = Config.get();
        Minecraft mc = Minecraft.getInstance();
        List<LyricsState.Line> lines = LyricsState.lines();
        if (!cfg.enabled || mc.player == null) return;

        long now = System.nanoTime();
        var cam = ctx.levelState().cameraRenderState;
        Vec3 cp = cam.pos;

        // new song (or lyrics just loaded): start fresh
        if (lines != lastLines) {
            lastLines = lines;
            words = buildWords(lines);
            lastWord = -1;
            FLOATING.clear();
        }

        // spawn every word whose time has come
        if (!words.isEmpty()) {
            long pos = LyricsState.positionMs();
            int cur = lastWordAtOrBefore(words, pos);
            if (cur > lastWord) {
                int from = Math.max(lastWord + 1, cur - 2); // never flood after a seek
                for (int w = from; w <= cur; w++) {
                    Word word = words.get(w);
                    float remaining = Math.max(0f, (word.endMs() - pos) / 1000f);
                    spawn(mc, cfg, cp, word.text(), Mth.clamp(remaining + 1.2f, 1.8f, 5.0f), now);
                }
                lastWord = cur;
            } else if (cur < lastWord) {
                lastWord = cur; // seeked backwards
            }
        }

        // drop expired words
        FLOATING.removeIf(f -> (now - f.bornNanos) / 1e9f > f.lifeSec);
        if (FLOATING.isEmpty()) return;

        double k = cfg.panelDistance / 40.0; // text size scales with distance
        PoseStack pose = ctx.poseStack();
        SubmitNodeCollector collector = ctx.submitNodeCollector();
        Font font = mc.font;
        double t = now / 1e9;

        for (Floating f : FLOATING) {
            float age = (now - f.bornNanos) / 1e9f;
            float fadeIn = Mth.clamp(age / 0.2f, 0f, 1f);
            float fadeOut = Mth.clamp((f.lifeSec - age) / 1.0f, 0f, 1f);
            float alpha = fadeIn * fadeOut;
            if (alpha <= 0.02f) continue;

            double rise = age * 0.15;                               // drifts upward
            double bob = Math.sin(t / 1.1 + f.swayPhase) * 0.9 * k; // floats a bit
            double sway = Math.sin(t / 1.7 + f.swayPhase) * 0.7 * k;
            float pop = 1f + 0.4f * (1f - fadeIn) * (1f - fadeIn);  // little "pop" on spawn
            int rgb = Mth.hsvToRgb(f.hue, 0.55f, 1f);

            pose.pushPose();
            pose.translate(f.x - cp.x, f.y + rise - cp.y, f.z - cp.z); // pose origin is the camera
            pose.mulPose(cam.orientation);                             // always face the camera
            pose.translate(sway, bob, 0);
            drawLine(pose, collector, font, f.text, (float) (0.36 * k * pop), (float) (46 * k), alpha, rgb);
            pose.popPose();
        }
    }

    private static void spawn(Minecraft mc, Config cfg, Vec3 cp, String text, float life, long now) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        // somewhere in front of the player, different every time
        double yaw = Math.toRadians(mc.player.getYRot() + r.nextDouble(-55, 55));
        double pitch = Math.toRadians(Mth.clamp(mc.player.getXRot() - cfg.panelUpDeg + r.nextDouble(-10, 10), -80, 80));
        double dist = cfg.panelDistance * r.nextDouble(0.85, 1.15);
        double cosP = Math.cos(pitch);

        Floating f = new Floating();
        f.text = text;
        f.x = cp.x - Math.sin(yaw) * cosP * dist;
        f.y = cp.y - Math.sin(pitch) * dist;
        f.z = cp.z + Math.cos(yaw) * cosP * dist;
        f.bornNanos = now;
        f.lifeSec = life;
        f.hue = r.nextFloat();
        f.swayPhase = r.nextDouble(0, Math.PI * 2);

        FLOATING.add(f);
        while (FLOATING.size() > 14) FLOATING.remove(0);
    }

    private static void drawLine(PoseStack pose, SubmitNodeCollector collector, Font font,
                                 String text, float scale, float maxWorldWidth, float alpha, int rgb) {
        int w = Math.max(1, font.width(text));
        float s = Math.min(scale, maxWorldWidth / w);
        int a = Math.max(5, (int) (Mth.clamp(alpha, 0f, 1f) * 255)); // alpha < 4 renders opaque
        int color = (a << 24) | (rgb & 0xFFFFFF);

        pose.pushPose();
        pose.scale(s, -s, s);
        collector.submitText(pose, -w / 2f, -4.5f, Component.literal(text).getVisualOrderText(),
                true, Font.DisplayMode.SEE_THROUGH, 0xF000F0, color, 0, 0);
        pose.popPose();
    }
}
