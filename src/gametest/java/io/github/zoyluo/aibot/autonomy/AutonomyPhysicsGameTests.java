package io.github.zoyluo.aibot.autonomy;

import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.task.TaskManager;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Real player displacement and damage through the autonomous runtime and vanilla physics. */
public final class AutonomyPhysicsGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "autonomyNavigationPhysics", tickLimit = 180)
    public void selectedVisibleNavigationReachesDestinationWithoutRelocation(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(2, 3, 2));
        prepareFloor(context, feet);
        AIPlayerEntity bot = spawn(context, "AutoNavPhysGT", feet);
        BlockPos destination = feet.east(3);
        require(context, new AutonomyObservation(bot).visibleCell(destination.down()),
                "the exposed destination support was hidden by a center-only ground ray");
        Vec3d initial = bot.getPos();
        AtomicInteger decisions = new AtomicInteger();
        AtomicBoolean sawSuccessfulNavigation = new AtomicBoolean();
        AutonomyCoordinator.INSTANCE.start(bot, input -> {
            AutonomyDecision decision;
            if (decisions.incrementAndGet() == 1) {
                JsonObject arguments = new JsonObject();
                arguments.addProperty("x", destination.getX());
                arguments.addProperty("y", destination.getY());
                arguments.addProperty("z", destination.getZ());
                decision = new AutonomyDecision(intention(),
                        new AutonomyDecision.Action("navigate", arguments, 100), null, null);
            } else {
                JsonObject state = input.getAsJsonObject("working_state");
                if (state.has("lastResult") && state.get("lastResult").isJsonObject()
                        && state.getAsJsonObject("lastResult").get("ok").getAsBoolean()) {
                    sawSuccessfulNavigation.set(true);
                }
                decision = new AutonomyDecision(intention(), null,
                        new AutonomyDecision.Wait("Observe the completed movement", 300), null);
            }
            return CompletableFuture.completedFuture(new AutonomyProvider.Reply(decision, 0, 0, 0));
        });
        Vec3d[] previous = {initial};
        double[] greatestStep = {0.0};
        for (int tick = 1; tick < 140; tick++) {
            context.runAtTick(tick, () -> {
                greatestStep[0] = Math.max(greatestStep[0], bot.getPos().distanceTo(previous[0]));
                previous[0] = bot.getPos();
            });
        }
        context.runAtTick(140, () -> {
            try {
                require(context, sawSuccessfulNavigation.get() && decisions.get() >= 2,
                        "navigation did not complete and return a success to the next autonomous decision");
                require(context, bot.getPos().distanceTo(Vec3d.ofBottomCenter(destination)) <= 0.6,
                        "navigation did not leave the real player at the selected visible destination");
                require(context, bot.getX() - initial.x > 2.3,
                        "navigation reported success without traversing the intervening cells");
                require(context, greatestStep[0] > 0.03 && greatestStep[0] < 0.8,
                        "navigation did not use bounded vanilla movement increments: " + greatestStep[0]);
                require(context, TaskManager.INSTANCE.getActive(bot).isEmpty(),
                        "navigation invoked a strategic task");
            } finally {
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), "AutoNavPhysGT");
            }
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "autonomyFallPhysics", tickLimit = 140)
    public void deliberateWaitPreservesGravityAndVanillaFallDamage(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(2, 3, 2));
        prepareFloor(context, feet);
        AIPlayerEntity bot = spawn(context, "AutoFallPhysGT", feet);
        AtomicInteger decisions = new AtomicInteger();
        AutonomyCoordinator.INSTANCE.start(bot, input -> {
            decisions.incrementAndGet();
            return CompletableFuture.completedFuture(new AutonomyProvider.Reply(
                    new AutonomyDecision(intention(), null,
                            new AutonomyDecision.Wait("Deliberately remain still", 400), null), 0, 0, 0));
        });
        double liftedY = feet.getY() + 7.0;
        AtomicBoolean observedDescent = new AtomicBoolean();
        float[] lowestHealth = {bot.getHealth()};
        context.runAtTick(65, () -> {
            require(context, AutonomyCoordinator.INSTANCE.status(bot).contains("WAITING"),
                    "fixture did not enter deliberate waiting before the fall");
            require(context, bot.isAlive() && bot.getHealth() == bot.getMaxHealth(),
                    "fixture player was damaged before the fall");
            // Fixture setup only: put the player in unsupported air after vanilla's 60-tick
            // join protection. The autonomous provider never requests movement or relocation.
            bot.refreshPositionAndAngles(feet.getX() + 0.5, liftedY, feet.getZ() + 0.5, 0, 0);
            bot.setVelocity(Vec3d.ZERO);
            bot.setOnGround(false);
            bot.fallDistance = 0;
        });
        context.runAtTick(72, () -> observedDescent.set(bot.getY() < liftedY - 0.5
                && bot.getY() > feet.getY() + 0.5));
        for (int tick = 66; tick < 110; tick++) {
            context.runAtTick(tick, () -> lowestHealth[0] = Math.min(lowestHealth[0], bot.getHealth()));
        }
        context.runAtTick(110, () -> {
            try {
                require(context, observedDescent.get(), "waiting suppressed real gravitational descent");
                require(context, bot.isAlive() && bot.isOnGround()
                                && Math.abs(bot.getY() - feet.getY()) < 0.1,
                        "falling player did not land normally on the fixture floor");
                require(context, lowestHealth[0] <= bot.getMaxHealth() - 2.0F,
                        "waiting or fake-player embodiment suppressed ordinary fall damage");
                require(context, bot.getPos().squaredDistanceTo(Vec3d.ofBottomCenter(feet)) < 0.05,
                        "a movement/recovery policy relocated the waiting player");
                require(context, decisions.get() >= 1 && TaskManager.INSTANCE.getActive(bot).isEmpty(),
                        "fall assigned a strategic recovery task");
            } finally {
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), "AutoFallPhysGT");
            }
            context.complete();
        });
    }

    private static AutonomyDecision.Intention intention() {
        return new AutonomyDecision.Intention("Fixture-selected intention", "Public physics test purpose", "active");
    }

    private static AIPlayerEntity spawn(TestContext context, String name, BlockPos feet) {
        return AIPlayerManager.INSTANCE.spawn(context.getWorld().getServer(), name, context.getWorld(),
                        Vec3d.ofBottomCenter(feet), 0, 0, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
    }

    private static void prepareFloor(TestContext context, BlockPos feet) {
        for (int x = -1; x <= 5; x++) {
            for (int z = -1; z <= 2; z++) {
                for (int y = -1; y <= 9; y++) {
                    context.getWorld().setBlockState(feet.add(x, y, z),
                            (y == -1 ? Blocks.STONE : Blocks.AIR).getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) context.throwGameTestException(message);
    }
}
