package com.luckystar.misakigoryo;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class GoryoSpiritRegistry {
    private static final DeferredRegister<EntityType<?>> ENTITIES =
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MisakiGoryo.MOD_ID);
    public static final RegistryObject<EntityType<MisakiSpiritEntity>> MISAKI =
        ENTITIES.register("misaki_spirit", () -> EntityType.Builder
            .<MisakiSpiritEntity>of(MisakiSpiritEntity::new, MobCategory.MISC)
            .sized(0.35F, 0.8F).clientTrackingRange(10).updateInterval(2)
            .noSave().noSummon().build("misaki_goryo:misaki_spirit"));

    private GoryoSpiritRegistry() {}
    public static void register(IEventBus bus) {
        ENTITIES.register(bus);
        GoryoSounds.register(bus);
        net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
            () -> GoryoClient::registerConfig);
    }
}
