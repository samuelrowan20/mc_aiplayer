package io.github.zoyluo.aibot.autonomy;

import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.persist.BotPersistence;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

/** Reload must not turn a saved physical predicament into a safe-position search. */
public final class AutonomyRestoreGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "autonomySurvivalBoundary", tickLimit = 40)
    public void pausedReloadPreservesAirborneAndWaterPositionsAndWorkingMemory(TestContext context) {
        var world = context.getWorld();
        var server = world.getServer();
        BlockPos feet = context.getAbsolutePos(new BlockPos(2, 3, 2));
        for (int x = -1; x <= 4; x++) {
            for (int z = -1; z <= 4; z++) {
                for (int y = -1; y <= 5; y++) {
                    world.setBlockState(feet.add(x, y, z),
                            (y == -1 ? Blocks.STONE : Blocks.AIR).getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        for (boolean water : new boolean[]{false, true}) {
            String name = water ? "AutoWaterSaveGT" : "AutoAirSaveGT";
            try {
                var bot = AIPlayerManager.INSTANCE.spawn(server, name, world,
                        Vec3d.ofBottomCenter(feet), 0, 0, GameMode.SURVIVAL).orElseThrow();
                Vec3d exact = Vec3d.ofBottomCenter(feet.up(3)).add(0.125, 0.375, 0.25);
                if (water) world.setBlockState(BlockPos.ofFloored(exact), Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
                bot.teleport(world, exact.x, exact.y, exact.z, Collections.emptySet(), 35, 12, true);
                bot.setHealth(12.5F);
                bot.getHungerManager().setFoodLevel(9);
                bot.getInventory().main.set(0, new ItemStack(Items.STICK, 3));
                AutonomyCoordinator.INSTANCE.start(bot, observation -> CompletableFuture.completedFuture(
                        new AutonomyProvider.Reply(new AutonomyDecision(
                                new AutonomyDecision.Intention("Retained model intention", "Public purpose", "active"),
                                null, new AutonomyDecision.Wait("Model-selected wait", 100), "Retained model memory"), 0, 0, 0)));
                AutonomyCoordinator.INSTANCE.tick(bot);
                AutonomyCoordinator.INSTANCE.tick(bot);
                AutonomyCoordinator.INSTANCE.pause(bot);
                var state = AutonomyCoordinator.INSTANCE.snapshot(bot);
                var record = BotPersistence.capture(bot);
                AIPlayerManager.INSTANCE.despawn(server, name);

                var restored = AIPlayerManager.INSTANCE.respawnFromRecord(server, record, true).orElseThrow();
                AutonomyCoordinator.INSTANCE.restore(restored, state);
                var after = AutonomyCoordinator.INSTANCE.snapshot(restored);
                if (!restored.getPos().equals(exact)) context.throwGameTestException("reload searched for a safer position: " + restored.getPos());
                if (restored.getHealth() != 12.5F || restored.getHungerManager().getFoodLevel() != 9)
                    context.throwGameTestException("reload changed saved health or hunger");
                if (!restored.getInventory().getStack(0).isOf(Items.STICK) || restored.getInventory().getStack(0).getCount() != 3)
                    context.throwGameTestException("reload changed saved inventory");
                if (!"PAUSED".equals(after.get("phase").getAsString()))
                    context.throwGameTestException("reload resumed a paused autonomous player");
                if (!after.get("intention").equals(state.get("intention"))
                        || !after.get("memorySummary").equals(state.get("memorySummary"))
                        || !after.get("embodiment").equals(state.get("embodiment")))
                    context.throwGameTestException("reload lost model intention, summary, or remembered places");
            } finally {
                AIPlayerManager.INSTANCE.despawn(server, name);
            }
        }
        context.complete();
    }
}
