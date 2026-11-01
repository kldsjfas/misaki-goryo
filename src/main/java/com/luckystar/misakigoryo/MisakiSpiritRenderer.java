package com.luckystar.misakigoryo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public final class MisakiSpiritRenderer extends EntityRenderer<MisakiSpiritEntity> {
    private final MisakiSpiritModel model = new MisakiSpiritModel();
    public MisakiSpiritRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0.2F;
    }

    @Override public void render(MisakiSpiritEntity spirit, float yaw, float partialTick,
                                 PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        if (ownView(spirit)) {
            // 自己看自己的刀灵：钉在镜头右上角，转过来面向玩家，不跟服务器位置走，不会拖影
            var camera = entityRenderDispatcher.camera;
            var look = camera.getLookVector(); var left = camera.getLeftVector(); var up = camera.getUpVector();
            // 右上角：前方 2 格、右边 1.3 格、上方 0.45 格（这里是脚的位置，身体往上长，整体落在屏幕右上）
            var spot = camera.getPosition().add(look.x() * 2.0 - left.x() * 1.3 + up.x() * .45,
                look.y() * 2.0 - left.y() * 1.3 + up.y() * .45, look.z() * 2.0 - left.z() * 1.3 + up.z() * .45);
            var here = spirit.getPosition(partialTick);
            pose.translate(spot.x - here.x, spot.y - here.y, spot.z - here.z);
            // 面向镜头：算出从刀灵指向镜头的朝向（MC 的偏航角），再按普通渲染同样的 180-朝向 摆模型
            var toCamera = camera.getPosition().subtract(spot);
            float facing = (float)Math.toDegrees(Math.atan2(-toCamera.x, toCamera.z));
            pose.mulPose(Axis.YP.rotationDegrees(180 - facing));
            pose.scale(-0.32F,-0.32F,0.32F);
        } else {
            pose.mulPose(Axis.YP.rotationDegrees(180 - net.minecraft.util.Mth.rotLerp(partialTick, spirit.yRotO, spirit.getYRot())));
            pose.scale(-0.62F,-0.62F,0.62F);
        }
        model.render(spirit,partialTick,pose,buffers,light);
        pose.popPose();
        if (!spirit.bubbleKey().isEmpty()) bubble(spirit,pose,buffers,light);
        super.render(spirit,yaw,partialTick,pose,buffers,light);
    }

    private static boolean ownView(MisakiSpiritEntity spirit) {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        return player != null && player.getUUID().equals(spirit.ownerId())
            && net.minecraft.client.Minecraft.getInstance().options.getCameraType().isFirstPerson();
    }

    @Override public boolean shouldRender(MisakiSpiritEntity spirit, net.minecraft.client.renderer.culling.Frustum frustum,
                                          double x, double y, double z) {
        return ownView(spirit) || super.shouldRender(spirit, frustum, x, y, z);
    }

    private void bubble(MisakiSpiritEntity spirit,PoseStack pose,MultiBufferSource buffers,int light) {
        if (entityRenderDispatcher.distanceToSqr(spirit)>24*24) return;
        var lines=getFont().split(Component.translatable(spirit.bubbleKey()),190);
        pose.pushPose();
        pose.translate(0,spirit.bladeStage()>=5?.88:.66,0);
        pose.mulPose(entityRenderDispatcher.cameraOrientation());
        pose.scale(-0.018F,-0.018F,0.018F);
        for(int index=0;index<Math.min(4,lines.size());index++) {
            var line=lines.get(index);
            getFont().drawInBatch(line,-getFont().width(line)/2.0F,index*10,0xFFF9F3,
                false,pose.last().pose(),buffers,Font.DisplayMode.NORMAL,0x990F1726,light);
        }
        pose.popPose();
    }
    @Override public ResourceLocation getTextureLocation(MisakiSpiritEntity entity) {
        return new ResourceLocation("misaki_goryo","textures/entity/misaki/"
            +(entity.bladeStage()>=5?"skin_misaki_dragon_honor.png":"skin_misaki_default.png"));
    }
}
