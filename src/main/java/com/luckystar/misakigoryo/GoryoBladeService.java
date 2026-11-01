package com.luckystar.misakigoryo;

import java.util.UUID;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3f;

/** Keeps the original blade's history while its identity grows. */
@Mod.EventBusSubscriber(modid = MisakiGoryo.MOD_ID)
public final class GoryoBladeService {
    // 刀的数值改动时加一，手上的旧刀会自动换成新数值。
    private static final int DATA_VERSION = 1;
    private static final DustParticleOptions CYAN = new DustParticleOptions(new Vector3f(0.15F, 0.85F, 1), 1.5F);

    private GoryoBladeService() {}

    public static int stage(ItemStack stack) { return BladeCatalog.stage(stack); }

    public static boolean isGrowthBlade(ItemStack stack) { return stage(stack) > 0; }

    public static ItemStack evolve(ItemStack previous, String key) {
        ItemStack result = BladeCatalog.create(key);
        inherit(previous, result);
        return result;
    }

    /** Native shaped_blade retains counters, but not custom NBT or the blade UUID. */
    public static void inherit(ItemStack previous, ItemStack result) {
        var oldState = previous.getCapability(ItemSlashBlade.BLADESTATE).orElseThrow(IllegalStateException::new);
        var newState = result.getCapability(ItemSlashBlade.BLADESTATE).orElseThrow(IllegalStateException::new);
        String resultKey = BladeCatalog.keyOf(result);
        CompoundTag adapted = oldState.serializeNBT().copy();
        CompoundTag identity = newState.serializeNBT();
        for (String key : new String[]{"ModelName", "TextureName", "translationKey", "SpecialAttackType",
            "SpecialEffects", "baseAttackModifier", "maxDamage", "isDefaultBewitched", "ComboRoot",
            "StandbyRenderType", "SummonedSwordColor", "SummonedSwordColorInverse"}) {
            if (identity.contains(key)) adapted.put(key, identity.get(key).copy());
            else adapted.remove(key);
        }
        adapted.putInt("Damage", (int)Math.min(newState.getMaxDamage(), Math.round(
            oldState.getDamage() / (double)Math.max(1, oldState.getMaxDamage()) * newState.getMaxDamage())));
        result.setTag(previous.hasTag() ? previous.getTag().copy() : new CompoundTag());
        result.getOrCreateTag().putString("GoryoBlade", resultKey);
        result.getOrCreateTag().putString("goryo", resultKey);
        result.getOrCreateTag().putInt("GoryoVersion", DATA_VERSION);
        newState.deserializeNBT(adapted);
        result.getOrCreateTag().put("bladeState", adapted.copy());
        BladeCatalog.ensureChargeEnchantment(result);
    }

    @SubscribeEvent
    public static void crafted(PlayerEvent.ItemCraftedEvent event) {
        if (event.getEntity().level().isClientSide) return;
        ItemStack result = event.getCrafting();
        // Shift-crafting has already moved a copy into the inventory. The original
        // result has count zero here, but still carries its native blade capability.
        var state = result.getCapability(ItemSlashBlade.BLADESTATE).orElse(null);
        if (state == null) return;
        String key = "";
        for (String candidate : BladeCatalog.BLADES.keySet()) {
            if (BladeCatalog.definition(candidate).getTranslationKey().equals(state.getTranslationKey())
                && BladeCatalog.BLADES.get(candidate).artId().equals(state.getSlashArtsKey())) {
                key = candidate;
                break;
            }
        }
        String required = switch (key) {
            case "sakura" -> "entry";
            case "gawoo" -> "sakura";
            case "dragon" -> "gawoo_awakened";
            default -> "";
        };
        if (required.isEmpty() && !key.equals("entry")) return;
        // 进度在做出刀的那一刻就给，不用等拿到主手上。
        // 按住 Shift 合成时这里的成品数量已经是 0，所以按刀名算阶段，不用 stage(result)。
        if (event.getEntity() instanceof ServerPlayer player)
            awardUpTo(player, java.util.List.of("entry", "sakura", "gawoo", "gawoo_awakened", "dragon").indexOf(key) + 1);
        UUID freshId = state.getUniqueId();
        var outputs = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<ItemStack, Boolean>());
        if (!result.isEmpty()) outputs.add(result);
        var inventory = event.getEntity().getInventory();
        for (int index = 0; index < Math.min(41, inventory.getContainerSize()); index++) {
            ItemStack moved = inventory.getItem(index);
            if (sameCraftedBlade(moved, key, freshId)) outputs.add(moved);
        }
        ItemStack carried = event.getEntity().containerMenu.getCarried();
        if (sameCraftedBlade(carried, key, freshId)) outputs.add(carried);
        if (key.equals("entry")) {
            for (ItemStack output : outputs) {
                output.getOrCreateTag().putString("GoryoBlade", "entry");
                output.getOrCreateTag().putString("goryo", "entry");
            }
            return;
        }
        for (int slot = 0; slot < event.getInventory().getContainerSize(); slot++) {
            ItemStack input = event.getInventory().getItem(slot);
            if (!BladeCatalog.matches(input, required)) continue;
            for (ItemStack output : outputs) inherit(input, output);
            break;
        }
    }

    private static boolean sameCraftedBlade(ItemStack candidate, String key, UUID freshId) {
        return BladeCatalog.matches(candidate, key) && candidate.getCapability(ItemSlashBlade.BLADESTATE)
            .map(state -> freshId.equals(state.getUniqueId())).orElse(false);
    }

    @SubscribeEvent
    public static void playerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)
            || player.tickCount % 20 != 0) return;
        ItemStack blade = player.getMainHandItem();
        int current = stage(blade);
        if (current == 0) return;
        if (blade.getOrCreateTag().getInt("GoryoVersion") < DATA_VERSION) {
            ItemStack migrated = evolve(blade, BladeCatalog.keyOf(blade));
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, migrated);
            blade = migrated;
        }
        awardUpTo(player, current);
        if (current == 3) {
            var state = blade.getCapability(ItemSlashBlade.BLADESTATE).orElseThrow(IllegalStateException::new);
            int kills = GoryoConfig.AWAKEN_KILLS.get(), souls = GoryoConfig.AWAKEN_SOULS.get();
            String refusal = state.getKillCount() < kills ? "waiting_kills"
                : state.getProudSoulCount() < souls ? "waiting_souls" : "";
            if (!refusal.isEmpty()) {
                long now = player.serverLevel().getGameTime();
                String stamp = "GoryoHint_" + refusal;
                if (!blade.getOrCreateTag().contains(stamp) || now >= blade.getTag().getLong(stamp) + 6000) {
                    Component message = refusal.equals("waiting_kills")
                        ? Component.translatable("goryo.awakening." + refusal, state.getKillCount(), kills)
                        : Component.translatable("goryo.awakening." + refusal, state.getProudSoulCount(), souls);
                    player.displayClientMessage(message, true);
                    blade.getOrCreateTag().putLong(stamp, now);
                }
                return;
            }
            ItemStack awakened = evolve(blade, "gawoo_awakened");
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, awakened);
            awakened.getOrCreateTag().putInt("GoryoAnnounced", 4);
            announce(player, 4);
            awardUpTo(player, 4);
        } else if (current == 5 && blade.getOrCreateTag().getInt("GoryoAnnounced") < 5) {
            blade.getOrCreateTag().putInt("GoryoAnnounced", 5);
            announce(player, 5);
        }
    }

    private static void announce(ServerPlayer player, int current) {
        String prefix = "goryo.awakening." + (current == 4 ? "four" : "five");
        player.sendSystemMessage(Component.translatable(prefix + ".chat_1"));
        player.sendSystemMessage(Component.translatable(prefix + ".chat_2"));
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 65, 20));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.translatable(prefix + ".title")));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.translatable(prefix + ".subtitle")));
        var level = player.serverLevel();
        level.playSound(null, player.blockPosition(), GoryoSounds.AWAKENING.get(), SoundSource.PLAYERS, 1, 0.7F);
        for (int step = 0; step < 48; step++) {
            double turn = step * Math.PI * 2 / 48;
            double x = player.getX() + Math.cos(turn) * 2.2;
            double z = player.getZ() + Math.sin(turn) * 2.2;
            level.sendParticles(CYAN, x, player.getY() + 1, z, 1, 0, 0.1, 0, 0);
            if (step % 2 == 0) level.sendParticles(ParticleTypes.CHERRY_LEAVES, x, player.getY() + 1.5, z, 1, 0, 0.5, 0, 0.04);
        }
    }

    // 前面几阶一并补上，用指令直接拿到高阶刀时进度树也是连着的。
    private static void awardUpTo(ServerPlayer player, int stage) {
        for (int earlier = 1; earlier <= stage; earlier++) award(player, "stage_" + earlier);
    }

    private static void award(ServerPlayer player, String path) {
        var advancement = player.getServer().getAdvancements().getAdvancement(MisakiGoryo.id("goryo/" + path));
        if (advancement != null && !player.getAdvancements().getOrStartProgress(advancement).isDone())
            player.getAdvancements().award(advancement, "done");
    }
}
