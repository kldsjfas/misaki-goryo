package com.luckystar.misakigoryo;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

// 觉醒嘎呜切（四阶）和真龙御礼（五阶）拿在主手上就能像创造模式一样飞，中期开始的赶路工具。
public final class GoryoFlight {
    private static final String GRANTED_KEY = "goryo_flight";
    private static final int FLIGHT_STAGE = 4;

    private GoryoFlight() {
    }

    @SubscribeEvent
    public static void tick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player) || player.level().getGameTime() % 5 != 0) return;
        if (player.isCreative() || player.isSpectator()) {
            player.getPersistentData().remove(GRANTED_KEY);
            return;
        }
        var abilities = player.getAbilities();
        boolean holding = GoryoConfig.FLIGHT.get() && BladeCatalog.stage(player.getMainHandItem()) >= FLIGHT_STAGE;
        boolean granted = player.getPersistentData().getBoolean(GRANTED_KEY);
        if (holding && !abilities.mayfly) {
            abilities.mayfly = true;
            player.getPersistentData().putBoolean(GRANTED_KEY, true);
            player.onUpdateAbilities();
        } else if (!holding && granted) {
            // 只收回自己给的飞行；换下刀时还在天上就给几秒缓降，免得摔死。
            player.getPersistentData().remove(GRANTED_KEY);
            boolean wasFlying = abilities.flying;
            abilities.mayfly = false;
            abilities.flying = false;
            player.onUpdateAbilities();
            if (wasFlying) player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 120, 0, false, false, true));
        }
    }
}
