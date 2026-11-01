package com.luckystar.misakigoryo;

import mods.flammpfeil.slashblade.entity.EntityAbstractSummonedSword;
import mods.flammpfeil.slashblade.slasharts.Drive;
import mods.flammpfeil.slashblade.slasharts.JudgementCut;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import mods.flammpfeil.slashblade.event.SlashBladeEvent;
import mods.flammpfeil.slashblade.registry.ComboStateRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Vector3f;

public final class BladeCombat {
    private static final List<ActiveArt> ACTIVE = new ArrayList<>();
    private static final Map<LivingEntity, PendingHit> PENDING = new WeakHashMap<>();
    private static final DustParticleOptions PINK = new DustParticleOptions(new Vector3f(1, 0.45F, 0.7F), 1.1F);
    private static final DustParticleOptions BLUE = new DustParticleOptions(new Vector3f(0.08F, 0.65F, 1), 1.3F);

    private BladeCombat() {}

    public static boolean activate(LivingEntity owner, ItemStack blade, String key) {
        if (!(owner.level() instanceof ServerLevel) || BladeCatalog.stage(blade) < 2 || !BladeCatalog.matches(blade, key)) return false;
        long now = now(owner);
        CompoundTag state = blade.getOrCreateTag();
        if (state.getLong("GoryoArtReady") > now || owner.getPersistentData().getLong("GoryoArtReady") > now
            || ACTIVE.size() >= GoryoConfig.MAX_ACTIVE_ARTS.get()) return false;
        int cooldown = switch (key) {
            case "sakura" -> GoryoConfig.SAKURA_COOLDOWN.get();
            case "gawoo" -> GoryoConfig.GAWOO_COOLDOWN.get();
            default -> GoryoConfig.DRAGON_COOLDOWN.get();
        };
        state.putLong("GoryoArtReady", now + cooldown);
        owner.getPersistentData().putLong("GoryoArtReady", now + cooldown);
        if (!state.hasUUID("GoryoBladeUuid")) state.putUUID("GoryoBladeUuid", UUID.randomUUID());
        boolean empowered = BladeCatalog.stage(blade) >= 3 && state.getInt("GoryoSpirit") >= 3;
        if (empowered) state.putInt("GoryoSpirit", 0);
        if (key.equals("sakura") || key.equals("gawoo")) safeDash(owner, 3);
        var art = new ActiveArt(owner, blade, key, now, empowered);
        art.fullMight = key.equals("dragon") && DragonBlade.consumeFullMight(owner);
        ACTIVE.add(art);
        art.level.playSound(null, owner.blockPosition(), GoryoSounds.DRAW.get(), SoundSource.PLAYERS, 0.8F, 1);
        if (BladeCatalog.stage(blade) >= 4)
            art.level.playSound(null, owner.blockPosition(), GoryoSounds.DIMENSION_CUT.get(), SoundSource.PLAYERS, 0.8F, 1);
        sjapAttack(art);
        if (key.equals("dragon")) {
            freeze(art);
            slashLines(art, 6, false);
        } else {
            pulse(art);
            art.stage = 1;
        }
        owner.getPersistentData().putLong("GoryoArt", now);
        GoryoSpiritEvents.specialAttack(owner, blade);
        return true;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var iterator = ACTIVE.iterator();
        while (iterator.hasNext()) {
            ActiveArt art = iterator.next();
            long age = now(art.owner) - art.started;
            ItemStack held = art.owner.getMainHandItem();
            if (!art.owner.isAlive() || art.owner.isRemoved() || art.owner.level() != art.level || age < 0 || age > 80
                || !held.hasTag() || !held.getTag().hasUUID("GoryoBladeUuid")
                || !held.getTag().getUUID("GoryoBladeUuid").equals(art.bladeId) || !BladeCatalog.matches(held, art.key)
                || art.stage >= art.pulses()) {
                if (art.stage >= art.pulses()) art.level.playSound(null, art.owner.blockPosition(),
                    GoryoSounds.SHEATH.get(), SoundSource.PLAYERS, 0.8F, 1.25F);
                art.entities.forEach(net.minecraft.world.entity.Entity::discard);
                iterator.remove();
                continue;
            }
            if (art.key.equals("dragon") && age < 20) {
                for (FrozenTarget frozen : art.frozen) {
                    if (!frozen.target.isAlive() || frozen.target.level() != art.level) continue;
                    frozen.target.setDeltaMovement(Vec3.ZERO);
                    if (frozen.target.position().distanceToSqr(frozen.position) > 0.01)
                        frozen.target.teleportTo(frozen.position.x, frozen.position.y, frozen.position.z);
                }
            }
            if (age >= art.stage * 10L + (art.key.equals("dragon") ? 20 : 0)) {
                pulse(art);
                art.stage++;
            }
        }
        PENDING.entrySet().removeIf(entry -> now(entry.getKey()) - entry.getValue().tick > 2);
    }

    // 只用拔刀剑本体的剑气和次元斩做表现，伤害统一由 pulse() 分段结算。
    private static void sjapAttack(ActiveArt art) {
        int color = BladeCatalog.BLADES.get(art.key).color();
        float yaw = art.owner.getYRot();
        int count = art.key.equals("sakura") ? 3 : art.key.equals("gawoo") ? 5 : 1;
        try {
            for (int ray = 0; ray < count; ray++) {
                art.owner.setYRot(yaw + (ray - (count - 1) / 2.0F) * (art.key.equals("gawoo") ? 25 : 35));
                float roll = art.key.equals("gawoo_awakened") || art.key.equals("dragon") ? 90 : -15 + ray * 15;
                mark(art, Drive.doSlash(art.owner, roll, 12, color, Vec3.ZERO, false, 0,
                    mods.flammpfeil.slashblade.util.KnockBacks.cancel, 0.8F));
            }
        } finally {
            art.owner.setYRot(yaw);
        }
        if (art.key.equals("gawoo_awakened") || art.key.equals("dragon")) mark(art, JudgementCut.doJudgementCut(art.owner));
        for (EntityAbstractSummonedSword sword : art.level.getEntitiesOfClass(EntityAbstractSummonedSword.class,
            art.owner.getBoundingBox().inflate(5), entity -> entity.getOwner() == art.owner)) {
            mark(art, sword);
        }
    }

    private static void mark(ActiveArt art, net.minecraft.world.entity.Entity effect) {
        if (effect == null) return;
        effect.getPersistentData().putUUID("GoryoArtOwner", art.owner.getUUID());
        if (!art.entities.contains(effect)) art.entities.add(effect);
    }

    private static long now(LivingEntity owner) {
        return owner.level().getGameTime();
    }

    // 特殊攻击每段伤害跟玩家当前攻击力走，配置值是倍率。
    private static double artDamage(ActiveArt art, double multiplier) {
        return Math.max(1, art.owner.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) * multiplier);
    }

    private static void pulse(ActiveArt art) {
        // Each damaging beat can build one layer; extra targets in that beat cannot.
        art.chargeGranted = false;
        Vec3 center = art.owner.position();
        double radius;
        double damage;
        boolean fan = art.key.equals("sakura") || art.key.equals("gawoo");
        if (fan) {
            // 10-31：用户反馈瞄准了也打不着，判定范围放大到约两倍。
            radius = art.empowered ? 12 : 10;
            damage = artDamage(art, GoryoConfig.SAKURA_DAMAGE.get()) * (art.empowered ? 1.7 : 1);
            petalFan(art, radius);
        } else if (art.key.equals("gawoo_awakened")) {
            radius = art.empowered ? 11 : 9;
            // 判定圈往准星方向挪 3 格，瞄哪打哪。
            center = center.add(art.direction.scale(3));
            damage = artDamage(art, GoryoConfig.GAWOO_DAMAGE.get()) * (art.stage == 3 ? 2 : 1) * (art.empowered ? 1.5 : 1);
            slashLines(art, radius, true);
            if (art.stage == 3) art.level.sendParticles(ParticleTypes.CHERRY_LEAVES,
                center.x, center.y + 2, center.z, 18, radius * 0.5, 0.2, radius * 0.5, 0.02);
        } else {
            double distance = art.stage < 4 ? 6 : 12;
            Vec3 proposed = art.origin.add(art.direction.scale(distance)).add(0, 1, 0);
            var clip = art.level.clip(new ClipContext(art.origin.add(0, 1, 0), proposed,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, art.owner));
            center = clip.getType() == HitResult.Type.MISS ? proposed : clip.getLocation().subtract(art.direction.scale(0.3));
            radius = art.stage < 4 ? (art.empowered ? 10 : 9) : 11;
            damage = artDamage(art, GoryoConfig.DRAGON_DAMAGE.get()) * (art.stage < 4 ? 1.6 : 2.5 * (art.fullMight ? 2 : 1));
            if (art.stage < 4) slashLines(art, radius, true);
            else {
                dragonTrail(art, center);
                art.level.playSound(null, art.owner.blockPosition(), GoryoSounds.DRAGON_ROAR.get(), SoundSource.PLAYERS, 0.9F, 1.1F);
            }
            if (art.empowered) art.level.sendParticles(ParticleTypes.CHERRY_LEAVES, center.x, center.y + 1,
                center.z, 28, radius * 0.5, 0.8, radius * 0.5, 0.06);
        }
        Vec3 hitCenter = center;
        List<LivingEntity> candidates = art.level.getEntitiesOfClass(LivingEntity.class,
            new AABB(center.subtract(radius, 3, radius), center.add(radius, 4, radius)),
            target -> CombatRules.canHarm(art.owner, target));
        candidates.sort(Comparator.comparingDouble(target -> target.distanceToSqr(hitCenter)));
        int tested = 0;
        for (LivingEntity target : candidates) {
            if (tested++ >= GoryoConfig.MAX_TARGETS.get()) break;
            Vec3 offset = target.position().subtract(center);
            if (offset.horizontalDistanceSqr() > radius * radius || !art.owner.hasLineOfSight(target)) continue;
            if (fan && offset.horizontalDistanceSqr() > 0.01 && art.direction.dot(offset.multiply(1, 0, 1).normalize()) < 0.5) continue;
            if (art.key.equals("dragon") && art.stage == 4) {
                Vec3 forward = target.position().subtract(art.origin).multiply(1, 0, 1);
                double along = forward.dot(art.direction);
                if (along < 0 || along > 25 || forward.subtract(art.direction.scale(along)).lengthSqr() > 16) continue;
            }
            float previous = target.getHealth() + target.getAbsorptionAmount();
            DamageSource source = art.owner instanceof Player player ? art.level.damageSources().playerAttack(player)
                : art.level.damageSources().mobAttack(art.owner);
            boolean damaged = target.hurt(source, (float)damage);
            if (damaged && target.getHealth() + target.getAbsorptionAmount() < previous
                && BladeCatalog.stage(art.blade) >= 3 && !art.empowered && !art.chargeGranted) {
                addSpirit(art.owner, art.blade, target.getUUID());
                art.chargeGranted = true;
            }
        }
    }

    @SubscribeEvent
    public static void identifyNativeBlade(SlashBladeEvent.UpdateEvent event) {
        if (event.getLevel().isClientSide) return;
        String key = BladeCatalog.keyOf(event.getBlade());
        if (!key.isEmpty()) {
            event.getBlade().getOrCreateTag().putString("GoryoBlade", key);
            event.getBlade().getOrCreateTag().putString("goryo", key);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void visualProjectilesDoNotDoubleHit(net.minecraftforge.event.entity.living.LivingAttackEvent event) {
        var direct = event.getSource().getDirectEntity();
        if (direct != null && direct.getPersistentData().hasUUID("GoryoArtOwner")) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void confirmDamage(LivingDamageEvent event) {
        if (event.getEntity().level().isClientSide || event.getAmount() <= 0
            || !(event.getSource().getEntity() instanceof LivingEntity owner)
            || !CombatRules.canHarm(owner, event.getEntity()) || isActive(owner)
            || BladeCatalog.stage(owner.getMainHandItem()) < 3) return;
        PENDING.put(owner, new PendingHit(event.getEntity().getUUID(), now(owner)));
    }

    @SubscribeEvent
    public static void onNativeHit(SlashBladeEvent.HitEvent event) {
        LivingEntity owner = event.getUser();
        if (owner.level().isClientSide || isActive(owner) || BladeCatalog.stage(event.getBlade()) < 3) return;
        PendingHit hit = PENDING.remove(owner);
        if (hit == null || !hit.target.equals(event.getTarget().getUUID()) || now(owner) - hit.tick > 1) return;
        var combo = event.getSlashBladeState().getComboSeq();
        if (event.getSlashBladeState().isCharged(owner) || combo.equals(ComboStateRegistry.COMBO_A5.getId())
            || combo.equals(ComboStateRegistry.COMBO_B7.getId()) || combo.equals(ComboStateRegistry.SAKURA_END_FINISH.getId())
            || combo.equals(ComboStateRegistry.SAKURA_END_FINISH_AIR.getId())
            || combo.equals(ComboStateRegistry.AERIAL_CLEAVE_LANDING.getId()))
            owner.getPersistentData().putLong("GoryoStrongHit", now(owner));
        CompoundTag tag = event.getBlade().getOrCreateTag();
        long next = tag.getLong("GoryoSpiritHitReady");
        if (next > now(owner)) return;
        addSpirit(owner, event.getBlade(), event.getTarget().getUUID());
        tag.putLong("GoryoSpiritHitReady", now(owner) + 10);
    }

    private static void addSpirit(LivingEntity owner, ItemStack blade, UUID target) {
        CompoundTag tag = blade.getOrCreateTag();
        if (!tag.hasUUID("GoryoSpiritTarget") || !target.equals(tag.getUUID("GoryoSpiritTarget"))) tag.putInt("GoryoSpirit", 0);
        tag.putUUID("GoryoSpiritTarget", target);
        tag.putInt("GoryoSpirit", Math.min(3, tag.getInt("GoryoSpirit") + 1));
    }

    private static void freeze(ActiveArt art) {
        int count = 0;
        for (LivingEntity target : art.level.getEntitiesOfClass(LivingEntity.class,
            art.owner.getBoundingBox().inflate(7, 3, 7), entity -> CombatRules.canHarm(art.owner, entity))) {
            if (count++ >= GoryoConfig.MAX_TARGETS.get()) break;
            if (!art.owner.hasLineOfSight(target)) continue;
            art.frozen.add(new FrozenTarget(target, target.position()));
            target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20, 9, false, false));
            target.setDeltaMovement(Vec3.ZERO);
        }
    }

    private static void slashLines(ActiveArt art, double radius, boolean shatter) {
        Vec3 center = art.owner.position().add(art.direction.scale(3)).add(0, 1.2, 0);
        Vec3 side = new Vec3(-art.direction.z, 0, art.direction.x);
        for (int line = 0; line < 5; line++) {
            double height = (line - 2) * 0.5;
            for (int sample = 0; sample < 16; sample++) {
                double offset = (sample / 15.0 - 0.5) * radius * 2;
                Vec3 point = center.add(side.scale(offset)).add(art.direction.scale(height * 0.7))
                    .add(0, height + Math.sin(sample * 0.25 + line) * 0.12, 0);
                art.level.sendParticles(BLUE, point.x, point.y, point.z, 1, 0, 0, 0, 0);
                if (shatter && sample % 4 == 0) art.level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                    point.x, point.y, point.z, 2, 0.06, 0.08, 0.06, 0.05);
            }
        }
    }

    public static void safeDash(LivingEntity owner, double distance) {
        if (!owner.onGround()) return;
        Vec3 direction = owner.getLookAngle().multiply(1, 0, 1).normalize();
        Vec3 start = owner.position();
        Vec3 destination = start;
        for (double traveled = 0.25; traveled <= Math.min(3, distance); traveled += 0.25) {
            Vec3 next = start.add(direction.scale(traveled));
            BlockPos feet = BlockPos.containing(next);
            if (!owner.level().hasChunkAt(feet) || !owner.level().getWorldBorder().isWithinBounds(feet)
                || !owner.level().noCollision(owner, owner.getBoundingBox().move(next.subtract(start)))
                || !owner.level().getFluidState(feet).isEmpty()
                || owner.level().getBlockState(BlockPos.containing(next.add(0, -0.15, 0))).getCollisionShape(owner.level(), feet.below()).isEmpty()) break;
            destination = next;
        }
        if (!destination.equals(start)) owner.teleportTo(destination.x, destination.y, destination.z);
    }

    private static void petalFan(ActiveArt art, double radius) {
        double heading = Math.atan2(art.direction.z, art.direction.x);
        double span = art.key.equals("sakura") ? Math.PI * 2 / 3 : Math.PI * 2;
        for (int sample = 0; sample < 24; sample++) {
            double angle = heading - span / 2 + span * sample / 23;
            Vec3 position = art.owner.position().add(Math.cos(angle) * radius, 1, Math.sin(angle) * radius);
            art.level.sendParticles(PINK, position.x, position.y, position.z, 1, 0, 0.15, 0, 0);
        }
    }

    private static void dragonTrail(ActiveArt art, Vec3 center) {
        Vec3 side = new Vec3(-art.direction.z, 0, art.direction.x);
        for (int sample = 0; sample < 36; sample++) {
            double turn = sample * Math.PI / 10 + art.stage * 0.7;
            double taper = 0.25 + sample / 60.0;
            Vec3 point = center.add(art.direction.scale((sample - 30) * 0.17))
                .add(side.scale(Math.sin(turn) * taper)).add(0, Math.cos(turn) * taper * 0.55, 0);
            art.level.sendParticles(BLUE, point.x, point.y, point.z, 1, 0, 0, 0, 0);
        }
        Vec3 head = center.add(art.direction.scale(1.1));
        for (int horn : new int[]{-1, 1}) {
            for (int point = 0; point < 5; point++) {
                Vec3 tip = head.add(side.scale(horn * (0.3 + point * 0.07))).add(0, 0.2 + point * 0.12, 0);
                art.level.sendParticles(BLUE, tip.x, tip.y, tip.z, 1, 0, 0, 0, 0);
            }
        }
        art.level.sendParticles(ParticleTypes.ELECTRIC_SPARK, head.x, head.y, head.z, 10, 0.7, 0.5, 0.7, 0.02);
    }

    private static boolean isActive(LivingEntity owner) {
        return ACTIVE.stream().anyMatch(art -> art.owner == owner);
    }

    public static void clear() { ACTIVE.forEach(art -> art.entities.forEach(net.minecraft.world.entity.Entity::discard)); ACTIVE.clear(); PENDING.clear(); }

    private record PendingHit(UUID target, long tick) {}
    private record FrozenTarget(LivingEntity target, Vec3 position) {}

    private static final class ActiveArt {
        private final LivingEntity owner;
        private final ServerLevel level;
        private final ItemStack blade;
        private final UUID bladeId;
        private final String key;
        private final long started;
        private final Vec3 origin;
        private final Vec3 direction;
        private final boolean empowered;
        private final List<net.minecraft.world.entity.Entity> entities = new ArrayList<>();
        private final List<FrozenTarget> frozen = new ArrayList<>();
        private int stage;
        private boolean chargeGranted;
        private boolean fullMight;

        private ActiveArt(LivingEntity owner, ItemStack blade, String key, long started, boolean empowered) {
            this.owner = owner;
            this.level = (ServerLevel)owner.level();
            this.blade = blade;
            this.bladeId = blade.getOrCreateTag().getUUID("GoryoBladeUuid");
            this.key = key;
            this.started = started;
            this.origin = owner.position();
            Vec3 horizontal = owner.getLookAngle().multiply(1, 0, 1);
            this.direction = horizontal.lengthSqr() < 0.01 ? new Vec3(0, 0, 1) : horizontal.normalize();
            this.empowered = empowered;
        }

        private int pulses() { return key.equals("sakura") || key.equals("gawoo") ? 3 : key.equals("gawoo_awakened") ? 4 : 5; }
    }
}
