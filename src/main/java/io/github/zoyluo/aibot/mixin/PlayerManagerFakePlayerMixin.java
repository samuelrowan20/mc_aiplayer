package io.github.zoyluo.aibot.mixin;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.network.AINetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PlayerManager.class)
public abstract class PlayerManagerFakePlayerMixin {
    @Redirect(method = "respawnPlayer", at = @At(value = "NEW",
            target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/world/ServerWorld;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/network/packet/c2s/common/SyncedClientOptions;)Lnet/minecraft/server/network/ServerPlayerEntity;"))
    private ServerPlayerEntity aibot$preservePlayerType(MinecraftServer server,
            net.minecraft.server.world.ServerWorld world, com.mojang.authlib.GameProfile profile,
            net.minecraft.network.packet.c2s.common.SyncedClientOptions options,
            ServerPlayerEntity oldPlayer, boolean alive, net.minecraft.entity.Entity.RemovalReason reason) {
        return oldPlayer instanceof AIPlayerEntity
                ? new AIPlayerEntity(server, world, profile, options)
                : new ServerPlayerEntity(server, world, profile, options);
    }

    @Shadow
    @Final
    private MinecraftServer server;

    @Redirect(
            method = "onPlayerConnect",
            at = @At(
                    value = "NEW",
                    target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/network/ClientConnection;Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/server/network/ConnectedClientData;)Lnet/minecraft/server/network/ServerPlayNetworkHandler;"
            )
    )
    private ServerPlayNetworkHandler aibot$replaceNetworkHandler(MinecraftServer server,
                                                                  ClientConnection connection,
                                                                  ServerPlayerEntity player,
                                                                  ConnectedClientData clientData) {
        if (player instanceof AIPlayerEntity fakePlayer) {
            return new AINetworkHandler(this.server, connection, fakePlayer, clientData);
        }
        return new ServerPlayNetworkHandler(this.server, connection, player, clientData);
    }
}
