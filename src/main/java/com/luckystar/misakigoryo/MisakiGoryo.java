package com.luckystar.misakigoryo;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(MisakiGoryo.MOD_ID)
public final class MisakiGoryo {
    public static final String MOD_ID = "misaki_goryo";

    public MisakiGoryo() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        BladeArts.register(modBus);
        GoryoSpiritRegistry.register(modBus);
        GoryoNetwork.register();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, GoryoConfig.SPEC);
        MinecraftForge.EVENT_BUS.register(BladeCombat.class);
        MinecraftForge.EVENT_BUS.register(DragonBlade.class);
        MinecraftForge.EVENT_BUS.register(GoryoFlight.class);
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> BladeCombat.clear());
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }
}
