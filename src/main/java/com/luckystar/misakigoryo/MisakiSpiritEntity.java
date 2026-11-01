package com.luckystar.misakigoryo;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

/** A harmless visual companion with a neck anchor at the owner's eye height. */
public final class MisakiSpiritEntity extends Entity {
    private static final EntityDataAccessor<Optional<UUID>> OWNER = SynchedEntityData.defineId(
        MisakiSpiritEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Integer> STAGE = SynchedEntityData.defineId(
        MisakiSpiritEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> BUBBLE = SynchedEntityData.defineId(
        MisakiSpiritEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Long> BUBBLE_UNTIL = SynchedEntityData.defineId(
        MisakiSpiritEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Long> DRAW_UNTIL = SynchedEntityData.defineId(
        MisakiSpiritEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<String> ANIMATION = SynchedEntityData.defineId(
        MisakiSpiritEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Long> ANIMATION_START = SynchedEntityData.defineId(
        MisakiSpiritEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Float> HEAD_YAW = SynchedEntityData.defineId(
        MisakiSpiritEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> HEAD_PITCH = SynchedEntityData.defineId(
        MisakiSpiritEntity.class, EntityDataSerializers.FLOAT);
    private static final Map<String, Integer> DURATIONS = Map.of(
        "wave", 36, "hide", 20, "celebrate", 36, "worry", 44, "yawn", 60, "sa", 28);

    private Vec3 networkTarget = Vec3.ZERO;
    private float networkYaw, networkPitch;
    private int interpolationTicks, stillTicks, nextBlink = -1, blinkUntil;
    private float previousHeadYaw, previousHeadPitch, headYaw, headPitch;
    private Vec3 previousOwnerPosition;
    private float previousOwnerYaw;
    private long departureAt;
    private boolean departureParticlesSent;

    public MisakiSpiritEntity(EntityType<? extends MisakiSpiritEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    @Override protected void defineSynchedData() {
        entityData.define(OWNER, Optional.empty());
        entityData.define(STAGE, 4);
        entityData.define(BUBBLE, "");
        entityData.define(BUBBLE_UNTIL, 0L);
        entityData.define(DRAW_UNTIL, 0L);
        entityData.define(ANIMATION, "idle");
        entityData.define(ANIMATION_START, 0L);
        entityData.define(HEAD_YAW, 0F);
        entityData.define(HEAD_PITCH, 0F);
    }

    public void bind(ServerPlayer owner, int stage) {
        entityData.set(OWNER, Optional.of(owner.getUUID()));
        entityData.set(STAGE, stage);
        setPos(owner.position().add(followOffset(owner)));
        setYRot(owner.getYRot());
        previousOwnerPosition = owner.position();
        previousOwnerYaw = owner.getYRot();
    }

    public UUID ownerId() { return entityData.get(OWNER).orElse(null); }
    public int bladeStage() { return entityData.get(STAGE); }
    public void setBladeStage(int stage) { entityData.set(STAGE, stage); }
    public String bubbleKey() { return level().getGameTime() < entityData.get(BUBBLE_UNTIL) ? entityData.get(BUBBLE) : ""; }
    public boolean drawing() { return level().getGameTime() < entityData.get(DRAW_UNTIL); }
    public boolean blinking() { return tickCount < blinkUntil; }
    public float headYaw(float partialTick) { return Mth.rotLerp(partialTick, previousHeadYaw, headYaw); }
    public float headPitch(float partialTick) { return Mth.lerp(partialTick, previousHeadPitch, headPitch); }
    public float walkAmount() {
        Player owner = ownerId() == null ? null : level().getPlayerByUUID(ownerId());
        return owner == null ? 0 : Mth.clamp((float)owner.getDeltaMovement().horizontalDistance() * 5, 0, 1);
    }
    public String animationName() {
        String animation = entityData.get(ANIMATION);
        return level().getGameTime() - entityData.get(ANIMATION_START) < DURATIONS.getOrDefault(animation, 0)
            ? animation : "idle";
    }
    public float animationTick(float partialTick) {
        return Math.max(0, level().getGameTime() - entityData.get(ANIMATION_START) + partialTick);
    }
    public long animationStartedAt() { return entityData.get(ANIMATION_START); }
    public boolean playAnimation(String animation) {
        if (level().isClientSide || !DURATIONS.containsKey(animation)) return false;
        String current = animationName();
        if (!current.equals("idle") && priority(animation) < priority(current)) return false;
        if (current.equals(animation)) return false;
        entityData.set(ANIMATION, animation);
        entityData.set(ANIMATION_START, level().getGameTime());
        return true;
    }
    private static int priority(String animation) {
        return switch (animation) {
            case "hide" -> 5;
            case "sa" -> 4;
            case "worry" -> 3;
            case "celebrate", "wave" -> 2;
            default -> 1;
        };
    }
    public void bubble(String key, int ticks) {
        entityData.set(BUBBLE, key);
        entityData.set(BUBBLE_UNTIL, level().getGameTime() + ticks);
    }
    public void drawSword() {
        entityData.set(DRAW_UNTIL, level().getGameTime() + 28);
        playAnimation("sa");
    }
    /** Keep the existing departure grace, then hop during its final 20 ticks. */
    public void beginDeparture(long disappearsAt) {
        departureAt = disappearsAt;
        departureParticlesSent = false;
    }
    public void cancelDeparture() {
        departureAt = 0;
        departureParticlesSent = false;
        if (entityData.get(ANIMATION).equals("hide")) {
            entityData.set(ANIMATION, "idle");
            entityData.set(ANIMATION_START, level().getGameTime());
        }
    }

    @Override public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps, boolean teleport) {
        if (!level().isClientSide || teleport || position().distanceToSqr(x, y, z) > 64) {
            super.lerpTo(x, y, z, yaw, pitch, steps, teleport);
            interpolationTicks = 0;
            return;
        }
        networkTarget = new Vec3(x, y, z);
        networkYaw = yaw;
        networkPitch = pitch;
        interpolationTicks = Math.max(1, Math.min(3, steps));
    }

    @Override public void tick() {
        super.tick();
        if (level().isClientSide) {
            tickClient();
            return;
        }
        UUID ownerId = ownerId();
        Player owner = ownerId == null ? null : level().getPlayerByUUID(ownerId);
        if (!(owner instanceof ServerPlayer player) || !player.isAlive()
                || GoryoBladeService.stage(player.getMainHandItem()) < 4
                || (GoryoSpiritEvents.hidden(player) && !GoryoSpiritEvents.departing(player))) {
            discard();
            return;
        }
        setBladeStage(GoryoBladeService.stage(player.getMainHandItem()));
        Vec3 target = player.position().add(followOffset(player));
        setPos(position().distanceToSqr(target) > 64 ? target : position().lerp(target, .22));
        updateGaze(player);
        if (departureAt > 0 && level().getGameTime() >= departureAt - 20) playAnimation("hide");
        if (departureAt > 0 && level().getGameTime() >= departureAt - 2 && !departureParticlesSent) {
            departureParticlesSent = true;
            ((ServerLevel)level()).sendParticles(ParticleTypes.CHERRY_LEAVES, getX(), getY(), getZ(), 6, .12, .12, .12, .01);
        }
        setDeltaMovement(Vec3.ZERO);
    }

    private void tickClient() {
        if (interpolationTicks > 0) {
            double amount = 1.0 / interpolationTicks--;
            setPos(position().lerp(networkTarget, amount));
            setYRot(Mth.rotLerp((float)amount, getYRot(), networkYaw));
            setXRot(Mth.lerp((float)amount, getXRot(), networkPitch));
        }
        previousHeadYaw = headYaw;
        previousHeadPitch = headPitch;
        headYaw = Mth.rotLerp(.25F, headYaw, entityData.get(HEAD_YAW));
        headPitch = Mth.lerp(.25F, headPitch, entityData.get(HEAD_PITCH));
        if (nextBlink < 0) nextBlink = tickCount + 60 + random.nextInt(61);
        if (tickCount >= nextBlink) {
            blinkUntil = tickCount + 3;
            nextBlink = tickCount + 60 + random.nextInt(61);
        }
    }

    private void updateGaze(ServerPlayer owner) {
        boolean still = previousOwnerPosition != null
            && owner.position().distanceToSqr(previousOwnerPosition) < .0001
            && Math.abs(Mth.wrapDegrees(owner.getYRot() - previousOwnerYaw)) < 1;
        stillTicks = still ? Math.min(120, stillTicks + 1) : 0;
        previousOwnerPosition = owner.position();
        previousOwnerYaw = owner.getYRot();
        float desiredYaw = Mth.wrapDegrees(owner.getYRot() - getYRot());
        float desiredPitch = owner.getXRot();
        if (stillTicks >= 60 && !drawing() && departureAt == 0) {
            Vec3 face = owner.getEyePosition().subtract(position().add(0, .62 * 6 / 16, 0));
            float towardOwner = (float)Math.toDegrees(Math.atan2(-face.x, face.z));
            setYRot(Mth.rotLerp(.12F, getYRot(), towardOwner));
            desiredYaw = Mth.wrapDegrees(towardOwner - getYRot());
            desiredPitch = -(float)Math.toDegrees(Math.atan2(face.y, face.horizontalDistance()));
        } else {
            setYRot(Mth.rotLerp(.25F, getYRot(), owner.getYRot()));
            desiredYaw = Mth.wrapDegrees(owner.getYRot() - getYRot());
        }
        desiredYaw = Mth.clamp(desiredYaw, -75, 75);
        desiredPitch = Mth.clamp(desiredPitch, -30, 30);
        if (Math.abs(entityData.get(HEAD_YAW) - desiredYaw) > .5F) entityData.set(HEAD_YAW, desiredYaw);
        if (Math.abs(entityData.get(HEAD_PITCH) - desiredPitch) > .5F) entityData.set(HEAD_PITCH, desiredPitch);
    }

    // 别人看到的位置：玩家右前上方。自己第一人称时渲染器会直接钉在镜头右上角
    public static Vec3 anchor(Player player) {
        return player.position().add(followOffset(player));
    }

    private static Vec3 followOffset(Player player) {
        double yaw = Math.toRadians(player.getYRot());
        Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        return new Vec3(0, player.getEyeHeight() + .1, 0).add(forward.scale(1.6)).add(right.scale(1.1));
    }

    @Override public boolean hurt(DamageSource source, float amount) { return false; }
    @Override public boolean isPickable() { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public net.minecraft.world.phys.AABB getBoundingBoxForCulling() {
        return new net.minecraft.world.phys.AABB(getX()-1.2, getY()-1.0, getZ()-1.2,
            getX()+1.2, getY()+1.3, getZ()+1.2);
    }
    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.hasUUID("Owner")) entityData.set(OWNER, Optional.of(tag.getUUID("Owner")));
        entityData.set(STAGE, Math.max(4, Math.min(5, tag.getInt("BladeStage"))));
    }
    @Override protected void addAdditionalSaveData(CompoundTag tag) {
        if (ownerId() != null) tag.putUUID("Owner", ownerId());
        tag.putInt("BladeStage", bladeStage());
    }
    @Override public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
