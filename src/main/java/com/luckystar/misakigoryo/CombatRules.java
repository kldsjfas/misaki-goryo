package com.luckystar.misakigoryo;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;

public final class CombatRules {
    private CombatRules() {}

    public static boolean canHarm(LivingEntity attacker, LivingEntity target) {
        return target.isAlive() && canClaimKill(attacker, target);
    }

    public static boolean canClaimKill(LivingEntity attacker, LivingEntity target) {
        // 车万女仆一律不打（拔刀剑原模组也把她列进了不可攻击名单）。
        var targetId = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(target.getType());
        if (targetId != null && targetId.getNamespace().equals("touhou_little_maid")) return false;
        if (attacker == target || target.isSpectator()
            || attacker.isAlliedTo(target) || target.isAlliedTo(attacker)) return false;
        if (target instanceof Player player && (player.isCreative() || player.isSpectator())) return false;
        if (target instanceof OwnableEntity pet) {
            if (attacker.getUUID().equals(pet.getOwnerUUID())) return false;
            LivingEntity owner = pet.getOwner();
            if (owner != null && (attacker.isAlliedTo(owner) || owner.isAlliedTo(attacker))) return false;
        }
        if (attacker instanceof Player && target instanceof Player && attacker.getServer() != null
            && !attacker.getServer().isPvpAllowed()) return false;
        return true;
    }
}
