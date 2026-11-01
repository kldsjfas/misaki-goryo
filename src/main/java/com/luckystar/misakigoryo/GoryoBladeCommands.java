package com.luckystar.misakigoryo;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Small operator commands for model inspection and server administration. */
@Mod.EventBusSubscriber(modid = MisakiGoryo.MOD_ID)
public final class GoryoBladeCommands {
    private GoryoBladeCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("goryo").requires(source -> source.hasPermission(2))
            .then(Commands.literal("give").then(Commands.argument("blade", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(BladeCatalog.BLADES.keySet(), builder))
                .executes(context -> {
                    String key = StringArgumentType.getString(context, "blade");
                    if (!BladeCatalog.BLADES.containsKey(key)) return 0;
                    var player = context.getSource().getPlayerOrException();
                    var blade = BladeCatalog.create(key);
                    if (!player.getInventory().add(blade)) player.drop(blade, false);
                    return 1;
                })))
            .then(Commands.literal("art").executes(context -> {
                var player = context.getSource().getPlayerOrException();
                var blade = player.getMainHandItem();
                return BladeCombat.activate(player, blade, BladeCatalog.keyOf(blade)) ? 1 : 0;
            })));
    }
}
