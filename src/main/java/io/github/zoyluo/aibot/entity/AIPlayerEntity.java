package io.github.zoyluo.aibot.entity;

import com.mojang.authlib.GameProfile;
import io.github.zoyluo.aibot.action.ActionPack;
import io.github.zoyluo.aibot.log.BotLog;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

public class AIPlayerEntity extends ServerPlayerEntity {
    private final ActionPack actionPack = new ActionPack(this);

    public AIPlayerEntity(MinecraftServer server,
                          ServerWorld world,
                          GameProfile profile,
                          SyncedClientOptions clientOptions) {
        super(server, world, profile, clientOptions);
    }

    @Override
    public void tick() {
        if (this.server.getTicks() % 10 == 0 && this.networkHandler != null) {
            this.networkHandler.syncWithPlayerPosition();
            this.getServerWorld().getChunkManager().updatePosition(this);
        }

        try {
            super.tick();
            net.minecraft.util.math.Vec3d beforePhysics = this.getPos();
            this.playerTick();
            if (io.github.zoyluo.aibot.autonomy.AutonomyCoordinator.INSTANCE.owns(this)) {
                net.minecraft.util.math.Vec3d movement = this.getPos().subtract(beforePhysics);
                // Normally charged by the absent client's movement-packet handler.
                this.increaseTravelMotionStats(movement.x, movement.y, movement.z);
            }
            this.actionPack.onUpdate();
        } catch (NullPointerException exception) {
            BotLog.error(this, "tick_npe_swallowed", exception);
        }
    }

    @Override
    public String getIp() {
        return "127.0.0.1";
    }

    public void reviveForAIBotSpawn() {
        this.unsetRemoved();
    }

    public ActionPack getActionPack() {
        return actionPack;
    }

    /**
     * A real client normally owns player physics. Autonomous fake players have no client:
     * let the existing playerTick -> tickMovement -> travel call perform vanilla collisions,
     * gravity, jumping and swimming exactly once, including purposeful waits and provider outages.
     */
    @Override
    public boolean isLogicalSideForUpdatingMovement() {
        return io.github.zoyluo.aibot.autonomy.AutonomyCoordinator.INSTANCE.owns(this)
                || super.isLogicalSideForUpdatingMovement();
    }

    @Override
    protected void fall(double heightDifference, boolean onGround,
                        net.minecraft.block.BlockState state, net.minecraft.util.math.BlockPos landedPosition) {
        if (io.github.zoyluo.aibot.autonomy.AutonomyCoordinator.INSTANCE.owns(this)) {
            // ServerPlayerEntity.fall is otherwise a no-op: real clients invoke handleFall
            // through movement packets. That method delegates to ordinary PlayerEntity.fall.
            handleFall(0.0, heightDifference, 0.0, onGround);
        } else {
            super.fall(heightDifference, onGround, state, landedPosition);
        }
    }
}
