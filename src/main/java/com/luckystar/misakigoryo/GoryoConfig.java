package com.luckystar.misakigoryo;

import net.minecraftforge.common.ForgeConfigSpec;

// 数值按原版拔刀剑的尺度定：刀的基础攻击 4～9，特殊攻击每段伤害 = 玩家当前攻击力 × 倍率。
public final class GoryoConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue MAX_TARGETS;
    public static final ForgeConfigSpec.IntValue MAX_ACTIVE_ARTS;
    public static final ForgeConfigSpec.IntValue SAKURA_COOLDOWN;
    public static final ForgeConfigSpec.IntValue GAWOO_COOLDOWN;
    public static final ForgeConfigSpec.IntValue DRAGON_COOLDOWN;
    public static final ForgeConfigSpec.DoubleValue SAKURA_DAMAGE;
    public static final ForgeConfigSpec.DoubleValue GAWOO_DAMAGE;
    public static final ForgeConfigSpec.DoubleValue DRAGON_DAMAGE;
    public static final ForgeConfigSpec.IntValue AWAKEN_KILLS;
    public static final ForgeConfigSpec.IntValue AWAKEN_SOULS;
    public static final ForgeConfigSpec.BooleanValue FLIGHT;

    static {
        var builder = new ForgeConfigSpec.Builder();
        builder.push("arts");
        MAX_TARGETS = builder.comment("Targets tested per special attack pulse.").defineInRange("maxTargetsPerPulse", 16, 1, 32);
        MAX_ACTIVE_ARTS = builder.comment("Special attacks allowed to run at the same time on the server.").defineInRange("maxConcurrentArts", 64, 1, 128);
        SAKURA_COOLDOWN = builder.defineInRange("sakuraCooldownTicks", 60, 20, 12000);
        GAWOO_COOLDOWN = builder.defineInRange("gawooCooldownTicks", 100, 40, 12000);
        DRAGON_COOLDOWN = builder.defineInRange("dragonCooldownTicks", 160, 60, 12000);
        SAKURA_DAMAGE = builder.comment("Damage of one Sakura / Gawoo pulse, as a multiple of the wielder's attack damage.")
            .defineInRange("sakuraPulseMultiplier", 1.0, 0.1, 10.0);
        GAWOO_DAMAGE = builder.comment("Damage of one Dimension Cut pulse, as a multiple of the wielder's attack damage.")
            .defineInRange("dimensionCutPulseMultiplier", 0.6, 0.1, 10.0);
        DRAGON_DAMAGE = builder.comment("Damage of one True Dragon pulse, as a multiple of the wielder's attack damage.")
            .defineInRange("dragonPulseMultiplier", 0.6, 0.1, 10.0);
        builder.pop().push("growth");
        AWAKEN_KILLS = builder.comment("Kills on the blade needed for Gawoo-giri to awaken by itself.").defineInRange("awakenKills", 300, 0, 100000);
        AWAKEN_SOULS = builder.comment("Proud souls on the blade needed for Gawoo-giri to awaken by itself.").defineInRange("awakenProudSouls", 2000, 0, 1000000);
        builder.pop().push("passives");
        FLIGHT = builder.comment("Stage 4 and 5 blades let the holder fly like in creative mode.").define("bladeFlight", true);
        builder.pop();
        SPEC = builder.build();
    }

    private GoryoConfig() {}
}
