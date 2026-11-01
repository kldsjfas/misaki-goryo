package com.luckystar.misakigoryo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

// 真龙御礼的被动，按原版拔刀剑的尺度：龙威叠层增伤、每第三刀引青龙雷、每次伤害附带目标最大生命的少量真实伤害、
// 持刀减伤四分之一并免疫环境伤害、致命伤半血复活；满层龙威让特殊攻击最后一斩翻倍。
public final class DragonBlade {
    public static final int MAX_MIGHT = 10;
    private static final double MIGHT_BONUS = .04;
    private static final long MIGHT_DECAY_TICKS = 120;
    private static final int THUNDER_EVERY = 3;
    private static final int THUNDER_TARGETS = 4;
    private static final double THUNDER_RANGE = 8;
    private static final double THUNDER_SHARE = .5;
    private static final float DAMAGE_TAKEN = .75F;
    private static final double VERDICT_SHARE = .02;
    private static final double VERDICT_BOSS_SHARE = .005;
    private static final long REVIVE_COOLDOWN_TICKS = 18000;
    private static final String REVIVE_KEY = "goryo_dragon_revive_ready";
    private static final double LIFESTEAL = .02;
    private static final Map<UUID, Might> MIGHT = new HashMap<>();
    private static boolean thundering;

    private static final class Might {
        private int stacks;
        private int hits;
        private long lastHit;
    }

    private DragonBlade() {
    }

    public static boolean holding(LivingEntity entity) {
        return entity != null && BladeCatalog.matches(entity.getMainHandItem(), "dragon");
    }

    public static int might(LivingEntity owner) {
        Might might = MIGHT.get(owner.getUUID());
        if (might == null || owner.level().getGameTime() - might.lastHit > MIGHT_DECAY_TICKS) return 0;
        return might.stacks;
    }

    // 特殊攻击开始时调用：满层就消耗掉，让最后一斩翻倍。
    public static boolean consumeFullMight(LivingEntity owner) {
        if (might(owner) < MAX_MIGHT) return false;
        MIGHT.remove(owner.getUUID());
        return true;
    }

    // 记层数并按层数增伤；雷击本身不叠层也不吃增伤。
    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onHit(LivingHurtEvent event) {
        if (!(event.getSource().getEntity() instanceof Player owner) || owner.level().isClientSide
            || event.getEntity() == owner || !holding(owner) || thundering) return;
        long now = owner.level().getGameTime();
        Might might = MIGHT.computeIfAbsent(owner.getUUID(), id -> new Might());
        if (now - might.lastHit > MIGHT_DECAY_TICKS) { might.stacks = 0; might.hits = 0; }
        event.setAmount((float)(event.getAmount() * (1 + might.stacks * MIGHT_BONUS)));
        might.stacks = Math.min(MAX_MIGHT, might.stacks + 1);
        might.lastHit = now;
        if (++might.hits % THUNDER_EVERY == 0) thunder(owner, event.getEntity());
    }

    private static void thunder(Player owner, LivingEntity struck) {
        if (!(owner.level() instanceof ServerLevel level)) return;
        double damage = owner.getAttributeValue(Attributes.ATTACK_DAMAGE) * THUNDER_SHARE;
        List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class,
            new AABB(struck.position().subtract(THUNDER_RANGE, 3, THUNDER_RANGE), struck.position().add(THUNDER_RANGE, 3, THUNDER_RANGE)),
            target -> target != struck && target.isAlive() && CombatRules.canHarm(owner, target));
        targets.sort((left, right) -> Double.compare(left.distanceToSqr(struck), right.distanceToSqr(struck)));
        Vec3 from = struck.position().add(0, struck.getBbHeight() * .6, 0);
        level.playSound(null, struck.blockPosition(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, .35F, 1.6F);
        thundering = true;
        try {
            for (int index = 0; index < Math.min(THUNDER_TARGETS, targets.size()); index++) {
                LivingEntity target = targets.get(index);
                Vec3 to = target.position().add(0, target.getBbHeight() * .6, 0);
                for (int step = 0; step <= 8; step++) {
                    Vec3 point = from.lerp(to, step / 8.0);
                    level.sendParticles(ParticleTypes.ELECTRIC_SPARK, point.x, point.y, point.z, 2, .05, .05, .05, 0);
                }
                target.invulnerableTime = 0;
                target.hurt(level.damageSources().indirectMagic(owner, owner), (float)damage);
                from = to;
            }
        } finally {
            thundering = false;
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void bodyOfDragon(LivingHurtEvent event) {
        if (event.getEntity() instanceof Player holder && !holder.level().isClientSide && holding(holder)) {
            event.setAmount(event.getAmount() * DAMAGE_TAKEN);
        } else if (event.getSource().getEntity() instanceof Player owner && !owner.level().isClientSide
            && event.getEntity() != owner && holding(owner)) {
            float heal = (float)Math.min(event.getAmount() * LIFESTEAL, owner.getMaxHealth() * .1);
            if (heal > 0) owner.heal(heal);
        }
    }

    // 龙王裁决：在护甲之后追加，等于真实伤害；Boss（forge:bosses 标签）只吃四分之一。
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void verdict(LivingDamageEvent event) {
        if (!(event.getSource().getEntity() instanceof Player owner) || owner.level().isClientSide
            || event.getEntity() == owner || !holding(owner) || event.getEntity() instanceof Player) return;
        LivingEntity target = event.getEntity();
        double share = target.getType().is(net.minecraftforge.common.Tags.EntityTypes.BOSSES) ? VERDICT_BOSS_SHARE : VERDICT_SHARE;
        event.setAmount((float)(event.getAmount() + target.getMaxHealth() * share));
    }

    // 真龙之躯：摔落、火焰、熔岩、溺水、窒息伤害直接免疫。
    @SubscribeEvent
    public static void immune(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof Player holder) || holder.level().isClientSide || !holding(holder)) return;
        DamageSource source = event.getSource();
        if (source.is(DamageTypeTags.IS_FALL) || source.is(DamageTypeTags.IS_FIRE) || source.is(DamageTypeTags.IS_DROWNING)
            || source.is(DamageTypes.IN_WALL) || source.is(DamageTypes.LAVA)) event.setCanceled(true);
    }

    // 龙魂不灭：致命伤时回到一半生命、抗性Ⅳ 3 秒，冷却 15 分钟。
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void revive(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Player holder) || holder.level().isClientSide || !holding(holder)) return;
        long now = holder.level().getGameTime();
        var data = holder.getPersistentData();
        if (now < data.getLong(REVIVE_KEY)) return;
        event.setCanceled(true);
        data.putLong(REVIVE_KEY, now + REVIVE_COOLDOWN_TICKS);
        holder.setHealth(holder.getMaxHealth() * .5F);
        holder.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 60, 3, false, true, true));
        holder.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 1, false, true, true));
        if (holder.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, holder.getX(), holder.getY() + 1, holder.getZ(), 60, .6, 1, .6, .4);
            level.playSound(null, holder.blockPosition(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1F, 1.2F);
        }
        holder.displayClientMessage(net.minecraft.network.chat.Component.translatable("goryo.dragon.revive"), true);
    }

    // 龙王祭的祝福：真龙御礼只要在身上（背包、快捷栏、副手都算）就一直生效，每 2 秒续一次，图标不闪、不冒粒子。
    // 清除和免疫负面效果只在主手握刀时生效。
    @SubscribeEvent
    public static void blessing(net.minecraftforge.event.TickEvent.PlayerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END || event.player.level().isClientSide
            || event.player.level().getGameTime() % 40 != 0 || !carrying(event.player)) return;
        Player player = event.player;
        if (holding(player)) player.getActiveEffects().stream().filter(effect -> !effect.getEffect().isBeneficial()
            && effect.getEffect().getCategory() == net.minecraft.world.effect.MobEffectCategory.HARMFUL).map(MobEffectInstance::getEffect)
            .toList().forEach(player::removeEffect);
        for (var blessing : new MobEffectInstance[]{
            new MobEffectInstance(MobEffects.DIG_SPEED, 300, 0, true, false, true),
            new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 300, 0, true, false, true),
            new MobEffectInstance(MobEffects.NIGHT_VISION, 400, 0, true, false, true),
            new MobEffectInstance(MobEffects.WATER_BREATHING, 300, 0, true, false, true),
            new MobEffectInstance(MobEffects.LUCK, 300, 0, true, false, true)}) {
            MobEffectInstance current = player.getEffect(blessing.getEffect());
            // 已经有更高等级的同名效果（比如药水）就不去覆盖。
            if (current == null || current.getAmplifier() <= blessing.getAmplifier()) player.addEffect(blessing);
        }
    }

    public static boolean carrying(Player player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (stack.getItem() instanceof mods.flammpfeil.slashblade.item.ItemSlashBlade && BladeCatalog.matches(stack, "dragon")) return true;
        }
        return false;
    }

    // 真龙护体：主手握着真龙御礼时，负面效果一律加不上。
    @SubscribeEvent
    public static void immuneToHarm(net.minecraftforge.event.entity.living.MobEffectEvent.Applicable event) {
        if (event.getEntity() instanceof Player player && !player.level().isClientSide
            && event.getEffectInstance().getEffect().getCategory() == net.minecraft.world.effect.MobEffectCategory.HARMFUL
            && holding(player)) event.setResult(net.minecraftforge.eventbus.api.Event.Result.DENY);
    }
}
