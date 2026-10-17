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
import java.util.*;

/** Server-side illusion engine. All fake blocks are packet-only, never written to the level. */
public final class FalseWorld implements ModInitializer {
    private static final Random RNG = new Random();
    private static final HorrorDirector MIMIC = new HorrorDirector();
    private static final FakePlayerPackets GHOST = new FakePlayerPackets();
    private static boolean enabled = true;
    private static int forcedPhase = -1;
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
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
            dispatcher.register(Commands.literal("falseworld").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
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
                    if (p == null) return 0; p.hurt(p.damageSources().magic(), 2.0f); return 1;
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
                    if (p == null) { c.getSource().sendFailure(Component.literal("Player not online")); return 0; }
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

    private static int phase() { return forcedPhase >= 0 ? forcedPhase : Math.min(4, events / 3); }
    private static ServerPlayer getVictim(MinecraftServer server) {
        return server.getPlayerList().getPlayers().stream().filter(p -> p.getUUID().equals(victim)).findFirst().orElse(null);
    }
    private static void reset(MinecraftServer server) {
        ServerPlayer p = getVictim(server);
        if (p != null) restore(p);
        GHOST.hide(p);
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
                p.hurt(p.damageSources().magic(), 2.0f);
                playPrivateHorrorSound(p, true);
            }
        }
        if (tick < nextEvent) return;
        nextEvent = tick + 20L * (45 + RNG.nextInt(100));
        List<ServerPlayer> online = server.getPlayerList().getPlayers();
        if (online.size() < 2) return;
        if (p == null) {
            // When a target disconnects, restore their old client state on rejoin via chunk reload.
            illusions.clear(); victim = online.get(RNG.nextInt(online.size())).getUUID();
            stolenIdentity = null; events = 0; p = getVictim(server);
        }
        if (p != null) encounter(server, p);
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
        int stage = phase();
        int choice = RNG.nextInt(stage >= 2 ? 7 : 4);
        if (choice == 0) p.sendSystemMessage(Component.literal(EARLY[RNG.nextInt(EARLY.length)]));
        else if (choice == 1) p.sendSystemMessage(Component.literal("§8Was that §f" + copy.getGameProfile().name() + "§8?"));
        else if (choice == 2) p.sendSystemMessage(Component.literal(stage >= 3 ? LATE[RNG.nextInt(LATE.length)] : EARLY[RNG.nextInt(EARLY.length)]));
        else if (choice == 3) { createIllusion(p); playPrivateHorrorSound(p, false); }
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
}
