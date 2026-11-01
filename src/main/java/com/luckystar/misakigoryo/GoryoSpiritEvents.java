package com.luckystar.misakigoryo;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import mods.flammpfeil.slashblade.event.SlashBladeEvent;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AnvilRepairEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid=MisakiGoryo.MOD_ID)
public final class GoryoSpiritEvents {
    private static final Map<UUID,Companion> COMPANIONS=new HashMap<>();
    private static final String HIDDEN="GoryoMisakiHidden";
    private static final String DEPARTING="GoryoMisakiDepartingUntil";
    private GoryoSpiritEvents() {}

    public static boolean hidden(ServerPlayer player) { return persisted(player).getBoolean(HIDDEN); }
    public static boolean departing(ServerPlayer player) {
        return player.getPersistentData().getLong(DEPARTING)>player.level().getGameTime();
    }
    private static net.minecraft.nbt.CompoundTag persisted(ServerPlayer player) {
        var data=player.getPersistentData();
        if(!data.contains("PlayerPersisted",10)) data.put("PlayerPersisted",new net.minecraft.nbt.CompoundTag());
        return data.getCompound("PlayerPersisted");
    }

    public static void toggle(ServerPlayer player) {
        if(GoryoBladeService.stage(player.getMainHandItem())<4) return;
        long now=player.level().getGameTime();
        if(player.getPersistentData().getLong("GoryoMisakiToggleReady")>now) return;
        player.getPersistentData().putLong("GoryoMisakiToggleReady",now+10);
        boolean hide=!hidden(player);
        persisted(player).putBoolean(HIDDEN,hide);
        GoryoVoice.mute(player);
        Companion companion=COMPANIONS.get(player.getUUID());
        if(hide) {
            player.getPersistentData().putLong(DEPARTING,now+80);
            if(companion!=null) {
                speak(player,companion,"hide",true);
                if(companion.entity!=null) companion.entity.beginDeparture(now+80);
            }
        } else {
            player.getPersistentData().remove(DEPARTING);
            companion=ensure(player,GoryoBladeService.stage(player.getMainHandItem()));
            if(companion.entity!=null) companion.entity.cancelDeparture();
            speak(player,companion,"show",false);
        }
    }

    @SubscribeEvent public static void tick(TickEvent.PlayerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        long now=player.level().getGameTime();
        int stage=GoryoBladeService.stage(player.getMainHandItem());
        if(stage<4 || !player.isAlive() || player.isSpectator() || (hidden(player)&&!departing(player))) {
            GoryoVoice.tick(player);
            removeEntity(player);
            // 收刀后忘掉上一把，再拔出来同一把刀也算一次召唤，会重新出声
            Companion away=COMPANIONS.get(player.getUUID());
            if(away!=null) { away.lastBlade=null; away.lastStage=0; }
            return;
        }
        Companion companion=ensure(player,stage);
        confirmCelebrations(player,companion);
        if(hidden(player)) return;
        GoryoVoice.tick(player);
        long art=player.getPersistentData().getLong("GoryoArt");
        if(art>companion.lastArt) {
            companion.lastArt=art;
            specialAttack(player,player.getMainHandItem());
        }
        long strong=player.getPersistentData().getLong("GoryoStrongHit");
        if(strong>companion.lastStrong) {
            companion.lastStrong=strong;
            comboFinish(player);
        }
        UUID bladeId=player.getMainHandItem().getCapability(ItemSlashBlade.BLADESTATE)
            .map(state->state.getUniqueId()).orElse(null);
        if(companion.lastStage!=stage || !java.util.Objects.equals(companion.lastBlade,bladeId)) {
            companion.lastStage=stage;
            companion.lastBlade=bladeId;
            String factory=stage>=5?"awake":"fanfare";
            GoryoVoice.play(player,factory);
        }
        if(player.swinging&&player.swingTime==1&&player.getRandom().nextFloat()<0.7F) GoryoVoice.play(player,"attack");
        if(now%20!=0) return;
        if(player.level().isNight() && now-companion.lastCombat>=1200 && now>=companion.nextYawn
            && companion.entity!=null && !GoryoVoice.busy(player) && companion.entity.bubbleKey().isEmpty()) {
            if(companion.entity.playAnimation("yawn")) companion.nextYawn=now+1200+player.getRandom().nextInt(1201);
        }
        if(player.isSleeping()&&!companion.wasSleeping) speak(player,companion,"sleep",false);
        companion.wasSleeping=player.isSleeping();
        boolean broken=player.getMainHandItem().getCapability(ItemSlashBlade.BLADESTATE)
            .map(state->state.isBroken()).orElse(false);
        if(broken&&!companion.wasBroken) speak(player,companion,"broken",false);
        companion.wasBroken=broken;
        if(player.getHealth()<player.getMaxHealth()*0.3F) speak(player,companion,"low_health",false);
        boolean raining=player.level().isRainingAt(player.blockPosition());
        if(raining&&!companion.wasRaining) speak(player,companion,"rain",false);
        companion.wasRaining=raining;
        long day=player.level().getDayTime()/24000;
        if(player.level().isNight()&&companion.lastNight!=day) {
            if(speak(player,companion,"night",false)) companion.lastNight=day;
        }
        if(now-companion.lastCombat>=12000 && now-companion.lastIdle>=12000) {
            String idle=stage>=5?(player.getRandom().nextBoolean()?"idle_dragon_1":"idle_dragon_2"):"idle";
            if(speak(player,companion,idle,false)) companion.lastIdle=now;
        }
        if(now%100==0) nearbyBoss(player,companion);
    }

    private static Companion ensure(ServerPlayer player,int stage) {
        Companion companion=COMPANIONS.computeIfAbsent(player.getUUID(),key->new Companion(player.level().getGameTime()));
        if(companion.entity==null || companion.entity.isRemoved() || companion.entity.level()!=player.level()) {
            if(companion.entity!=null&&!companion.entity.isRemoved()) companion.entity.discard();
            companion.celebrations.clear();
            companion.celebrationReady=false;
            var spirit=GoryoSpiritRegistry.MISAKI.get().create(player.serverLevel());
            if(spirit==null) throw new IllegalStateException("Misaki entity type failed to create");
            spirit.bind(player,stage);
            if(hidden(player)&&departing(player)) spirit.beginDeparture(player.getPersistentData().getLong(DEPARTING));
            player.serverLevel().addFreshEntity(spirit);
            companion.entity=spirit;
            companion.lastStage=0;
        }
        companion.entity.setBladeStage(stage);
        return companion;
    }

    public static void specialAttack(LivingEntity owner,ItemStack blade) {
        if(!(owner instanceof ServerPlayer player)||GoryoBladeService.stage(blade)<4||hidden(player)) return;
        Companion companion=ensure(player,GoryoBladeService.stage(blade));
        companion.lastArt=player.getPersistentData().getLong("GoryoArt");
        companion.entity.drawSword();
        companion.lastCombat=player.level().getGameTime();
        GoryoVoice.play(player,GoryoBladeService.stage(blade)>=5?"skill_dragon":"skill");
    }
    public static void voiceStarted(ServerPlayer player,String event,long endsAt) {
        Companion companion=COMPANIONS.get(player.getUUID());
        if(companion!=null && companion.entity!=null && (event.equals("fanfare") || event.equals("awake"))) {
            int stage=event.equals("awake")?5:4;
            companion.entity.bubble("goryo.misaki.factory."+stage,
                (int)Math.max(1,endsAt-player.level().getGameTime()));
            companion.lastSilent=player.level().getGameTime();
        }
        if(companion!=null&&companion.entity!=null) {
            if(event.equals("fanfare") || event.equals("awake")) companion.entity.playAnimation("wave");
            if(event.equals("heavyhurt")) companion.entity.playAnimation("worry");
            if((event.equals("kill") || event.equals("win")) && companion.celebrationReady
                && companion.entity.playAnimation("celebrate")) companion.celebrationReady=false;
        }
    }
    public static void voiceFinished(ServerPlayer player,String event,long finishedAt) {}
    public static boolean silentBusy(ServerPlayer player) {
        Companion companion=COMPANIONS.get(player.getUUID());
        return companion!=null&&companion.entity!=null&&!companion.entity.bubbleKey().isEmpty()
            &&!GoryoVoice.busy(player);
    }
    public static void comboFinish(LivingEntity owner) {
        if(owner instanceof ServerPlayer player && GoryoBladeService.stage(player.getMainHandItem())>=4) {
            GoryoVoice.play(player,"combo_finish");
        }
    }

    @SubscribeEvent public static void hit(SlashBladeEvent.HitEvent event) {
        if(!(event.getUser() instanceof ServerPlayer player)||GoryoBladeService.stage(event.getBlade())<4
            ||!CombatRules.canClaimKill(player,event.getTarget())) return;
        Companion companion=COMPANIONS.get(player.getUUID());
        if(companion!=null) companion.lastCombat=player.level().getGameTime();
    }

    @SubscribeEvent(priority=EventPriority.LOWEST) public static void hurt(LivingDamageEvent event) {
        if(event.getAmount()<=0 || !(event.getEntity() instanceof ServerPlayer player)
            ||GoryoBladeService.stage(player.getMainHandItem())<4) return;
        if(event.getAmount()>player.getMaxHealth()*0.12F) GoryoVoice.play(player,"heavyhurt");
        Companion companion=COMPANIONS.get(player.getUUID());
        if(companion!=null) companion.lastCombat=player.level().getGameTime();
    }

    @SubscribeEvent public static void death(LivingDeathEvent event) {
        if(event.getEntity() instanceof ServerPlayer player) {
            GoryoVoice.play(player,"dead");
            return;
        }
        if(!(event.getSource().getEntity() instanceof ServerPlayer player)
            ||GoryoBladeService.stage(player.getMainHandItem())<4
            ||!CombatRules.canClaimKill(player,event.getEntity())) return;
        Companion companion=COMPANIONS.get(player.getUUID());
        if(companion==null) return;
        companion.lastCombat=player.level().getGameTime();
        if((isBoss(event.getEntity()) || isElite(event.getEntity())) && companion.celebrations.size()<8) {
            companion.celebrations.put(event.getEntity().getUUID(),new Celebration(event,player.level().getGameTime()+1800));
        }
        if(isBoss(event.getEntity())) {
            GoryoVoice.bossKilled(player);
        } else {
            // 小怪也有击杀台词；10 秒内连斩 5 个换成胜利台词。
            long now=player.level().getGameTime();
            companion.kills.addLast(now);
            while(!companion.kills.isEmpty()&&now-companion.kills.peekFirst()>200) companion.kills.removeFirst();
            if(companion.kills.size()>=5&&now>=companion.streakReady) {
                companion.kills.clear();
                companion.streakReady=now+400;
                GoryoVoice.play(player,"win");
            } else GoryoVoice.play(player,"kill");
        }
    }

    @SubscribeEvent public static void anvil(AnvilRepairEvent event) {
        if(!(event.getEntity() instanceof ServerPlayer player)
            ||GoryoBladeService.stage(event.getOutput())<4) return;
        int before=event.getLeft().getCapability(ItemSlashBlade.BLADESTATE)
            .map(state->state.getRefine()).orElse(0);
        int after=event.getOutput().getCapability(ItemSlashBlade.BLADESTATE)
            .map(state->state.getRefine()).orElse(0);
        Companion companion=COMPANIONS.get(player.getUUID());
        if(after>before&&companion!=null) speak(player,companion,"refine",false);
    }

    private static void nearbyBoss(ServerPlayer player,Companion companion) {
        for(var target:player.level().getEntitiesOfClass(LivingEntity.class,
            player.getBoundingBox().inflate(24),entity->entity!=player&&isElite(entity)&&CombatRules.canHarm(player,entity))) {
            if(!player.hasLineOfSight(target)) continue;
            speak(player,companion,"elite",false);
            return;
        }
    }

    // Boss 用 Forge 的 forge:bosses 实体标签判断，别的模组的 Boss 只要进了这个标签就算。
    private static boolean isBoss(LivingEntity entity) {
        return entity.getType().is(net.minecraftforge.common.Tags.EntityTypes.BOSSES);
    }
    private static boolean isElite(LivingEntity entity) {
        return isBoss(entity)||(entity instanceof net.minecraft.world.entity.monster.Enemy&&entity.getMaxHealth()>=60);
    }

    private static boolean speak(ServerPlayer player,Companion companion,String key,boolean departure) {
        long now=player.level().getGameTime();
        if(companion.entity==null||companion.entity.isRemoved()||(!departure&&hidden(player))
            ||GoryoVoice.busy(player)||now-companion.lastSilent<1200
            ||now<companion.silentReady.getOrDefault(key,0L)) return false;
        companion.entity.bubble("goryo.misaki."+key,100);
        if(key.equals("show")) companion.entity.playAnimation("wave");
        if(key.equals("low_health")) companion.entity.playAnimation("worry");
        companion.lastSilent=now;
        companion.silentReady.put(key,now+12000);
        return true;
    }

    private static void confirmCelebrations(ServerPlayer player,Companion companion) {
        var iterator=companion.celebrations.values().iterator();
        long now=player.level().getGameTime();
        while(iterator.hasNext()) {
            Celebration pending=iterator.next();
            LivingEntity target=pending.death.getEntity();
            if(pending.death.isCanceled() || target.isAlive() || now>pending.expires) {
                iterator.remove();
            } else if(target.isRemoved()) {
                iterator.remove();
                if(target.getRemovalReason()==net.minecraft.world.entity.Entity.RemovalReason.KILLED)
                    companion.celebrationReady=true;
            }
        }
        if(companion.celebrationReady && companion.entity!=null && !hidden(player)
            && !GoryoVoice.busy(player) && companion.entity.bubbleKey().isEmpty()
            && companion.entity.playAnimation("celebrate")) companion.celebrationReady=false;
    }
    private record Celebration(LivingDeathEvent death,long expires) {}

    private static void removeEntity(ServerPlayer player) {
        Companion companion=COMPANIONS.get(player.getUUID());
        if(companion!=null) {
            companion.celebrations.clear();
            companion.celebrationReady=false;
        }
        if(companion!=null&&companion.entity!=null&&!companion.entity.isRemoved()) {
            companion.entity.discard();
            companion.entity=null;
            companion.lastStage=0;
            if(player.isAlive()) GoryoVoice.mute(player);
        }
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if(event.getEntity() instanceof ServerPlayer player) removeEntity(player);
        COMPANIONS.remove(event.getEntity().getUUID());
        GoryoVoice.remove(event.getEntity().getUUID());
    }
    @SubscribeEvent public static void stop(ServerStoppingEvent event) {
        for(var companion:COMPANIONS.values()) if(companion.entity!=null) companion.entity.discard();
        COMPANIONS.clear();
        GoryoVoice.clear();
    }
    private static final class Companion {
        private MisakiSpiritEntity entity;
        private int lastStage;
        private UUID lastBlade;
        private long lastCombat,lastSilent,lastIdle,lastArt,lastStrong;
        private long lastNight=-1,nextYawn,streakReady;
        private final java.util.ArrayDeque<Long> kills=new java.util.ArrayDeque<>();
        private boolean celebrationReady;
        private final Map<UUID,Celebration> celebrations=new java.util.LinkedHashMap<>();
        private boolean wasRaining,wasBroken,wasSleeping;
        private final Map<String,Long> silentReady=new HashMap<>();
        private Companion(long now) { lastCombat=now; lastIdle=now; lastSilent=now-1200; nextYawn=now+2400; }
    }
}
