package com.falseworld;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.world.entity.EquipmentSlot;
import com.mojang.datafixers.util.Pair;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.UUID;

/**
 * Experimental server-only vanilla fake player packets. Never adds the actor to
 * ServerLevel or player list; only the target client receives these packets.
 * 1.21.11 constructor signatures MUST be checked by compiling against Mojang mappings.
 */
public final class FakePlayerPackets {
    private ServerPlayer actor;
    private int entityId = -1;
    private Vec3 shownAt;
    private UUID identity;
    private String name;
    private String texture;
    private String signature;
    private boolean visible;

    public boolean visible() { return visible; }
    public void hide(ServerPlayer viewer) {
        if (viewer != null && visible) {
            viewer.connection.send(new ClientboundRemoveEntitiesPacket(entityId));
        }
        visible = false;
        actor = null;
        entityId = -1;
        shownAt = null;
    }
    public void show(ServerPlayer viewer, UUID id, String username,
                     String skin, String skinSignature, Vec3 pos, float yaw) {
        if (viewer == null || id == null || username == null) return;
        boolean different = !id.equals(identity) || actor == null;
        if (different) {
            hide(viewer);
            identity = id; name = username; texture = skin; signature = skinSignature;
            GameProfile profile = new GameProfile(id, username);
            if (skin != null && !skin.isEmpty()) {
                profile.properties().put("textures", new Property("textures", skin,
                    skinSignature == null || skinSignature.isEmpty() ? null : skinSignature));
            }
            MinecraftServer server = viewer.getServer();
            actor = new ServerPlayer(server, (ServerLevel) viewer.level(), profile,
                ClientInformation.createDefault());
            entityId = actor.getId();
            // Player info must arrive before spawn for the vanilla player renderer.
            viewer.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(actor)));
        }
        if (visible && shownAt != null && shownAt.distanceToSqr(pos) < 0.12) return;
        if (visible) {
            actor.setPos(pos.x, pos.y, pos.z);
            actor.setYRot(yaw);
            actor.setYHeadRot(yaw);
            viewer.connection.send(new ClientboundTeleportEntityPacket(actor));
            shownAt = pos;
            return;
        }
        actor.setPos(pos.x, pos.y, pos.z);
        actor.setYRot(yaw);
        actor.setYHeadRot(yaw);
        actor.setYBodyRot(yaw);
        viewer.connection.send(new ClientboundAddEntityPacket(actor, 0, actor.blockPosition()));
        // The fake's equipment must be copied separately from the real player
        // (requires a reference to the impersonated player at the call site).
        shownAt = pos;
        visible = true;
    }
}
