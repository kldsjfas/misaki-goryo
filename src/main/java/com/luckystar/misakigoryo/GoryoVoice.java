package com.luckystar.misakigoryo;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** Serializes a companion's voice across all event sources. No sound may interrupt another. */
public final class GoryoVoice {
    private static final Map<UUID,State> STATES=new HashMap<>();
    private static final Map<String,Integer> DURATION=Map.of(
        "fanfare",76,"awake",151,"attack",10,"combo_finish",49,"skill",41,
        "skill_dragon",49,"kill",34,"win",39,"heavyhurt",11,"dead",21);
    private GoryoVoice() {}

    public static boolean play(ServerPlayer owner,String event) {
        return play(owner,event,false);
    }

    private static boolean play(ServerPlayer owner,String event,boolean bossSequence) {
        int stage = GoryoBladeService.stage(owner.getMainHandItem());
        if(stage<4 || GoryoSpiritEvents.hidden(owner)) return false;
        if((event.equals("fanfare") && stage!=4) || (event.equals("awake") && stage!=5)) return false;
        long now=owner.level().getGameTime();
        State state=STATES.computeIfAbsent(owner.getUUID(),key->new State());
        if(now<state.cooldowns.getOrDefault(event,0L)) return false;
        boolean summon=event.equals("fanfare")||event.equals("awake");
        // 切刀的召唤台词一定要响：客户端会先停掉正在播的那句再放，所以不会叠音。
        if((state.bossStep>0&&!bossSequence&&!summon) || (now<state.busyUntil&&!summon) || (GoryoSpiritEvents.silentBusy(owner)&&!summon)) {
            if(!bossSequence && priority(event)>0 && (state.pending.isEmpty() || priority(event)>=priority(state.pending))) {
                state.pending=event;
                state.pendingAt=now;
            }
            return false;
        }
        state.busyUntil=now+DURATION.getOrDefault(event,40);
        state.lastEvent=event;
        state.started=now;
        state.winFinished=false;
        // 冷却只防同一句连刷；真正防叠音的是上面的 busyUntil。
        int cooldown=switch(event) {
            case "fanfare","awake" -> 0;
            case "attack" -> 14;
            case "combo_finish" -> 30;
            case "heavyhurt" -> 40;
            case "kill" -> 20;
            default -> 1;
        };
        state.cooldowns.put(event,now+cooldown);
        GoryoNetwork.voice(owner,event);
        GoryoSpiritEvents.voiceStarted(owner,event,state.busyUntil);
        return true;
    }

    public static void bossKilled(ServerPlayer owner) {
        if(GoryoBladeService.stage(owner.getMainHandItem())<4 || GoryoSpiritEvents.hidden(owner)) return;
        State state=STATES.computeIfAbsent(owner.getUUID(),key->new State());
        if(state.bossStep>0) return;
        state.bossStep=1;
        tick(owner);
    }

    private static int priority(String event) {
        return switch(event) {
            case "win" -> 3;
            case "fanfare", "awake" -> 2;
            case "skill", "skill_dragon", "kill", "combo_finish" -> 1;
            default -> 0;
        };
    }

    public static boolean busy(ServerPlayer owner) {
        State state=STATES.get(owner.getUUID());
        return state!=null && state.busyUntil>owner.level().getGameTime();
    }

    public static void tick(ServerPlayer owner) {
        State state=STATES.get(owner.getUUID());
        if(state==null) return;
        if(GoryoBladeService.stage(owner.getMainHandItem())<4 || GoryoSpiritEvents.hidden(owner)) {
            mute(owner);
            return;
        }
        if(busy(owner) || GoryoSpiritEvents.silentBusy(owner)) return;
        if(state.bossStep==3) {
            if(!state.winFinished && owner.level().getGameTime()<=state.busyUntil+40) return;
            state.bossStep=0;
        }
        if(state.bossStep>0) {
            if(play(owner,state.bossStep==1?"kill":"win",true)) state.bossStep++;
            return;
        }
        if(state.pending.isEmpty()) return;
        String pending=state.pending;
        state.pending="";
        // 排队超过 1.5 秒的台词已经和眼前的事对不上了，直接丢掉。
        if(owner.level().getGameTime()-state.pendingAt<=30) play(owner,pending);
    }

    public static void finished(ServerPlayer owner,String event) {
        State state=STATES.get(owner.getUUID());
        long now=owner.level().getGameTime();
        if(state!=null&&state.lastEvent.equals(event)&&!state.winFinished
            &&now>=state.started+15&&now<=state.busyUntil+40) {
            state.winFinished=true;
            GoryoSpiritEvents.voiceFinished(owner,event,now);
        }
    }

    public static void mute(ServerPlayer owner) {
        State state=STATES.get(owner.getUUID());
        if(state==null) return;
        boolean hadVoice=!state.lastEvent.isEmpty() || !state.pending.isEmpty() || state.bossStep>0;
        state.busyUntil=0;
        state.pending="";
        state.lastEvent="";
        state.bossStep=0;
        state.winFinished=false;
        if(hadVoice) GoryoNetwork.mute(owner);
    }
    public static void remove(UUID owner) { STATES.remove(owner); }
    public static void clear() { STATES.clear(); }
    private static final class State {
        private long busyUntil,started,pendingAt;
        private String lastEvent="";
        private String pending="";
        private int bossStep;
        private boolean winFinished;
        private final Map<String,Long> cooldowns=new HashMap<>();
    }
}
