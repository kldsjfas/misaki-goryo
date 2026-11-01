package com.luckystar.misakigoryo;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.math.Axis;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import mods.flammpfeil.slashblade.client.renderer.util.BladeRenderState;
import mods.flammpfeil.slashblade.event.client.RenderOverrideEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// 刀穗子：拔刀剑画刀身（blade 组，含刀柄）时，顺手把拆出来的 tassel 组按弹簧摆动画上去。转头、走路、挥刀都会把它甩起来。
@Mod.EventBusSubscriber(modid = MisakiGoryo.MOD_ID, value = Dist.CLIENT)
public final class TasselPhysics {
    private static Map<String, float[]> pivots;
    private static float swayX, speedX, swayZ, speedZ, lastYaw, lastPitch;
    private static long lastNanos;
    private static boolean rendering;

    private TasselPhysics() {
    }

    @SubscribeEvent
    public static void onRender(RenderOverrideEvent event) {
        // 手持时拔刀剑只画 blade（这一组里自带刀柄），刀身受损时画 blade_damaged；穗子跟着它们画
        String target = event.getTarget();
        if (rendering || !("blade".equals(target) || "blade_damaged".equals(target))) return;
        String path = event.getTexture().getPath();
        if (!event.getTexture().getNamespace().equals(MisakiGoryo.MOD_ID) || !path.startsWith("model/blades/")) return;
        String key = path.substring("model/blades/".length()).replace(".png", "");
        float[] pivot = pivots().get(key);
        if (pivot == null) return;
        step();
        var minecraft = Minecraft.getInstance();
        int light = minecraft.level != null && minecraft.player != null
            ? LevelRenderer.getLightColor(minecraft.level, minecraft.player.blockPosition()) : 0xF000F0;
        var pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(pivot[0], pivot[1], pivot[2]);
        // 先让穗子朝真实地面垂下：把世界的「下」换到当前模型坐标里，再从模型的 -Y 转过去
        pose.mulPose(hangDown(pose));
        pose.mulPose(Axis.ZP.rotationDegrees(swayZ));
        pose.mulPose(Axis.XP.rotationDegrees(swayX));
        pose.translate(-pivot[0], -pivot[1], -pivot[2]);
        rendering = true;
        try {
            BladeRenderState.renderOverrided(event.getStack(), event.getModel(), "tassel", event.getTexture(), pose, event.getBuffer(), light);
        } finally {
            rendering = false;
            pose.popPose();
        }
    }

    private static org.joml.Quaternionf hangDown(com.mojang.blaze3d.vertex.PoseStack pose) {
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        // 渲染时的姿态矩阵是在视角空间里的：先把世界的「下」转进视角空间，再用法线矩阵的逆（转置）转回模型空间
        // 视角矩阵是 XP(俯仰)·YP(偏航+180)，偏航不影响竖直方向，所以世界的「下」在视角空间里只跟俯仰有关
        float pitch = (float)Math.toRadians(camera.getXRot());
        var down = new org.joml.Vector3f(0, -(float)Math.cos(pitch), -(float)Math.sin(pitch));
        new org.joml.Matrix3f(pose.last().normal()).transpose().transform(down);
        if (down.lengthSquared() < 1.0E-6F) return new org.joml.Quaternionf();
        down.normalize();
        return new org.joml.Quaternionf().rotationTo(new org.joml.Vector3f(0, -1, 0), down);
    }

    // 每帧推进一次弹簧：转头和挥刀给穗子一个冲量，弹簧把它拉回垂直，阻尼让它慢慢停下
    private static void step() {
        var player = Minecraft.getInstance().player;
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0 : Math.min(0.05F, (now - lastNanos) / 1.0E9F);
        lastNanos = now;
        if (player == null || dt <= 0) return;
        float yaw = player.getYRot(), pitch = player.getXRot();
        float yawSpeed = Mth.wrapDegrees(yaw - lastYaw) / dt, pitchSpeed = (pitch - lastPitch) / dt;
        lastYaw = yaw;
        lastPitch = pitch;
        var motion = player.getDeltaMovement();
        float swing = player.getAttackAnim(Minecraft.getInstance().getFrameTime());
        float forceX = Mth.clamp(-yawSpeed * 0.9F, -900, 900) + swing * 600;
        float forceZ = Mth.clamp(pitchSpeed * 0.6F, -600, 600) + (float)(motion.horizontalDistance() * 900) + swing * 400;
        speedX += (forceX - 70 * swayX - 7 * speedX) * dt;
        speedZ += (forceZ - 70 * swayZ - 7 * speedZ) * dt;
        swayX = Mth.clamp(swayX + speedX * dt, -65, 65);
        swayZ = Mth.clamp(swayZ + speedZ * dt, -65, 65);
    }

    private static Map<String, float[]> pivots() {
        if (pivots != null) return pivots;
        pivots = new HashMap<>();
        try {
            var resource = Minecraft.getInstance().getResourceManager()
                .getResource(new ResourceLocation(MisakiGoryo.MOD_ID, "model/blades/tassel.json"));
            if (resource.isPresent()) {
                try (var reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    for (var entry : json.entrySet()) {
                        var a = entry.getValue().getAsJsonArray();
                        pivots.put(entry.getKey(), new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()});
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return pivots;
    }
}
