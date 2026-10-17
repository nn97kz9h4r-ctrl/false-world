package com.falseworld;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.world.entity.EquipmentSlot;
import com.mojang.datafixers.util.Pair;
import java.nio.file.*;
import java.io.*;
import java.util.logging.Logger;
import java.util.logging.FileHandler;
import java.util.logging.SimpleFormatter;
import java.util.Properties;
import java.util.*;

/** Server-side illusion engine. All fake blocks are packet-only, never written to the level. */
public final class FalseWorld implements ModInitializer {
    private static final Random RNG = new Random();
    private static final HorrorDirector MIMIC = new HorrorDirector();
    private static final FakePlayerPackets GHOST = new FakePlayerPackets();
    private static boolean enabled = true;
    private static final Properties CONFIG = new Properties();
    private static final Set<UUID> OPT_OUT = new HashSet<>();
    private static Path dataDir;
    private static UUID persistedVictim;
    private static long lastPersistenceTick;
    private static final Logger LOG = Logger.getLogger("FalseWorld");
    private static int mobEntityId = -1;
    private static long mobExpiry;
    private static int damageCap = 2;
    private static boolean allowDamage = false;
    private static boolean allowBlackout = true;
    private static int blackoutSeconds = 5;
    private static int eventMinSeconds = 45, eventMaxSeconds = 140;
    private static int maxPhase = 4;
    private static long lastBlackoutTick = -2400;
    private static int forcedPhase = -1;
    // Packet-only false-world session: remains in original dimension; no world edits.
    private static final Map<UUID, FalseSession> FALSE_SESSIONS = new HashMap<>();
    private static boolean falseWorldEnabled = true;
    private static int falseWorldChancePercent = 5;
    private static int falseWorldMinSeconds = 60, falseWorldMaxSeconds = 300;
    private static long nextFalseWorldChanceTick;
    private record FalseSession(UUID playerId, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> originDimension,
                                long endTick, long nextCorruptionTick, long startedTick) {}
    private static long nextGhostRefresh;
    private static UUID previousViewer;
    private static UUID victim, stolenIdentity;
    private static UUID lastStolenIdentity;
    private static final Map<UUID, IdentitySnapshot> identitySnapshots = new HashMap<>();

    /** A snapshot is immutable, so disguise selection is stable between encounters. */
    public record IdentitySnapshot(UUID playerId, String playerName, String textureProperty,
                                   String textureSignature, long capturedAtTick) {}
    private static long tick, nextEvent = 20 * 50;
    private static int events;
    private static final Map<BlockPos, Long> illusions = new HashMap<>();
    private static final String[] SOUND_IDS = {
        "minecraft:block.stone.step",
        "minecraft:block.wooden_door.open",
        "minecraft:block.gravel.break",
        "minecraft:entity.enderman.ambient",
        "minecraft:block.wood.hit"
    };
    private static final int SOUND_COOLDOWN_TICKS = 20 * 12;
    private static long lastSoundTick = -SOUND_COOLDOWN_TICKS;
    private static final String[] EARLY = {
        "§8You hear footsteps stop behind you.", "§7Something moved just outside your vision.",
        "§8The air feels strangely still.", "§8You could swear someone was watching."
    };
    private static final String[] LATE = {
        "§8That wasn't where the door was.", "§8Someone is walking in your footsteps.",
        "§4You are not alone.", "§8It knows where you sleep."
    };

    @Override public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(FalseWorld::tickServer);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(FalseWorld::loadConfigAndState);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(FalseWorld::saveState);
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
            dispatcher.register(Commands.literal("falseworld").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("dimension").then(Commands.literal("test").executes(c -> {
                    ServerPlayer p = getVictim(c.getSource().getServer());
                    if (p == null) { c.getSource().sendFailure(Component.literal("Select an online victim first: /falseworld target <name>")); return 0; }
                    startFalseSession(p, true);
                    c.getSource().sendSuccess(() -> Component.literal("False-world session started for victim"), false);
                    return 1;
                })).then(Commands.literal("return").executes(c -> {
                    ServerPlayer p = getVictim(c.getSource().getServer());
                    if (p == null || !FALSE_SESSIONS.containsKey(p.getUUID())) { c.getSource().sendFailure(Component.literal("No active false-world session")); return 0; }
                    endFalseSession(p);
                    c.getSource().sendSuccess(() -> Component.literal("False-world session ended"), false);
                    return 1;
                })).then(Commands.literal("status").executes(c -> {
                    ServerPlayer p = getVictim(c.getSource().getServer());
                    FalseSession session = p == null ? null : FALSE_SESSIONS.get(p.getUUID());
                    c.getSource().sendSuccess(() -> Component.literal(session == null ? "No false-world session" :
                        "False-world session: " + Math.max(0, (session.endTick - tick) / 20) + " seconds left"), false);
                    return 1;
                })))
                .then(Commands.literal("blackouttest").executes(c -> {
                    ServerPlayer p = getVictim(c.getSource().getServer());
                    if (p == null) return 0;
                    blackout(p); return 1;
                }))
                .then(Commands.literal("mobtest").executes(c -> {
                    ServerPlayer p = getVictim(c.getSource().getServer());
                    if (p == null) return 0;
                    fakeMob(p); return 1;
                }))
                .then(Commands.literal("timetest").executes(c -> {
                    ServerPlayer p = getVictim(c.getSource().getServer());
                    if (p == null) return 0;
                    p.connection.send(new ClientboundSetTimePacket(18000L, 18000L, false)); return 1;
                }))
                .then(Commands.literal("chattest").executes(c -> {
                    ServerPlayer p = getVictim(c.getSource().getServer());
                    if (p == null) return 0;
                    fakeChat(p, "Someone", "Did you see that?"); return 1;
                }))
                .then(Commands.literal("optout").then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                    String name = StringArgumentType.getString(c,"name");
                    ServerPlayer p = c.getSource().getServer().getPlayerList().getPlayers().stream()
                        .filter(x -> x.getGameProfile().name().equalsIgnoreCase(name)).findFirst().orElse(null);
                    if (p == null) return 0;
                    OPT_OUT.add(p.getUUID());
                    if (p.getUUID().equals(victim)) reset(c.getSource().getServer());
                    saveState(c.getSource().getServer()); return 1;
                })))
                .then(Commands.literal("optin").then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                    String name = StringArgumentType.getString(c,"name");
                    ServerPlayer p = c.getSource().getServer().getPlayerList().getPlayers().stream()
                        .filter(x -> x.getGameProfile().name().equalsIgnoreCase(name)).findFirst().orElse(null);
                    if (p == null) return 0;
                    OPT_OUT.remove(p.getUUID()); saveState(c.getSource().getServer()); return 1;
                })))
                .then(Commands.literal("reloadconfig").executes(c -> {
                    loadConfigAndState(c.getSource().getServer()); return 1;
                }))
                .then(Commands.literal("status").executes(c -> {
                    c.getSource().sendSuccess(() -> Component.literal("Victim=" + victim + "; copied=" + stolenIdentity + "; events=" + events + "; phase=" + phase() + "; mimic=" + MIMIC.snapshot().mode() + "; ghostVisible=" + GHOST.visible() + "; enabled=" + enabled), false);
                    return 1;
                }))
                .then(Commands.literal("enable").executes(c -> {
                    enabled = true; c.getSource().sendSuccess(() -> Component.literal("False World enabled"), true); return 1;
                }))
                .then(Commands.literal("disable").executes(c -> {
                    enabled = false; reset(c.getSource().getServer());
                    c.getSource().sendSuccess(() -> Component.literal("False World disabled and reset"), true); return 1;
                }))
                .then(Commands.literal("mimic").then(Commands.literal("spawn").executes(c -> {
                    ServerPlayer p = getVictim(c.getSource().getServer());
                    if (p == null) { c.getSource().sendFailure(Component.literal("Choose an online victim with /falseworld target")); return 0; }
                    ServerPlayer copy = c.getSource().getServer().getPlayerList().getPlayers().stream()
                        .filter(x -> !x.getUUID().equals(p.getUUID())).findFirst().orElse(null);
                    if (copy == null) { c.getSource().sendFailure(Component.literal("A second online player is needed for the disguise")); return 0; }
                    captureIdentity(copy); stolenIdentity = copy.getUUID(); MIMIC.reset();
                    MIMIC.begin(p, stolenIdentity, 4); nextGhostRefresh = 0;
                    c.getSource().sendSuccess(() -> Component.literal("Spawn requested; packet compatibility unverified"), false); return 1;
                })).then(Commands.literal("hide").executes(c -> {
                    GHOST.hide(getVictim(c.getSource().getServer())); MIMIC.reset();
                    c.getSource().sendSuccess(() -> Component.literal("Mimic hidden"), false); return 1;
                })).then(Commands.literal("chase").executes(c -> {
                    forcedPhase = 4;
                    c.getSource().sendSuccess(() -> Component.literal("Phase override set to 4 for stalking/chase"), false); return 1;
                })))
                .then(Commands.literal("phase").then(Commands.argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0,4)).executes(c -> {
                    forcedPhase = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "value");
                    c.getSource().sendSuccess(() -> Component.literal("Forced phase " + forcedPhase), false); return 1;
                })))
                .then(Commands.literal("illusiontest").executes(c -> {
                    ServerPlayer p = getVictim(c.getSource().getServer());
                    if (p == null) return 0; createIllusion(p); return 1;
                }))
                .then(Commands.literal("attacktest").executes(c -> {
                    ServerPlayer p = getVictim(c.getSource().getServer());
                    if (p == null) return 0; if (allowDamage && damageCap > 0) p.hurt(p.damageSources().magic(), damageCap); return 1;
                }))
                .then(Commands.literal("reset").executes(c -> {
                    reset(c.getSource().getServer());
                    c.getSource().sendSuccess(() -> Component.literal("False World reset; illusions restored."), true);
                    return 1;
                }))
                .then(Commands.literal("target").then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                    String name = StringArgumentType.getString(c, "name");
                    ServerPlayer p = c.getSource().getServer().getPlayerList().getPlayers().stream()
                        .filter(x -> x.getGameProfile().name().equalsIgnoreCase(name)).findFirst().orElse(null);
                    if (p == null || OPT_OUT.contains(p.getUUID())) { c.getSource().sendFailure(Component.literal("Player offline or opted out")); return 0; }
                    reset(c.getSource().getServer());
                    victim = p.getUUID(); nextEvent = tick + 20 * 15;
                    c.getSource().sendSuccess(() -> Component.literal("Secret target selected."), true);
                    return 1;
                })))
                .then(Commands.literal("disguiseinfo").executes(c -> {
                    IdentitySnapshot snap = stolenIdentity == null ? null : identitySnapshots.get(stolenIdentity);
                    c.getSource().sendSuccess(() -> Component.literal(snap == null
                        ? "No disguise snapshot captured yet."
                        : "Selected identity: " + snap.playerName()
                          + " | signed skin payload: " + (!snap.textureProperty().isEmpty())
                          + " | visual fake-player packet layer: not implemented"), false);
                    return 1;
                }))
                .then(Commands.literal("soundtest").executes(c -> {
                    ServerPlayer p = getVictim(c.getSource().getServer());
                    if (p == null) { c.getSource().sendFailure(Component.literal("No online victim")); return 0; }
                    playPrivateHorrorSound(p, true);
                    c.getSource().sendSuccess(() -> Component.literal("Sent positional sound to victim only."), false);
                    return 1;
                }))
                .then(Commands.literal("trigger").executes(c -> {
                    ServerPlayer p = getVictim(c.getSource().getServer());
                    if (p == null) { c.getSource().sendFailure(Component.literal("No online victim")); return 0; }
                    encounter(c.getSource().getServer(), p);
                    return 1;
                }))));
    }

    private static int phase() { return Math.min(maxPhase, forcedPhase >= 0 ? forcedPhase : Math.min(4, events / 3)); }
    private static ServerPlayer getVictim(MinecraftServer server) {
        return server.getPlayerList().getPlayers().stream().filter(p -> p.getUUID().equals(victim)).findFirst().orElse(null);
    }
    private static void reset(MinecraftServer server) {
        ServerPlayer p = getVictim(server);
        if (p != null) { endFalseSession(p); restore(p); }
        GHOST.hide(p);
        if (p != null) { p.removeEffect(MobEffects.BLINDNESS); p.removeEffect(MobEffects.DARKNESS); clearMob(p); }
        MIMIC.reset(); forcedPhase = -1; nextGhostRefresh = 0; illusions.clear(); identitySnapshots.clear(); lastStolenIdentity = null; victim = null; stolenIdentity = null; events = 0; lastSoundTick = -SOUND_COOLDOWN_TICKS;
        nextEvent = tick + 20 * 60;
    }
    private static void restore(ServerPlayer p) {
        for (BlockPos pos : illusions.keySet()) {
            if (p.level().isLoaded(pos)) p.connection.send(new ClientboundBlockUpdatePacket(pos, p.level().getBlockState(pos)));
        }
    }
    private static void tickServer(MinecraftServer server) {
        tick++;
        ServerPlayer p = getVictim(server);
        if (!enabled) return;
        if (p != null) tickFalseSession(p);
        if (p == null && victim != null) FALSE_SESSIONS.remove(victim);
        if (persistedVictim != null && victim == null) { victim = persistedVictim; persistedVictim = null; }
        if (tick - lastPersistenceTick > 1200) { saveState(server); lastPersistenceTick = tick; }
        if (p != null && mobEntityId >= 0 && tick >= mobExpiry) clearMob(p);
        if (p == null && GHOST.visible()) { GHOST.hide(null); MIMIC.reset(); }
        if (p != null && MIMIC.active() && stolenIdentity != null && tick >= nextGhostRefresh) {
            IdentitySnapshot snap = identitySnapshots.get(stolenIdentity);
            if (snap != null) {
                var state = MIMIC.snapshot();
                GHOST.show(p, snap.playerId(), snap.playerName(), snap.textureProperty(),
                    snap.textureSignature(), state.position(), state.yaw());
                nextGhostRefresh = tick + 5;
            }
        } else if (p != null && !MIMIC.active() && GHOST.visible()) GHOST.hide(p);
        if (p != null && !illusions.isEmpty()) {
            Iterator<Map.Entry<BlockPos, Long>> it = illusions.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<BlockPos, Long> e = it.next();
                if (tick >= e.getValue()) {
                    if (p.level().isLoaded(e.getKey())) p.connection.send(new ClientboundBlockUpdatePacket(e.getKey(), p.level().getBlockState(e.getKey())));
                    it.remove();
                }
            }
        }
        if (p != null && MIMIC.active()) {
            if (MIMIC.tick(p, phase())) {
                // A server-authoritative attack. No phantom entity hitboxes needed.
                if (allowDamage && damageCap > 0) p.hurt(p.damageSources().magic(), damageCap);
                playPrivateHorrorSound(p, true);
            }
        }
        if (tick < nextEvent) return;
        nextEvent = tick + 20L * (eventMinSeconds + RNG.nextInt(Math.max(1, eventMaxSeconds - eventMinSeconds + 1)));
        List<ServerPlayer> online = server.getPlayerList().getPlayers().stream().filter(x -> !OPT_OUT.contains(x.getUUID())).toList();
        if (online.size() < 2) return;
        if (p == null) {
            // When a target disconnects, restore their old client state on rejoin via chunk reload.
            illusions.clear(); victim = online.get(RNG.nextInt(online.size())).getUUID();
            stolenIdentity = null; events = 0; p = getVictim(server);
        }
        if (p != null && !OPT_OUT.contains(p.getUUID()) && !FALSE_SESSIONS.containsKey(p.getUUID())) encounter(server, p);
    }
    /**
     * Preserve the selected player's Mojang-signed skin payload, if present.
     * This is the data required by a future client renderer, not a rendered NPC.
     */
    private static void captureIdentity(ServerPlayer copy) {
        String texture = "";
        String signature = "";
        var properties = copy.getGameProfile().properties().get("textures");
        if (!properties.isEmpty()) {
            var property = properties.iterator().next();
            texture = property.value();
            signature = property.signature() == null ? "" : property.signature();
        }
        identitySnapshots.put(copy.getUUID(), new IdentitySnapshot(
            copy.getUUID(), copy.getGameProfile().name(), texture, signature, tick));
    }

    private static void encounter(MinecraftServer server, ServerPlayer p) {
        List<ServerPlayer> others = server.getPlayerList().getPlayers().stream().filter(x -> !x.getUUID().equals(p.getUUID())).toList();
        if (others.isEmpty()) return;
        List<ServerPlayer> alternatives = others.stream()
            .filter(x -> !x.getUUID().equals(lastStolenIdentity)).toList();
        if (!alternatives.isEmpty()) others = alternatives;
        ServerPlayer copy = others.get(RNG.nextInt(others.size()));
        stolenIdentity = copy.getUUID();
        lastStolenIdentity = stolenIdentity;
        captureIdentity(copy);
        if (phase() >= 1) { MIMIC.begin(p, copy.getUUID(), phase()); nextGhostRefresh = 0; }
        events++;
        if (falseWorldEnabled && phase() >= 3 && !FALSE_SESSIONS.containsKey(p.getUUID())
            && tick >= nextFalseWorldChanceTick && RNG.nextInt(100) < falseWorldChancePercent) {
            startFalseSession(p, false);
            return;
        }
        int stage = phase();
        int choice = RNG.nextInt(stage >= 2 ? 11 : 5);
        if (choice == 0) p.sendSystemMessage(Component.literal(EARLY[RNG.nextInt(EARLY.length)]));
        else if (choice == 1) p.sendSystemMessage(Component.literal("§8Was that §f" + copy.getGameProfile().name() + "§8?"));
        else if (choice == 2) p.sendSystemMessage(Component.literal(stage >= 3 ? LATE[RNG.nextInt(LATE.length)] : EARLY[RNG.nextInt(EARLY.length)]));
        else if (choice == 3) { createIllusion(p); playPrivateHorrorSound(p, false); }
        else if (choice == 7) blackout(p);
        else if (choice == 8) fakeMob(p);
        else if (choice == 9) fakeChat(p, copy.getGameProfile().name(), "I'm right behind you.");
        else if (choice == 10) p.connection.send(new ClientboundSetTimePacket(18000L, 18000L, false));
        else { playPrivateHorrorSound(p, false);
            p.sendSystemMessage(Component.literal("§8You remember " + copy.getGameProfile().name() + " being somewhere else.")); }
    }
    /**
     * Send a vanilla positional sound directly to the victim's connection.
     * Unlike ServerLevel.playSound, this does NOT broadcast to nearby players.
     * The client performs stereo positioning based on the supplied coordinates.
     */
    private static void playPrivateHorrorSound(ServerPlayer p, boolean force) {
        if (!force && tick - lastSoundTick < SOUND_COOLDOWN_TICKS) return;
        lastSoundTick = tick;
        double angle = RNG.nextDouble() * Math.PI * 2;
        double distance = 3.0 + RNG.nextDouble() * 8.0;
        double x = p.getX() + Math.cos(angle) * distance;
        double y = p.getY() + RNG.nextInt(5) - 2;
        double z = p.getZ() + Math.sin(angle) * distance;
        String soundId = SOUND_IDS[RNG.nextInt(SOUND_IDS.length)];
        p.connection.send(new ClientboundSoundPacket(
            Holder.direct(SoundEvent.createVariableRangeEvent(Identifier.parse(soundId))),
            SoundSource.AMBIENT,
            x, y, z,
            0.65f + RNG.nextFloat() * 0.35f,
            0.75f + RNG.nextFloat() * 0.35f,
            RNG.nextLong()
        ));
    }

    private static void createIllusion(ServerPlayer p) {
        if (illusions.size() >= 8) return;
        BlockPos base = p.blockPosition();
        for (int attempt = 0; attempt < 24; attempt++) {
            BlockPos pos = base.offset(RNG.nextInt(11)-5, RNG.nextInt(5)-2, RNG.nextInt(11)-5);
            if (!p.level().isLoaded(pos) || pos.distSqr(base) < 6 || illusions.containsKey(pos)) continue;
            BlockState actual = p.level().getBlockState(pos);
            // Illusions are non-colliding and temporary; no permanent block mutation.
            if (actual.isAir()) {
                p.connection.send(new ClientboundBlockUpdatePacket(pos, Blocks.COBWEB.defaultBlockState()));
                illusions.put(pos.immutable(), tick + 20L * (8 + RNG.nextInt(12)));
                return;
            }
        }
        p.sendSystemMessage(Component.literal("§8For an instant, the walls seem unfamiliar."));
    }

    /**
     * False World overlay: private time/block/sound packets while player continues
     * walking in the real world. Exact-position return is automatic because no
     * teleport happens. This is NOT a separately registered Minecraft dimension.
     */
    private static void startFalseSession(ServerPlayer p, boolean forced) {
        if (!falseWorldEnabled || OPT_OUT.contains(p.getUUID())) return;
        if (FALSE_SESSIONS.containsKey(p.getUUID())) return;
        int duration = falseWorldMinSeconds + RNG.nextInt(falseWorldMaxSeconds - falseWorldMinSeconds + 1);
        FALSE_SESSIONS.put(p.getUUID(), new FalseSession(p.getUUID(), p.level().dimension(),
            tick + duration * 20L, tick, tick));
        nextFalseWorldChanceTick = tick + 20L * 600;
        p.connection.send(new ClientboundSetTimePacket(18000L, 18000L, false));
        p.sendSystemMessage(Component.literal("§8§oThe world folds inward. Everything feels wrong."));
        playPrivateHorrorSound(p, true);
        LOG.info("false_world_enter player=" + p.getUUID() + " durationSeconds=" + duration + " forced=" + forced);
    }

    private static void tickFalseSession(ServerPlayer p) {
        FalseSession session = FALSE_SESSIONS.get(p.getUUID());
        if (session == null) return;
        if (tick >= session.endTick || !p.level().dimension().equals(session.originDimension)) {
            endFalseSession(p);
            return;
        }
        if (tick >= session.nextCorruptionTick) {
            // Private visual corruption; no block writes, no effects on other players.
            p.connection.send(new ClientboundSetTimePacket(18000L, 18000L, false));
            createIllusion(p);
            if (RNG.nextInt(3) == 0) playPrivateHorrorSound(p, true);
            if (RNG.nextInt(5) == 0) blackout(p);
            FALSE_SESSIONS.put(p.getUUID(), new FalseSession(session.playerId,
                session.originDimension, session.endTick, tick + 20L * (4 + RNG.nextInt(5)), session.startedTick));
        }
    }

    private static void endFalseSession(ServerPlayer p) {
        FalseSession session = FALSE_SESSIONS.remove(p.getUUID());
        if (session == null) return;
        restore(p);
        illusions.clear();
        // Clear only the two effects applied by this mod. If another mod grants
        // these effects, a future implementation should track ownership first.
        p.removeEffect(MobEffects.BLINDNESS);
        p.removeEffect(MobEffects.DARKNESS);
        // Resync the real world time/weather/border to the player.
        p.level().getServer().getPlayerList().sendLevelInfo(p, (ServerLevel) p.level());
        p.sendSystemMessage(Component.literal("§7§oThe false world releases you."));
        LOG.info("false_world_return player=" + p.getUUID() + " position=" + p.blockPosition());
    }

    /** Screen obscuring uses server-issued vanilla blindness and darkness effects.
     * This is intentionally NOT claimed to be a packet-only visual overlay. */
    private static void blackout(ServerPlayer p) {
        if (!allowBlackout || tick - lastBlackoutTick < 20 * 30) return;
        lastBlackoutTick = tick;
        int duration = Math.max(1, Math.min(12, blackoutSeconds)) * 20;
        p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, duration, 0, false, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, duration, 0, false, false, false));
        LOG.info("blackout victim=" + p.getUUID() + " ticks=" + duration);
    }
    /** System messages are deliberately marked as SYSTEM, not forged signed chat. */
    private static void fakeChat(ServerPlayer p, String name, String message) {
        p.sendSystemMessage(Component.literal("§7<" + name + "> §f" + message));
        LOG.info("fake_chat victim=" + p.getUUID() + " name=" + name);
    }
    private static void fakeMob(ServerPlayer p) {
        clearMob(p);
        ServerLevel level = (ServerLevel)p.level();
        var mob = RNG.nextBoolean() ? new Creeper(EntityType.CREEPER, level) : new EnderMan(EntityType.ENDERMAN, level);
        Vec3 pos = p.position().add(RNG.nextInt(19)-9, 0, RNG.nextInt(19)-9);
        mob.setPos(pos.x, pos.y, pos.z);
        mobEntityId = mob.getId();
        p.connection.send(new ClientboundAddEntityPacket(mob, 0, mob.blockPosition()));
        mobExpiry = tick + 20 * 5;
        LOG.info("fake_mob victim=" + p.getUUID() + " entity=" + mobEntityId);
    }
    private static void clearMob(ServerPlayer p) {
        if (mobEntityId < 0) return;
        p.connection.send(new ClientboundRemoveEntitiesPacket(mobEntityId));
        mobEntityId = -1;
    }
    private static void loadConfigAndState(MinecraftServer server) {
        try {
            dataDir = server.getServerDirectory().resolve("config").resolve("falseworld");
            Files.createDirectories(dataDir);
            Path cfg = dataDir.resolve("falseworld.properties");
            CONFIG.clear();
            if (Files.exists(cfg)) try (var in = Files.newInputStream(cfg)) { CONFIG.load(in); }
            CONFIG.putIfAbsent("damage.enabled", "false");
            CONFIG.putIfAbsent("damage.max", "2");
            CONFIG.putIfAbsent("blackout.enabled", "true");
            CONFIG.putIfAbsent("blackout.seconds", "5");
            CONFIG.putIfAbsent("event.min_seconds", "45");
            CONFIG.putIfAbsent("event.max_seconds", "140");
            CONFIG.putIfAbsent("phase.max", "4");
            CONFIG.putIfAbsent("false_world.enabled", "true");
            CONFIG.putIfAbsent("false_world.chance_percent", "5");
            CONFIG.putIfAbsent("false_world.min_seconds", "60");
            CONFIG.putIfAbsent("false_world.max_seconds", "300");
            try (var out = Files.newOutputStream(cfg)) { CONFIG.store(out, "The False World settings"); }
            allowDamage = Boolean.parseBoolean(CONFIG.getProperty("damage.enabled"));
            damageCap = Math.max(0, Math.min(8, Integer.parseInt(CONFIG.getProperty("damage.max"))));
            allowBlackout = Boolean.parseBoolean(CONFIG.getProperty("blackout.enabled"));
            blackoutSeconds = Math.max(1, Math.min(12, Integer.parseInt(CONFIG.getProperty("blackout.seconds"))));
            eventMinSeconds = Math.max(10, Integer.parseInt(CONFIG.getProperty("event.min_seconds")));
            eventMaxSeconds = Math.max(eventMinSeconds, Integer.parseInt(CONFIG.getProperty("event.max_seconds")));
            maxPhase = Math.max(0, Math.min(4, Integer.parseInt(CONFIG.getProperty("phase.max"))));
            falseWorldEnabled = Boolean.parseBoolean(CONFIG.getProperty("false_world.enabled"));
            falseWorldChancePercent = Math.max(0, Math.min(100, Integer.parseInt(CONFIG.getProperty("false_world.chance_percent"))));
            falseWorldMinSeconds = Math.max(60, Math.min(300, Integer.parseInt(CONFIG.getProperty("false_world.min_seconds"))));
            falseWorldMaxSeconds = Math.max(falseWorldMinSeconds, Math.min(300, Integer.parseInt(CONFIG.getProperty("false_world.max_seconds"))));
            Path stateFile = dataDir.resolve("state.properties");
            if (Files.exists(stateFile)) {
                Properties state = new Properties();
                try (var in = Files.newInputStream(stateFile)) { state.load(in); }
                String target = state.getProperty("victim", "");
                if (!target.isEmpty()) persistedVictim = UUID.fromString(target);
                events = Integer.parseInt(state.getProperty("events", "0"));
                OPT_OUT.clear();
                for (String item : state.getProperty("optouts", "").split(","))
                    if (!item.isBlank()) OPT_OUT.add(UUID.fromString(item));
            }
            LOG.info("False World configuration loaded");
        } catch (Exception e) { LOG.warning("False World config/state error: " + e); }
    }
    private static void saveState(MinecraftServer server) {
        if (dataDir == null) return;
        try {
            Properties state = new Properties();
            UUID target = victim != null ? victim : persistedVictim;
            state.setProperty("victim", target == null ? "" : target.toString());
            state.setProperty("events", Integer.toString(events));
            state.setProperty("optouts", String.join(",", OPT_OUT.stream().map(UUID::toString).toList()));
            Path temp = dataDir.resolve("state.tmp");
            try (var out = Files.newOutputStream(temp)) { state.store(out, "False World save state"); }
            Files.move(temp, dataDir.resolve("state.properties"), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { LOG.warning("False World state save failed: " + e); }
    }

}
