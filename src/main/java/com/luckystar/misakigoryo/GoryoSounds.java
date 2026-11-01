package com.luckystar.misakigoryo;

import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class GoryoSounds {
    private static final DeferredRegister<SoundEvent> SOUNDS =
        DeferredRegister.create(ForgeRegistries.SOUND_EVENTS,MisakiGoryo.MOD_ID);
    public static final RegistryObject<SoundEvent> DRAW=event("goryo.draw");
    public static final RegistryObject<SoundEvent> SHEATH=event("goryo.sheath");
    public static final RegistryObject<SoundEvent> DIMENSION_CUT=event("goryo.dimension_cut");
    public static final RegistryObject<SoundEvent> DRAGON_ROAR=event("goryo.dragon_roar");
    public static final RegistryObject<SoundEvent> AWAKENING=event("goryo.awakening");
    private GoryoSounds() {}
    private static RegistryObject<SoundEvent> event(String name) {
        return SOUNDS.register(name,()->SoundEvent.createVariableRangeEvent(MisakiGoryo.id(name)));
    }
    public static void register(IEventBus bus) { SOUNDS.register(bus); }
}
