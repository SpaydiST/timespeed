package org.example1.timespeed;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    public static final ModConfigSpec SERVER_SPEC;

    public static final ModConfigSpec.DoubleValue TARGET_PERCENT, MAX_SLEEP_SPEED;
    public static final ModConfigSpec.BooleanValue ENABLE_AFK_SYSTEM, RESET_PHANTOMS_FOR_ALL, ENABLE_THUNDER_SKIP, ENABLE_DEATH_COORDS, ENABLE_CHAT_PINGS;
    public static final ModConfigSpec.IntValue AFK_TIMEOUT_MINUTES;
    public static final ModConfigSpec.ConfigValue<String> MSG_CANT_SLEEP, MSG_SLEEP_INFO, MSG_MORNING, MSG_REWINDING;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("Sleep_Mechanics");
        TARGET_PERCENT = builder.defineInRange("targetPercentForMaxSpeed", 60.0, 1.0, 100.0);
        MAX_SLEEP_SPEED = builder.defineInRange("maxSleepSpeed", 100.0, 2.0, 10000.0);
        builder.pop();

        builder.push("QoL_Features");
        ENABLE_AFK_SYSTEM = builder.define("enableAfkSystem", true);
        AFK_TIMEOUT_MINUTES = builder.defineInRange("afkTimeoutMinutes", 5, 1, 60);
        RESET_PHANTOMS_FOR_ALL = builder.define("resetPhantomsForAll", true);
        ENABLE_THUNDER_SKIP = builder.define("enableThunderSkip", true);
        ENABLE_DEATH_COORDS = builder.define("enableDeathCoords", true);
        ENABLE_CHAT_PINGS = builder.define("enableChatPings", true);
        builder.pop();

        builder.push("Messages");
        MSG_CANT_SLEEP = builder.define("msgCantSleep", "Что-то мешает вам уснуть. Вы не участвуете в ускорении ночи!");
        MSG_SLEEP_INFO = builder.define("msgSleepInfo", "🛌 Спят: %d/%d (Ускорение: x%s)");
        MSG_MORNING = builder.define("msgMorning", "🌅 Наступило утро!");
        MSG_REWINDING = builder.define("msgRewinding", "🌙 Перемотка ночи... До утра: %d сек.");
        builder.pop();

        SERVER_SPEC = builder.build();
    }
}