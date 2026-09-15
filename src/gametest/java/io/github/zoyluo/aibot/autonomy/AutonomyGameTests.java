package io.github.zoyluo.aibot.autonomy;

import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.mode.OperatingProfile;
import io.github.zoyluo.aibot.pathfinding.AStarPathfinder;
import io.github.zoyluo.aibot.task.TaskManager;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** World-backed boundary tests. No provider, tutorial, or strategic task is involved. */
public final class AutonomyGameTests implements FabricGameTest {
    private static final String BATCH = "autonomySurvivalBoundary";

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = BATCH, tickLimit = 220)
    public void continuousRuntimeWaitsMovesAndReconsidersPhysicalFailure(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(2, 3, 2));
        prepareFloor(context, feet);
        AIPlayerEntity bot = spawn(context, "AutoLoopGT", feet);
        Vec3d start = bot.getPos();
        AtomicInteger decisions = new AtomicInteger();
        AtomicBoolean sawFailure = new AtomicBoolean();
        AutonomyProvider provider = input -> {
            int decision = decisions.incrementAndGet();
            var intention = new AutonomyDecision.Intention("Test chosen intention", "Public test purpose", "active");
            AutonomyDecision next;
            if (decision == 2) {
                JsonObject arguments = new JsonObject();
                arguments.addProperty("forward", 1);
                arguments.addProperty("strafe", 0);
                next = new AutonomyDecision(intention,
                        new AutonomyDecision.Action("move", arguments, 8), null, null);
            } else if (decision == 3) {
                JsonObject arguments = new JsonObject();
                arguments.addProperty("x", feet.getX() + 30);
                arguments.addProperty("y", feet.getY());
                arguments.addProperty("z", feet.getZ());
                next = new AutonomyDecision(intention,
                        new AutonomyDecision.Action("mine", arguments, 60), null, null);
            } else {
                if (decision == 4) {
                    var state = input.getAsJsonObject("working_state");
                    sawFailure.set(state.has("lastResult")
                            && !state.getAsJsonObject("lastResult").get("ok").getAsBoolean());
                }
                next = new AutonomyDecision(intention, null,
                        new AutonomyDecision.Wait("Deliberate observation interval", decision == 1 ? 20 : 200), null);
            }
            return CompletableFuture.completedFuture(new AutonomyProvider.Reply(next, 0, 0, 0));
        };
        AutonomyCoordinator.INSTANCE.start(bot, provider);
        context.runAtTick(10, () -> {
            try {
                require(context, decisions.get() == 1, "purposeful wait triggered extra decisions");
                require(context, bot.getPos().squaredDistanceTo(start) < 0.01,
                        "purposeful wait forced physical motion");
                require(context, AutonomyCoordinator.INSTANCE.status(bot).contains("WAITING"),
                        "bounded deliberate wait was not represented explicitly");
            } catch (RuntimeException failure) {
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), "AutoLoopGT");
                throw failure;
            }
        });
        context.runAtTick(175, () -> {
            try {
                require(context, bot.getZ() > start.z + 0.1, "selected movement never moved the real player");
                require(context, bot.getPos().squaredDistanceTo(start) < 16,
                        "bounded local motion moved farther than its ordinary eight-tick input permits");
                require(context, decisions.get() >= 4, "runtime stopped deciding after a physical failure");
                require(context, sawFailure.get(), "next provider request did not receive the failed action result");
                require(context, TaskManager.INSTANCE.getActive(bot).isEmpty(),
                        "autonomy invoked a strategic fallback task");
                AutonomyCoordinator.INSTANCE.pause(bot);
                require(context, AutonomyCoordinator.INSTANCE.status(bot).contains("PAUSED"),
                        "pause did not quiesce autonomy");
            } finally {
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), "AutoLoopGT");
            }
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = BATCH, tickLimit = 100)
    public void vanillaDeathDropsInventoryAndRespawnReplacesManagedSubtype(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(2, 3, 2));
        prepareFloor(context, feet);
        AIPlayerEntity bot = spawn(context, "AutoDeathGT", feet);
        // Vanilla protects a newly joined player from ordinary damage for 60 ticks.
        context.runAtTick(65, () -> {
            var world = context.getWorld();
            boolean keepInventory = world.getGameRules().getBoolean(GameRules.KEEP_INVENTORY);
            world.getGameRules().get(GameRules.KEEP_INVENTORY).set(false, world.getServer());
            try {
                BlockPos respawnFeet = feet.east(6);
                // An explicit test spawn point makes assertions independent of the server seed.
                bot.setSpawnPoint(world.getRegistryKey(), respawnFeet, 0.0F, true, false);
                bot.getInventory().main.set(0, new ItemStack(Items.DIAMOND, 7));
                bot.getInventory().markDirty();
                var uuid = bot.getUuid();
                var deathBox = bot.getBoundingBox().expand(3);
                bot.damage(world, world.getDamageSources().generic(), 1000.0F);
                require(context, !bot.isAlive(), "ordinary lethal damage did not kill the player");
                int dropped = world.getEntitiesByClass(ItemEntity.class, deathBox,
                                entity -> entity.getStack().isOf(Items.DIAMOND))
                        .stream().mapToInt(entity -> entity.getStack().getCount()).sum();
                require(context, dropped == 7, "vanilla death did not drop the exact carried inventory");

                AIPlayerEntity respawned = AIPlayerManager.INSTANCE.respawnVanilla(bot);
                require(context, respawned != bot, "respawn revived the old object instead of replacing it");
                require(context, respawned.isAlive(), "replacement player is not alive");
                require(context, uuid.equals(respawned.getUuid()), "respawn changed player identity");
                require(context, AIPlayerManager.INSTANCE.getByUuid(uuid).orElse(null) == respawned,
                        "manager retained the dead player object");
                require(context, respawned.networkHandler.player == respawned,
                        "network handler retained the dead player object");
                require(context, respawned.getBlockPos().isWithinDistance(respawnFeet, 3),
                        "vanilla respawn ignored the configured fixture spawn point");
                require(context, !respawned.getBlockPos().isWithinDistance(feet, 3),
                        "respawn returned to the death drops");
                require(context, !respawned.getInventory().contains(new ItemStack(Items.DIAMOND)),
                        "respawn magically recovered the dropped inventory");
                require(context, TaskManager.INSTANCE.getActive(respawned).isEmpty(),
                        "respawn automatically assigned a recovery task");
                require(context, respawned.getActionPack().player() == respawned,
                        "replacement motor controller belongs to the dead player");
            } finally {
                world.getGameRules().get(GameRules.KEEP_INVENTORY).set(keepInventory, world.getServer());
                AIPlayerManager.INSTANCE.despawn(world.getServer(), "AutoDeathGT");
            }
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = BATCH, tickLimit = 160)
    public void autonomousDeathResumesDecisionsAndRetainsDeathEpisodes(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(2, 3, 2));
        prepareFloor(context, feet);
        AIPlayerEntity original = spawn(context, "AutoResumeGT", feet);
        original.setSpawnPoint(context.getWorld().getRegistryKey(), feet.east(6), 0.0F, true, false);
        AtomicInteger decisions = new AtomicInteger();
        AtomicBoolean rememberedDeath = new AtomicBoolean();
        AutonomyCoordinator.INSTANCE.start(original, input -> {
            decisions.incrementAndGet();
            String episodes = input.getAsJsonObject("working_state").getAsJsonArray("episodes").toString();
            if (episodes.contains("\"death\"") && episodes.contains("\"respawned\"")) {
                rememberedDeath.set(true);
            }
            var decision = new AutonomyDecision(
                    new AutonomyDecision.Intention("Observe this interval", "Public test purpose", "active"),
                    null, new AutonomyDecision.Wait("Observe", 200), null);
            return CompletableFuture.completedFuture(new AutonomyProvider.Reply(decision, 0, 0, 0));
        });
        context.runAtTick(65, () -> original.damage(context.getWorld(),
                context.getWorld().getDamageSources().generic(), 1000.0F));
        context.runAtTick(120, () -> {
            try {
                AIPlayerEntity current = AIPlayerManager.INSTANCE.getByUuid(original.getUuid()).orElseThrow();
                require(context, current != original && current.isAlive(),
                        "autonomy failed to replace the dead player through vanilla respawn");
                require(context, current.getBlockPos().isWithinDistance(feet.east(6), 3),
                        "autonomy respawn did not honor the ordinary spawn point");
                require(context, decisions.get() >= 2 && rememberedDeath.get(),
                        "autonomy did not resume with recorded death and respawn episodes");
                require(context, TaskManager.INSTANCE.getActive(current).isEmpty(),
                        "death assigned a recovery task");
                require(context, AutonomyCoordinator.INSTANCE.owns(current),
                        "replacement lost autonomous ownership");
            } finally {
                AIPlayerManager.INSTANCE.despawn(context.getWorld().getServer(), "AutoResumeGT");
            }
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = BATCH, tickLimit = 40)
    public void observationsCannotExposeSealedOreOrInvisibleEntity(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(2, 3, 2));
        prepareFloor(context, feet);
        AIPlayerEntity bot = spawn(context, "AutoSightGT", feet);
        try {
            BlockPos ore = feet.east(3);
            for (Direction side : Direction.values()) {
                context.getWorld().setBlockState(ore.offset(side), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            }
            context.getWorld().setBlockState(ore, Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
            AutonomyObservation observation = new AutonomyObservation(bot);
            require(context, !observation.knownOrVisible(ore), "sealed target was accepted as visible or known");
            String snapshot = observation.observe().toString();
            require(context, !snapshot.contains("diamond_ore"), "hidden ore entered the model observation");
            require(context, !snapshot.contains("nearest_ore") && !snapshot.contains("hostile"),
                    "legacy resource or danger classifications entered the observation");
            AutonomyEmbodiment embodiment = new AutonomyEmbodiment(bot);
            JsonObject target = new JsonObject();
            target.addProperty("x", ore.getX());
            target.addProperty("y", ore.getY());
            target.addProperty("z", ore.getZ());
            Vec3d originalPosition = bot.getPos();
            for (String action : new String[]{"mine", "navigate"}) {
                var result = embodiment.start(new AutonomyDecision.Action(action, target, 100));
                require(context, result != null && !result.ok(), "hidden " + action + " was accepted");
                require(context, context.getWorld().getBlockState(ore).isOf(Blocks.DIAMOND_ORE),
                        "hidden " + action + " mutated the ore");
                require(context, bot.getPos().equals(originalPosition), "hidden action relocated the player");
            }

            var entity = EntityType.ZOMBIE.create(context.getWorld(), SpawnReason.COMMAND);
            require(context, entity != null, "could not create invisible test entity");
            entity.refreshPositionAndAngles(Vec3d.ofBottomCenter(feet.south(2)), 0.0F, 0.0F);
            entity.setInvisible(true);
            context.getWorld().spawnEntity(entity);
            try {
                require(context, !observation.visibleEntity(entity), "invisible entity passed perception filter");
                String entities = observation.observe().getAsJsonArray("visible_entities").toString();
                require(context, !entities.contains("minecraft:zombie"), "invisible entity reached the model");
            } finally {
                entity.discard();
            }
        } finally {
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), "AutoSightGT");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = BATCH, tickLimit = 40)
    public void craftCannotAcquireOrRecursivelyPrepareMissingIngredients(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(2, 3, 2));
        prepareFloor(context, feet);
        AIPlayerEntity bot = spawn(context, "AutoCraftGT", feet);
        try {
            bot.getInventory().main.set(0, new ItemStack(Items.OAK_LOG, 2));
            bot.getInventory().markDirty();
            AutonomyEmbodiment embodiment = new AutonomyEmbodiment(bot);
            JsonObject arguments = new JsonObject();
            arguments.addProperty("recipe", "minecraft:crafting_table");
            var result = embodiment.start(new AutonomyDecision.Action("craft", arguments, 100));
            require(context, result != null && !result.ok(), "craft solved missing planks recursively");
            require(context, bot.getInventory().getStack(0).isOf(Items.OAK_LOG)
                            && bot.getInventory().getStack(0).getCount() == 2,
                    "failed craft consumed or transformed prerequisite resources");
            require(context, !bot.getInventory().contains(new ItemStack(Items.OAK_PLANKS))
                            && !bot.getInventory().contains(new ItemStack(Items.CRAFTING_TABLE)),
                    "failed craft acquired intermediate or target items");
            require(context, TaskManager.INSTANCE.getActive(bot).isEmpty(),
                    "failed craft assigned an acquisition task");
            arguments.addProperty("recipe", "minecraft:oak_planks");
            var available = embodiment.start(new AutonomyDecision.Action("craft", arguments, 100));
            require(context, available != null && available.ok(),
                    "available one-step vanilla recipe failed: " + (available == null ? "pending" : available.code()));
            require(context, InventoryAction.countItem(bot, Items.OAK_LOG) == 1
                            && InventoryAction.countItem(bot, Items.OAK_PLANKS) == 4,
                    "available craft did not consume exactly one input and produce its recipe output");
        } finally {
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), "AutoCraftGT");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = BATCH, tickLimit = 40)
    public void selectedBlockMiningRetainsTheModelChosenHeldItem(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(2, 3, 2));
        prepareFloor(context, feet);
        AIPlayerEntity bot = spawn(context, "AutoHeldGT", feet);
        AutonomyEmbodiment embodiment = new AutonomyEmbodiment(bot);
        try {
            BlockPos target = feet.east(2);
            context.getWorld().setBlockState(target, Blocks.DIRT.getDefaultState(), Block.NOTIFY_ALL);
            bot.getInventory().selectedSlot = 0;
            bot.getInventory().main.set(0, new ItemStack(Items.STICK));
            bot.getInventory().main.set(1, new ItemStack(Items.DIAMOND_SHOVEL));
            JsonObject arguments = new JsonObject();
            arguments.addProperty("x", target.getX());
            arguments.addProperty("y", target.getY());
            arguments.addProperty("z", target.getZ());
            require(context, embodiment.start(new AutonomyDecision.Action("mine", arguments, 100)) == null,
                    "reachable selected block did not start mining");
            embodiment.poll();
            require(context, bot.getMainHandStack().isOf(Items.STICK)
                            && bot.getInventory().selectedSlot == 0,
                    "mining silently selected a ranked tool instead of the model's held item");
        } finally {
            embodiment.cancel();
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), "AutoHeldGT");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = BATCH, tickLimit = 40)
    public void navigationCannotReuseAnUnrestrictedRouteAcrossUnknownCells(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(2, 3, 2));
        prepareFloor(context, feet);
        BlockPos goal = feet.east(3);
        var unrestricted = new AStarPathfinder(context.getWorld(), feet, goal, 200, 100,
                false, false).findPath();
        require(context, unrestricted.success(), "fixture did not create an ordinary open route");
        var restricted = new AStarPathfinder(context.getWorld(), feet, goal, 200, 100,
                pos -> Math.abs(pos.getX() - feet.getX()) <= 4
                        && Math.abs(pos.getZ() - feet.getZ()) <= 2
                        && Math.abs(pos.getY() - feet.getY()) <= 2
                        && pos.getX() != feet.getX() + 1).findPath();
        require(context, !restricted.success(),
                "visibility-scoped search crossed unknown terrain or reused an unrestricted cache entry");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = BATCH, tickLimit = 40)
    public void blockedPlacementFailsWithoutConsumingOrMutating(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(2, 3, 2));
        prepareFloor(context, feet);
        AIPlayerEntity bot = spawn(context, "AutoPlaceGT", feet);
        try {
            require(context, AIBotConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                    "test requires strict_survival");
            BlockPos support = feet.east(3);
            context.getWorld().setBlockState(support, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            for (int z = -2; z <= 2; z++) {
                for (int y = 0; y <= 3; y++) {
                    context.getWorld().setBlockState(feet.add(1, y, z), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
            bot.getInventory().selectedSlot = 0;
            bot.getInventory().main.set(0, new ItemStack(Items.COBBLESTONE, 2));
            BlockPos target = support.up();
            var before = context.getWorld().getBlockState(target);
            var result = BuildAction.placeBlock(bot, support, Direction.UP, Hand.MAIN_HAND);
            require(context, result.isFailed(), "occluded placement did not return a failure");
            require(context, context.getWorld().getBlockState(target).equals(before),
                    "occluded placement changed the world");
            require(context, bot.getMainHandStack().getCount() == 2,
                    "occluded placement consumed inventory");
        } finally {
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), "AutoPlaceGT");
        }
        context.complete();
    }

    private static AIPlayerEntity spawn(TestContext context, String name, BlockPos feet) {
        return AIPlayerManager.INSTANCE.spawn(context.getWorld().getServer(), name,
                        context.getWorld(), Vec3d.ofBottomCenter(feet), 0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
    }

    private static void prepareFloor(TestContext context, BlockPos feet) {
        for (int x = -1; x <= 8; x++) {
            for (int z = -2; z <= 3; z++) {
                for (int y = -1; y <= 4; y++) {
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
