package com.luckystar.misakigoryo;

import java.util.List;
import java.util.Map;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.item.SwordType;
import mods.flammpfeil.slashblade.client.renderer.CarryType;
import mods.flammpfeil.slashblade.registry.SlashArtsRegistry;
import mods.flammpfeil.slashblade.registry.slashblade.EnchantmentDefinition;
import mods.flammpfeil.slashblade.registry.slashblade.PropertiesDefinition;
import mods.flammpfeil.slashblade.registry.slashblade.RenderDefinition;
import mods.flammpfeil.slashblade.registry.slashblade.SlashBladeDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

public final class BladeCatalog {
    public record BladeSpec(String key, float attack, int durability, int color, String art, String effect) {
        public ResourceLocation artId() {
            return art.equals("entry") ? SlashArtsRegistry.JUDGEMENT_CUT.getId() : MisakiGoryo.id(art);
        }
    }

    public static final Map<String, BladeSpec> BLADES = Map.of(
        // 基础攻击按原版拔刀剑的尺度：铁质无铭刀约 4，钻石/下界合金无铭刀 7～8。
        "entry", new BladeSpec("entry", 4, 160, 0xFF94C5, "entry", ""),
        "sakura", new BladeSpec("sakura", 5, 180, 0xFF94C5, "sakura", "sakura_edge"),
        "gawoo", new BladeSpec("gawoo", 6, 300, 0xFF94C5, "gawoo", "gawoo_spirit"),
        "gawoo_awakened", new BladeSpec("gawoo_awakened", 7, 420, 0x28CFF4, "dimension_cut", "gawoo_spirit"),
        "dragon", new BladeSpec("dragon", 8, 600, 0x28CFF4, "dragon_king", "dragon_thunder"));

    private BladeCatalog() {}

    public static SlashBladeDefinition definition(String key) {
        BladeSpec spec = BLADES.get(key);
        if (spec == null) throw new IllegalArgumentException("Unknown misaki_goryo blade: " + key);
        var properties = PropertiesDefinition.Builder.newInstance()
            .baseAttackModifier(spec.attack).maxDamage(spec.durability).slashArtsType(spec.artId())
            .defaultSwordType(List.of(SwordType.BEWITCHED));
        if (!spec.effect.isEmpty()) properties.addSpecialEffect(MisakiGoryo.id(spec.effect));
        return new SlashBladeDefinition(MisakiGoryo.id(key), RenderDefinition.Builder.newInstance()
            .modelName(MisakiGoryo.id("model/blades/" + key + ".obj"))
            .textureName(MisakiGoryo.id("model/blades/" + key + ".png"))
            .effectColor(spec.color).standbyRenderType(CarryType.KATANA).build(), properties.build(),
            List.of(new EnchantmentDefinition(new ResourceLocation("minecraft", "unbreaking"), 1)));
    }

    public static ItemStack create(String key) {
        ItemStack result = definition(key).getBlade();
        if (result.isEmpty()) throw new IllegalStateException("SlashBlade rejected blade " + key);
        result.getOrCreateTag().putString("GoryoBlade", key);
        // 全小写的标记给提示框模组按刀配主题框用。
        result.getOrCreateTag().putString("goryo", key);
        return result;
    }

    public static void ensureChargeEnchantment(ItemStack blade) {
        if (EnchantmentHelper.getItemEnchantmentLevel(Enchantments.UNBREAKING, blade) >= 1) return;
        var enchantments = EnchantmentHelper.getEnchantments(blade);
        enchantments.put(Enchantments.UNBREAKING, 1);
        EnchantmentHelper.setEnchantments(enchantments, blade);
    }

    public static boolean matches(ItemStack stack, String key) {
        BladeSpec spec = BLADES.get(key);
        if (spec == null || stack.isEmpty() || !(stack.getItem() instanceof ItemSlashBlade)) return false;
        return stack.getCapability(ItemSlashBlade.BLADESTATE).map(state ->
            definition(key).getTranslationKey().equals(state.getTranslationKey())
                && spec.artId().equals(state.getSlashArtsKey())
                && state.getModel().filter(MisakiGoryo.id("model/blades/" + key + ".obj")::equals).isPresent()
                && (spec.effect.isEmpty() || state.hasSpecialEffect(MisakiGoryo.id(spec.effect)))).orElse(false);
    }

    public static String keyOf(ItemStack stack) {
        for (String key : BLADES.keySet()) if (matches(stack, key)) return key;
        return "";
    }

    public static int stage(ItemStack stack) {
        return switch (keyOf(stack)) {
            case "entry" -> 1;
            case "sakura" -> 2;
            case "gawoo" -> 3;
            case "gawoo_awakened" -> 4;
            case "dragon" -> 5;
            default -> 0;
        };
    }
}
