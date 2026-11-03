package org.example1.timespeed;

import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.SleepFinishedTimeEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

import java.util.*;

@Mod(Timespeed.MODID)
public class Timespeed {
    public static final String MODID = "timespeed";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Timespeed(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, Config.SERVER_SPEC);
        NeoForge.EVENT_BUS.register(new Events());
        modEventBus.addListener(Network::registerPayloads);
    }

    public static class Config {
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

    public static class State {
        public static float timeAccumulator = 0f;
        public static long serverCurrentSleepSpeed = 0L;
        public static long clientSleepSpeed = 0L;
        public static long lastSleepingCount = 0L;
        public static long lastSleepMessageTime = 0L;

        public static final Map<UUID, Long> lastPlayerActivity = new HashMap<>();
        public static final Map<UUID, Vec3> lastPlayerPos = new HashMap<>();
        public static final Map<UUID, Float> lastPlayerRot = new HashMap<>();
        public static final Map<UUID, Boolean> playerAfkState = new HashMap<>();

        public static void updateActivity(ServerPlayer player) {
            lastPlayerActivity.put(player.getUUID(), System.currentTimeMillis());
            lastPlayerPos.put(player.getUUID(), player.position());
            lastPlayerRot.put(player.getUUID(), player.getYRot());
        }

        public static boolean isAfk(ServerPlayer player) {
            if (!Config.ENABLE_AFK_SYSTEM.get()) return false;
            long lastActive = lastPlayerActivity.getOrDefault(player.getUUID(), 0L);
            long timeoutMillis = (long) Config.AFK_TIMEOUT_MINUTES.get() * 60 * 1000;
            return (System.currentTimeMillis() - lastActive) > timeoutMillis;
        }

        public static void reset() {
            serverCurrentSleepSpeed = 0L; timeAccumulator = 0f; lastSleepingCount = 0L; lastSleepMessageTime = 0L;
            lastPlayerActivity.clear(); lastPlayerPos.clear(); lastPlayerRot.clear();
            playerAfkState.clear();
        }
    }

    public static class Network {
        public record SleepSpeedPacket(long speed) implements CustomPacketPayload {
            public static final Type<SleepSpeedPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "sleep_speed"));
            public static final StreamCodec<ByteBuf, SleepSpeedPacket> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, SleepSpeedPacket::speed, SleepSpeedPacket::new);
            @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
        }

        public static void registerPayloads(final RegisterPayloadHandlersEvent event) {
            final PayloadRegistrar r = event.registrar(MODID);
            r.playToClient(SleepSpeedPacket.TYPE, SleepSpeedPacket.CODEC, (p, c) -> c.enqueueWork(() -> State.clientSleepSpeed = p.speed()));
        }
    }

    public static class Events {
        @SubscribeEvent public void onServerStarting(ServerStartingEvent event) { LOGGER.info("TimeSpeed Mod Loaded!"); }
        @SubscribeEvent public void onServerStopped(ServerStoppedEvent event) { State.reset(); }

        @SubscribeEvent public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                PacketDistributor.sendToPlayer(player, new Network.SleepSpeedPacket(State.serverCurrentSleepSpeed));
                State.updateActivity(player);
            }
        }

        @SubscribeEvent public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            UUID uuid = event.getEntity().getUUID();
            State.lastPlayerActivity.remove(uuid);
            State.lastPlayerPos.remove(uuid);
            State.lastPlayerRot.remove(uuid);
            State.playerAfkState.remove(uuid);
        }

        @SubscribeEvent public void onLivingDeath(LivingDeathEvent event) {
            if (Config.ENABLE_DEATH_COORDS.get() && event.getEntity() instanceof ServerPlayer p) {
                p.sendSystemMessage(Component.literal(String.format("☠ Координаты смерти: X: %d, Y: %d, Z: %d (%s)", p.blockPosition().getX(), p.blockPosition().getY(), p.blockPosition().getZ(), p.level().dimension().location().getPath())).withStyle(ChatFormatting.RED, ChatFormatting.ITALIC));
            }
        }

        @SubscribeEvent public void onChat(ServerChatEvent event) {
            ServerPlayer sender = event.getPlayer();
            State.updateActivity(sender);
            if (!Config.ENABLE_CHAT_PINGS.get()) return;

            String msg = event.getMessage().getString().toLowerCase(Locale.ROOT);
            for (ServerPlayer p : sender.server.getPlayerList().getPlayers()) {
                if (p == sender) continue;
                String name = p.getGameProfile().getName().toLowerCase(Locale.ROOT);
                if (msg.contains(name) || msg.contains("@" + name)) {
                    p.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.5F, 1.0F);
                }
            }
        }

        @SubscribeEvent public void onSleepFinished(SleepFinishedTimeEvent event) { event.setTimeAddition(0L); }

        private boolean canPlayerSleep(ServerPlayer player) {
            if (!player.isSleeping()) return false;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(i);
                if (!stack.isEmpty() && stack.getItem().toString().contains("cursed_ring")) return false;
            }
            if (player.getSleepingPos().isPresent()) {
                List<Monster> monsters = player.level().getEntitiesOfClass(Monster.class, new AABB(player.getSleepingPos().get()).inflate(8.0D, 5.0D, 8.0D), monster -> monster.isPreventingPlayerRest(player));
                if (!monsters.isEmpty()) return false;
            }
            return true;
        }

        @SubscribeEvent public void onLevelTick(LevelTickEvent.Post event) {
            Level level = event.getLevel();
            if (level.isClientSide) return;
            if (!(level instanceof ServerLevel serverLevel) || level.dimension() != Level.OVERWORLD || !level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)) return;

            List<ServerPlayer> players = serverLevel.players();
            if (players.isEmpty()) return;
            long gameTime = level.getGameTime();

            if (gameTime % 20 == 0) {
                for (ServerPlayer player : players) {
                    UUID uuid = player.getUUID();
                    if (player.distanceToSqr(State.lastPlayerPos.getOrDefault(uuid, Vec3.ZERO)) > 0.01 || Math.abs(player.getYRot() - State.lastPlayerRot.getOrDefault(uuid, 0f)) > 1.0f) {
                        State.updateActivity(player);
                    }
                    boolean isAfk = State.isAfk(player);
                    State.playerAfkState.put(uuid, isAfk);
                }
            }

            long timeOfDay = serverLevel.getDayTime() % 24000;
            boolean canSleepNow = (timeOfDay >= 12000 && timeOfDay < 23900) || (Config.ENABLE_THUNDER_SKIP.get() && serverLevel.isThundering());
            long totalValidPlayers = Math.max(1, players.stream().filter(p -> !p.isSpectator() && !State.playerAfkState.getOrDefault(p.getUUID(), false)).count());
            long sleepingPlayers = 0;

            for (ServerPlayer p : players) {
                if (p.isSleeping()) {
                    if (canPlayerSleep(p)) sleepingPlayers++;
                    else if (canSleepNow && gameTime % 40 == 0) p.displayClientMessage(Component.literal(Config.MSG_CANT_SLEEP.get()).withStyle(ChatFormatting.RED), true);
                }
            }

            float targetSpeedMultiplier = 0f;
            if (sleepingPlayers > 0 && canSleepNow) {
                float sleepPercentage = ((float) sleepingPlayers / totalValidPlayers) * 100.0f;
                targetSpeedMultiplier = Math.max(1.0f, Config.MAX_SLEEP_SPEED.get().floatValue() * (sleepPercentage / Config.TARGET_PERCENT.get().floatValue()));

                if (sleepingPlayers != State.lastSleepingCount) {
                    State.lastSleepingCount = sleepingPlayers;
                    if (gameTime - State.lastSleepMessageTime >= 100) {
                        serverLevel.getServer().getPlayerList().broadcastSystemMessage(Component.literal(String.format(Locale.US, Config.MSG_SLEEP_INFO.get(), sleepingPlayers, totalValidPlayers, String.format(Locale.US, "%.2f", targetSpeedMultiplier))).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), false);
                        State.lastSleepMessageTime = gameTime;
                    }
                }
            } else { State.lastSleepingCount = 0; }

            if (!canSleepNow && State.serverCurrentSleepSpeed > 0) {
                targetSpeedMultiplier = 0f; State.timeAccumulator = 0f;
                if (serverLevel.isRaining() || serverLevel.isThundering()) serverLevel.setWeatherParameters(6000, 0, false, false);
                serverLevel.getServer().getPlayerList().broadcastSystemMessage(Component.literal(Config.MSG_MORNING.get()).withStyle(ChatFormatting.YELLOW), false);
                for (ServerPlayer p : players) {
                    serverLevel.playSound(null, p.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.MASTER, 0.5f, 0.8f);
                    if (p.isSleeping()) p.stopSleeping();
                    if (Config.RESET_PHANTOMS_FOR_ALL.get()) p.resetStat(Stats.CUSTOM.get(Stats.TIME_SINCE_REST));
                }
            }

            if (targetSpeedMultiplier > 1.0f) {
                State.timeAccumulator += (targetSpeedMultiplier - 1.0f);
                int addTicks = (int) State.timeAccumulator;
                if (addTicks > 0) { serverLevel.setDayTime(serverLevel.getDayTime() + addTicks); State.timeAccumulator -= addTicks; }

                if ((long) targetSpeedMultiplier != State.serverCurrentSleepSpeed) {
                    State.serverCurrentSleepSpeed = (long) targetSpeedMultiplier;
                    PacketDistributor.sendToAllPlayers(new Network.SleepSpeedPacket(State.serverCurrentSleepSpeed));
                }
                if (timeOfDay > 12000 && timeOfDay < 23900) {
                    Component msg = Component.literal(String.format(Config.MSG_REWINDING.get(), Math.max(1, (int) ((24000 - timeOfDay) / (targetSpeedMultiplier * 20L))))).withStyle(ChatFormatting.AQUA);
                    for (ServerPlayer p : players) p.displayClientMessage(msg, true);
                }
            } else if (State.serverCurrentSleepSpeed != 0) {
                State.timeAccumulator = 0f; State.serverCurrentSleepSpeed = 0;
                PacketDistributor.sendToAllPlayers(new Network.SleepSpeedPacket(0));
            }
        }
    }
}