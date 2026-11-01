package com.luckystar.misakigoryo;

import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MisakiGoryo.MOD_ID, value = Dist.CLIENT)
public final class GoryoBladeTooltip {
    private GoryoBladeTooltip() {}

    @SubscribeEvent
    public static void tooltip(ItemTooltipEvent event) {
        String key = BladeCatalog.keyOf(event.getItemStack());
        if (key.isEmpty()) {
            var itemId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(event.getItemStack().getItem());
            if (itemId != null && itemId.getNamespace().equals("slashblade")
                && itemId.getPath().startsWith("bladestand_"))
                event.getToolTip().add(Component.translatable("goryo.tooltip.stand"));
            return;
        }
        int count = switch (key) {
            case "entry" -> 7;
            case "sakura" -> 6;
            case "gawoo" -> 8;
            case "gawoo_awakened" -> 9;
            case "dragon" -> 21;
            default -> 0;
        };
        // 平时只显示机制和成长几行，刀的故事按住 Shift 再看，免得介绍太长把刀名挤出屏幕。
        boolean story = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        event.getToolTip().add(Component.empty());
        for (int line = 0; line < count; line++) {
            Component text = key.equals("gawoo") && line == 7
                ? Component.translatable("goryo.tooltip.gawoo.7", GoryoConfig.AWAKEN_KILLS.get(), GoryoConfig.AWAKEN_SOULS.get())
                : Component.translatable("goryo.tooltip." + key + "." + line);
            String plain = text.getString();
            if (!story && (plain.contains("§o") || plain.contains("━") || plain.startsWith("§6「"))) continue;
            event.getToolTip().add(text);
        }
        if (BladeCatalog.stage(event.getItemStack()) >= 4) event.getToolTip().add(Component.translatable("goryo.tooltip.flight"));
        if (!story) event.getToolTip().add(Component.translatable("goryo.tooltip.more"));
    }
}
