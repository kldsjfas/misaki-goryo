package com.luckystar.misakigoryo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/** Uses the reviewed model contract directly, including its per-face UVs and rigid bones. */
public final class MisakiSpiritModel {
    public record CapturePose(String animation, float tick, boolean closedEyes) {}
    private static java.util.function.Function<MisakiSpiritEntity, CapturePose> capturePoseProvider;

    public static void setCapturePoseProvider(java.util.function.Function<MisakiSpiritEntity, CapturePose> provider) {
        capturePoseProvider = provider;
    }

    private static final ResourceLocation WHITE = new ResourceLocation(
        "misaki_goryo", "textures/entity/misaki/accessory_white.png");
    private final JsonObject contract;
    private final MisakiSpiritMotion motion = new MisakiSpiritMotion();
    private final Map<Integer, net.minecraft.world.item.ItemStack> displayBlades = new LinkedHashMap<>();
    private final Map<Integer, List<JsonObject>> roots = new LinkedHashMap<>();
    private final Map<Integer, Map<String, List<JsonObject>>> children = new LinkedHashMap<>();

    public MisakiSpiritModel() {
        try (var stream = MisakiSpiritModel.class.getResourceAsStream(
                "/assets/misaki_goryo/misaki_rig/misaki_contract.json")) {
            if (stream == null) throw new IllegalStateException("Missing Misaki model contract");
            contract = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot read Misaki model contract", error);
        }
        for (int stage : new int[]{4, 5}) {
            var stageChildren = new LinkedHashMap<String, List<JsonObject>>();
            var stageRoots = new ArrayList<JsonObject>();
            for (var element : contract.getAsJsonObject("stages").getAsJsonObject("" + stage).getAsJsonArray("bones")) {
                var bone = element.getAsJsonObject();
                if (bone.get("parent").isJsonNull()) stageRoots.add(bone);
                else stageChildren.computeIfAbsent(bone.get("parent").getAsString(), key -> new ArrayList<>()).add(bone);
            }
            roots.put(stage, stageRoots);
            children.put(stage, stageChildren);
        }
    }

    public void render(MisakiSpiritEntity spirit, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light) {
        int stage = spirit.bladeStage() >= 5 ? 5 : 4;
        CapturePose fixed = capturePoseProvider == null ? null : capturePoseProvider.apply(spirit);
        var sampled = fixed == null
            ? motion.sample(spirit.animationName(), spirit.animationTick(partialTick),
                spirit.tickCount + partialTick, spirit.blinking(), spirit.headYaw(partialTick),
                spirit.headPitch(partialTick), spirit.walkAmount())
            : motion.sample(fixed.animation(), fixed.tick(), 0, fixed.closedEyes(), 0, 0, 0);
        if (!sampled.visible()) return;
        String base = stage == 5 ? "skin_misaki_dragon_honor" : "skin_misaki_default";
        ResourceLocation skin = new ResourceLocation("misaki_goryo", "textures/entity/misaki/"
            + base + (sampled.closedEyes() ? "_closed" : "") + ".png");
        for (var root : roots.get(stage)) renderBone(root, stage, sampled, pose, buffers, skin, light);
    }

    private void renderBone(JsonObject bone, int stage, MisakiSpiritMotion.Pose sampled, PoseStack pose,
                            MultiBufferSource buffers, ResourceLocation skin, int light) {
        pose.pushPose();
        String name = bone.get("name").getAsString();
        JsonArray origin = bone.getAsJsonArray("origin");
        pose.translate(number(origin, 0) / 16, number(origin, 1) / 16, number(origin, 2) / 16);
        var localShift = sampled.translation(name);
        pose.translate(localShift.x() / 16, localShift.y() / 16, localShift.z() / 16);
        if (name.equals("root")) {
            var shift = sampled.rootTranslation();
            pose.translate(shift.x() / 16, shift.y() / 16, shift.z() / 16);
        }
        JsonArray rotation = bone.getAsJsonArray("rotationDegrees");
        var animated = sampled.rotation(name);
        pose.mulPose(Axis.ZP.rotationDegrees(number(rotation, 2) + animated.z()));
        pose.mulPose(Axis.YP.rotationDegrees(number(rotation, 1) + animated.y()));
        pose.mulPose(Axis.XP.rotationDegrees(number(rotation, 0) + animated.x()));
        JsonArray scale = bone.getAsJsonArray("scale");
        pose.scale(number(scale, 0), number(scale, 1), number(scale, 2));
        for (var element : bone.getAsJsonArray("cubes")) {
            JsonObject cube = element.getAsJsonObject();
            boolean white = cube.get("texture").getAsString().equals("white");
            VertexConsumer vertices = buffers.getBuffer(RenderType.entityCutoutNoCull(white ? WHITE : skin));
            renderCube(cube, pose, vertices, white ? 4 : 64, light);
        }
        if (name.equals("head")) renderEyes(stage, pose, buffers.getBuffer(RenderType.entityCutoutNoCull(skin)), light);
        var attachments = contract.getAsJsonObject("swordAttachments");
        var waist = attachments.getAsJsonObject("waist");
        // 四、五阶的刀没有鞘：收刀时腰上挂整把刀，拔刀时腰上不画东西。
        if (!sampled.swordDrawn() && name.equals(waist.get("parent").getAsString())) {
            renderBladeAttachment(stage, waist, new String[]{"blade", "handle"}, pose, buffers, light);
        }
        var hand = attachments.getAsJsonObject("hand");
        if (sampled.swordDrawn() && name.equals(hand.get("parent").getAsString())) {
            renderBladeAttachment(stage, hand, new String[]{"blade", "handle"}, pose, buffers, light);
        }
        for (var child : children.get(stage).getOrDefault(name, List.of())) {
            renderBone(child, stage, sampled, pose, buffers, skin, light);
        }
        pose.popPose();
    }

    private void renderBladeAttachment(int stage, JsonObject attachment, String[] groups, PoseStack pose,
                                       MultiBufferSource buffers, int light) {
        String key = stage >= 5 ? "dragon" : "gawoo_awakened";
        var stack = displayBlades.computeIfAbsent(stage, ignored -> BladeCatalog.create(key));
        var modelPath = new ResourceLocation("misaki_goryo", "model/blades/" + key + ".obj");
        var texture = new ResourceLocation("misaki_goryo", "model/blades/" + key + ".png");
        var blade = mods.flammpfeil.slashblade.client.renderer.model.BladeModelManager.getInstance().getModel(modelPath);
        pose.pushPose();
        try {
            JsonArray origin = attachment.getAsJsonArray("origin");
            JsonArray rotation = attachment.getAsJsonArray("rotationDegrees");
            pose.translate(number(origin, 0) / 16, number(origin, 1) / 16, number(origin, 2) / 16);
            pose.mulPose(Axis.ZP.rotationDegrees(number(rotation, 2)));
            pose.mulPose(Axis.YP.rotationDegrees(number(rotation, 1)));
            pose.mulPose(Axis.XP.rotationDegrees(number(rotation, 0)));
            float scale = attachment.get("scale").getAsFloat() / 16;
            pose.scale(scale, scale, scale);
            for (String group : groups) {
                mods.flammpfeil.slashblade.client.renderer.util.BladeRenderState.renderOverrided(
                    stack, blade, group, texture, pose, buffers, light);
                mods.flammpfeil.slashblade.client.renderer.util.BladeRenderState.renderOverridedLuminous(
                    stack, blade, group + "_luminous", texture, pose, buffers, light);
            }
        } finally {
            mods.flammpfeil.slashblade.client.renderer.util.BladeRenderState.resetCol();
            pose.popPose();
        }
    }

    private static void renderCube(JsonObject cube, PoseStack pose, VertexConsumer out, int textureSize, int light) {
        JsonArray from = cube.getAsJsonArray("from"), size = cube.getAsJsonArray("size");
        float inflate = cube.get("inflate").getAsFloat();
        float x0 = (number(from, 0) - inflate) / 16, x1 = (number(from, 0) + number(size, 0) + inflate) / 16;
        float y0 = (number(from, 1) - inflate) / 16, y1 = (number(from, 1) + number(size, 1) + inflate) / 16;
        float z0 = (number(from, 2) - inflate) / 16, z1 = (number(from, 2) + number(size, 2) + inflate) / 16;
        float[] tint = cube.has("tint") ? vector(cube.getAsJsonArray("tint")) : new float[]{1, 1, 1};
        JsonObject uv = cube.getAsJsonObject("faces");
        face(pose, out, new float[][]{{x1,y0,z0},{x0,y0,z0},{x0,y1,z0},{x1,y1,z0}}, uv.getAsJsonArray("north"), textureSize, tint, 0,0,-1,light);
        face(pose, out, new float[][]{{x0,y0,z1},{x1,y0,z1},{x1,y1,z1},{x0,y1,z1}}, uv.getAsJsonArray("south"), textureSize, tint, 0,0,1,light);
        face(pose, out, new float[][]{{x0,y0,z0},{x0,y0,z1},{x0,y1,z1},{x0,y1,z0}}, uv.getAsJsonArray("west"), textureSize, tint, -1,0,0,light);
        face(pose, out, new float[][]{{x1,y0,z1},{x1,y0,z0},{x1,y1,z0},{x1,y1,z1}}, uv.getAsJsonArray("east"), textureSize, tint, 1,0,0,light);
        face(pose, out, new float[][]{{x0,y0,z1},{x0,y0,z0},{x1,y0,z0},{x1,y0,z1}}, uv.getAsJsonArray("up"), textureSize, tint, 0,-1,0,light);
        face(pose, out, new float[][]{{x0,y1,z0},{x0,y1,z1},{x1,y1,z1},{x1,y1,z0}}, uv.getAsJsonArray("down"), textureSize, tint, 0,1,0,light);
    }

    private void renderEyes(int stage, PoseStack pose, VertexConsumer out, int light) {
        var eyes = contract.getAsJsonObject("stages").getAsJsonObject("" + stage)
            .getAsJsonObject("eyes").getAsJsonArray("pixels");
        for (var element : eyes) {
            var pixel = element.getAsJsonObject();
            var from = pixel.getAsJsonArray("from");
            var size = pixel.getAsJsonArray("size");
            float x0 = number(from,0)/16, x1 = (number(from,0)+number(size,0))/16;
            float y0 = number(from,1)/16, y1 = (number(from,1)+number(size,1))/16;
            float z = number(from,2)/16;
            face(pose,out,new float[][]{{x1,y0,z},{x0,y0,z},{x0,y1,z},{x1,y1,z}},
                pixel.getAsJsonArray("sourceUv"),64,vector(pixel.getAsJsonArray("tint")),0,0,-1,light);
        }
    }

    private static void face(PoseStack pose, VertexConsumer out, float[][] points, JsonArray uv,
                             int textureSize, float[] tint, float nx,float ny,float nz,int light) {
        float u0=number(uv,0)/textureSize, v0=number(uv,1)/textureSize;
        float u1=(number(uv,0)+number(uv,2))/textureSize;
        float v1=(number(uv,1)+number(uv,3))/textureSize;
        float[][] coords={{u1,v0},{u0,v0},{u0,v1},{u1,v1}};
        for (int index=0;index<4;index++) {
            float[] point=points[index];
            out.vertex(pose.last().pose(),point[0],point[1],point[2])
                .color(tint[0],tint[1],tint[2],1).uv(coords[index][0],coords[index][1])
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light)
                .normal(pose.last().normal(),nx,ny,nz).endVertex();
        }
    }
    private static float number(JsonArray values,int index) { return values.get(index).getAsFloat(); }
    private static float[] vector(JsonArray values) { return new float[]{number(values,0),number(values,1),number(values,2)}; }
}
