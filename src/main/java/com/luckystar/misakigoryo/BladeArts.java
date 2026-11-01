package com.luckystar.misakigoryo;

import mods.flammpfeil.slashblade.init.DefaultResources;
import mods.flammpfeil.slashblade.registry.ComboStateRegistry;
import mods.flammpfeil.slashblade.registry.combo.ComboState;
import mods.flammpfeil.slashblade.registry.specialeffects.SpecialEffect;
import mods.flammpfeil.slashblade.slasharts.SlashArts;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;

public final class BladeArts {
    public static final DeferredRegister<SlashArts> ARTS = DeferredRegister.create(SlashArts.REGISTRY_KEY, MisakiGoryo.MOD_ID);
    public static final DeferredRegister<ComboState> COMBOS = DeferredRegister.create(ComboState.REGISTRY_KEY, MisakiGoryo.MOD_ID);
    public static final DeferredRegister<SpecialEffect> EFFECTS = DeferredRegister.create(SpecialEffect.REGISTRY_KEY, MisakiGoryo.MOD_ID);

    static {
        for (String key : new String[]{"sakura", "gawoo", "dimension_cut", "dragon_king"}) {
            COMBOS.register(key, () -> ComboState.Builder.newInstance().startAndEnd(400, 459).speed(1.2F)
                .motionLoc(DefaultResources.ExMotionLocation).priority(40)
                .next(entity -> ComboStateRegistry.STANDBY.getId())
                .nextOfTimeout(entity -> ComboStateRegistry.STANDBY.getId()).build());
            ARTS.register(key, () -> new SjapArt(key));
        }
        for (String key : new String[]{"sakura_edge", "gawoo_spirit", "dragon_thunder"}) {
            EFFECTS.register(key, () -> new SpecialEffect(0, false, false));
        }
    }

    private BladeArts() {}

    public static void register(IEventBus bus) {
        COMBOS.register(bus);
        ARTS.register(bus);
        EFFECTS.register(bus);
    }

    private static final class SjapArt extends SlashArts {
        private final String key;

        private SjapArt(String key) {
            super(owner -> MisakiGoryo.id(key));
            this.key = key;
            setComboStateJust(owner -> MisakiGoryo.id(key));
            setComboStateSuper(owner -> MisakiGoryo.id(key));
            setProudSoulCost(20);
        }

        @Override
        public ResourceLocation doArts(ArtsType type, LivingEntity owner) {
            if (type == null || type == ArtsType.Fail) {
                return ComboStateRegistry.NONE.getId();
            }
            String bladeKey = key.equals("dragon_king") ? "dragon" : key.equals("dimension_cut") ? "gawoo_awakened" : key;
            if (!owner.level().isClientSide && !BladeCombat.activate(owner, owner.getMainHandItem(), bladeKey)) {
                return ComboStateRegistry.NONE.getId();
            }
            return MisakiGoryo.id(key);
        }
    }
}
