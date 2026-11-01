package com.luckystar.misakigoryo;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import com.mojang.blaze3d.platform.InputConstants;

@Mod.EventBusSubscriber(modid=MisakiGoryo.MOD_ID,value=Dist.CLIENT,bus=Mod.EventBusSubscriber.Bus.MOD)
public final class GoryoClient {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> VOICE_LANGUAGE;
    private static final KeyMapping HIDE = new KeyMapping("key.misaki_goryo.hide_misaki",
        KeyConflictContext.IN_GAME,KeyModifier.ALT,InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_H,
        "key.categories.misaki_goryo");
    private static final Map<Integer,PlayingVoice> VOICES=new HashMap<>();
    private static final int[] DRAGON_NAME_COLORS = {0x55FFFF, 0x00AAAA, 0x5555FF, 0xFF55FF};

    static {
        var builder=new ForgeConfigSpec.Builder();
        VOICE_LANGUAGE=builder.comment("Select the matching optional Misaki voice resource pack.")
            .defineInList("voiceLanguage","zh_cn",java.util.List.of("zh_cn","ja_jp"));
        SPEC=builder.build();
    }

    private GoryoClient() {}
    public static void registerConfig() {
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
            net.minecraftforge.fml.config.ModConfig.Type.CLIENT,SPEC,"misaki_goryo-client.toml");
    }
    @SubscribeEvent public static void keys(RegisterKeyMappingsEvent event) { event.register(HIDE); }
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(GoryoSpiritRegistry.MISAKI.get(),MisakiSpiritRenderer::new);
    }

    public static void voice(int ownerId,String event) {
        Minecraft client=Minecraft.getInstance();
        if(client.level==null) return;
        var playing=VOICES.remove(ownerId);
        if(event.isEmpty()) {
            if(playing!=null) client.getSoundManager().stop(playing.sound);
            return;
        }
        // 拔刀/觉醒是切刀时的召唤台词，必须能打断正在播的那句，否则刚挥完刀就切刀会听不到
        boolean summon=event.equals("fanfare")||event.equals("awake");
        if(playing!=null && client.getSoundManager().isActive(playing.sound)) {
            if(!summon) {
                VOICES.put(ownerId,playing);
                return;
            }
            client.getSoundManager().stop(playing.sound);
        }
        var owner=client.level.getEntity(ownerId);
        if(owner==null) return;
        ResourceLocation sound=new ResourceLocation("misaki_goryo",
            "misaki."+VOICE_LANGUAGE.get()+"."+event);
        // An optional pack may be absent; don't ask the sound engine to log a missing event.
        if(client.getSoundManager().getSoundEvent(sound)==null) return;
        var instance=new SimpleSoundInstance(SoundEvent.createVariableRangeEvent(sound),
            SoundSource.PLAYERS,0.85F,1,RandomSource.create(),owner.getX(),owner.getY()+0.8,owner.getZ());
        client.getSoundManager().play(instance);
        VOICES.put(ownerId,new PlayingVoice(instance,event,client.level.getGameTime()));
    }

    private record PlayingVoice(SimpleSoundInstance sound,String event,long started) {}

    @Mod.EventBusSubscriber(modid=MisakiGoryo.MOD_ID,value=Dist.CLIENT)
    public static final class InputEvents {
        private InputEvents() {}
        @SubscribeEvent(priority=net.minecraftforge.eventbus.api.EventPriority.LOWEST) public static void tooltip(net.minecraftforge.event.entity.player.ItemTooltipEvent event) {
            if (!BladeCatalog.matches(event.getItemStack(), "dragon")
                    || event.getItemStack().hasCustomHoverName() || event.getToolTip().isEmpty()) return;
            String text = Component.translatable("item.misaki_goryo.dragon").getString();
            if (text.length() > 256) return;
            var name = Component.empty().setStyle(event.getToolTip().get(0).getStyle());
            Style lettering = Style.EMPTY;
            long animationTime = Util.getMillis();
            int colorPhase = (int)((animationTime / 400L) % DRAGON_NAME_COLORS.length);
            int letterIndex = 0;
            for (int offset = 0; offset < text.length();) {
                if (text.charAt(offset) == '§' && offset + 1 < text.length()) {
                    ChatFormatting formatting = ChatFormatting.getByCode(text.charAt(offset + 1));
                    if (formatting != null) lettering = lettering.applyLegacyFormat(formatting);
                    offset += 2;
                    continue;
                }
                int codePoint = text.codePointAt(offset);
                Style appearance = lettering;
                if (codePoint == '✦') {
                    appearance = lettering.withColor((animationTime / 600L) % 2 == 0 ? 0xFFFF55 : 0xFFFFFF);
                } else if (!Character.isWhitespace(codePoint)) {
                    appearance = lettering.withColor(DRAGON_NAME_COLORS[(colorPhase + letterIndex++) % DRAGON_NAME_COLORS.length]);
                }
                name.append(Component.literal(new String(Character.toChars(codePoint))).withStyle(appearance));
                offset += Character.charCount(codePoint);
            }
            event.getToolTip().set(0, name);
        }
        @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
            if(event.phase!=TickEvent.Phase.END) return;
            Minecraft client=Minecraft.getInstance();
            if(client.level==null) {
                VOICES.clear();
                return;
            }
            while(HIDE.consumeClick()) if(client.player!=null) GoryoNetwork.toggle();
            VOICES.entrySet().removeIf(entry -> {
                var voice=entry.getValue();
                if(client.level.getGameTime()-voice.started<5||client.getSoundManager().isActive(voice.sound)) return false;
                if(client.player!=null&&entry.getKey()==client.player.getId()&&voice.event.equals("win"))
                    GoryoNetwork.voiceFinished(voice.event);
                return true;
            });
        }
    }
}
