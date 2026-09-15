package io.github.zoyluo.aibot.autonomy;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.*;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.mode.OperatingProfile;
import io.github.zoyluo.aibot.pathfinding.AStarPathfinder;
import io.github.zoyluo.aibot.pathfinding.Node;
import net.minecraft.entity.Entity;
import net.minecraft.item.BlockItem;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Server-tick-only allowlist. No typed goals, tasks, prerequisite planners or privileged tools. */
public final class AutonomyEmbodiment implements AutonomyEngine.Ports {
    private final Supplier<AIPlayerEntity> players;
    private AIPlayerEntity bot;
    private AutonomyObservation observation;
    private AutonomyDecision.Action active;
    private MiningController mining;
    private BlockPos miningTarget;
    private List<Node> path = List.of();
    private int pathIndex;
    private WalkToController walker;
    private int elapsed;
    private BlockPos openBlock;
    private String actionDimension;
    private Vec3d previousMovePosition;
    private double movementDistance;

    public AutonomyEmbodiment(Supplier<AIPlayerEntity> players) { this.players = players; }
    public AutonomyEmbodiment(AIPlayerEntity player) { this(() -> player); }

    public JsonObject snapshot() { bind(); return observation.snapshot(); }
    public void restore(JsonObject data) { bind(); observation.restore(data); }

    public static JsonArray capabilities() {
        JsonArray tools = new JsonArray();
        String coordinates = "\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"}";
        String blockCoordinates = coordinates.replace("number", "integer");
        String face = ",\"face\":{\"type\":\"string\",\"enum\":[\"up\",\"down\",\"north\",\"south\",\"east\",\"west\"]}";
        capability(tools, "inspect", "Refresh observations, which are already refreshed before every decision. No movement or expanded view.", "", "");
        capability(tools, "look", "Turn facing toward absolute world coordinates within 32 blocks. No movement; all-direction observation sampling is unchanged.", coordinates, "x,y,z");
        capability(tools, "navigate", "Navigate to a visible/remembered cell within 32 blocks. Only observed/known path cells; no digging, placing or teleporting.", blockCoordinates, "x,y,z");
        capability(tools, "move", "Local movement relative to facing, at most 100 ticks. Requires nonzero forward/strafe or jump=true; use deliberateWait to stay still. Jump also presses swim-up in fluid.",
                "\"forward\":{\"type\":\"number\",\"minimum\":-1,\"maximum\":1},\"strafe\":{\"type\":\"number\",\"minimum\":-1,\"maximum\":1},\"sprint\":{\"type\":\"boolean\"},\"jump\":{\"type\":\"boolean\"}", "forward,strafe");
        capability(tools, "mine", "Mine exactly one visible reachable block with the current held item.", blockCoordinates, "x,y,z");
        capability(tools, "place", "Place the current held block against the selected support face.", blockCoordinates + face, "x,y,z,face");
        capability(tools, "interact_block", "Use current held item on selected reachable face, opening normal screens when applicable.", blockCoordinates + face, "x,y,z,face");
        capability(tools, "attack", "Attack one visible entity in ordinary reach once.", "\"entity_id\":{\"type\":\"integer\"}", "entity_id");
        capability(tools, "interact_entity", "Use current held item on one visible entity in reach once.", "\"entity_id\":{\"type\":\"integer\"}", "entity_id");
        capability(tools, "equip", "Equip the selected main inventory slot into the main hand.", "\"slot\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":35}", "slot");
        capability(tools, "swap", "Swap selected main inventory slot with hotbar slot; requires player screen.", "\"slot\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":35},\"hotbar\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":8}", "slot,hotbar");
        capability(tools, "drop", "Drop the requested count from selected main inventory slot normally.", "\"slot\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":35},\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":64}", "slot,count");
        capability(tools, "use_item", "Use the current held item until it finishes or maxTicks expires.", "", "");
        capability(tools, "craft", "Perform exactly one namespaced vanilla recipe using present ingredients in the current player/table crafting screen. Requires empty cursor/grid; reports unmet requirements without acquiring anything.", "\"recipe\":{\"type\":\"string\"}", "recipe");
        capability(tools, "click_slot", "Click the current observed screen. Screen ids differ from inventory ids. Normal furnace input/fuel/output and equipment handling. pickup button 0/1, quick_move 0, swap hotbar 0..8, throw 0 one/1 stack.", "\"sync_id\":{\"type\":\"integer\"},\"slot\":{\"type\":\"integer\",\"minimum\":0},\"button\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":8},\"mode\":{\"type\":\"string\",\"enum\":[\"pickup\",\"quick_move\",\"swap\",\"throw\"]}", "sync_id,slot,button,mode");
        capability(tools, "close_container", "Close current screen through normal player behavior.", "", "");
        return tools;
    }

    private static void capability(JsonArray tools, String name, String description, String properties, String requiredNames) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        parameters.addProperty("additionalProperties", false);
        parameters.add("properties", JsonParser.parseString("{" + properties + "}"));
        JsonArray required = new JsonArray();
        if (!requiredNames.isEmpty()) for (String field : requiredNames.split(",")) required.add(field);
        parameters.add("required", required);
        tool.add("parameters", parameters);
        tools.add(tool);
    }

    @Override public JsonObject observe() {
        bind();
        closeInvalidScreen();
        return observation.observe();
    }

    @Override public AutonomyState.Result start(AutonomyDecision.Action action) {
        bind();
        cancel();
        if (AIBotConfig.get().profile() != OperatingProfile.STRICT_SURVIVAL
                || bot.isCreative() || bot.isSpectator()) {
            return fail("strict_survival_required");
        }
        if (!bot.isAlive()) return fail("player_dead");
        active = action;
        actionDimension = bot.getServerWorld().getRegistryKey().getValue().toString();
        elapsed = 0;
        JsonObject args = action.arguments();
        try {
            JsonObject schema = null;
            for (var definition : capabilities()) {
                if (definition.getAsJsonObject().get("name").getAsString().equals(action.name())) {
                    schema = definition.getAsJsonObject().getAsJsonObject("parameters");
                    break;
                }
            }
            if (schema == null) { cancelAction(); return fail("unsupported_action"); }
            for (String key : args.keySet()) {
                if (!schema.getAsJsonObject("properties").has(key)) {
                    cancelAction(); return fail("unexpected_argument");
                }
            }
            AutonomyState.Result result = switch (action.name()) {
                case "inspect" -> AutonomyState.Result.success("Observation refreshed at next decision.");
                case "look" -> {
                    Vec3d target = coordinates(args);
                    if (target.squaredDistanceTo(bot.getEyePos()) > 32 * 32) yield fail("target_too_far");
                    float yaw = bot.getYaw(), pitch = bot.getPitch();
                    ActionResult lookResult = LookAction.lookAt(bot, target);
                    if (lookResult.isSuccess() && Math.abs(MathHelper.wrapDegrees(bot.getYaw() - yaw)) < 0.1
                            && Math.abs(bot.getPitch() - pitch) < 0.1) {
                        yield AutonomyState.Result.failure("already_facing_target",
                                "Already facing this target; no turn or movement occurred. Observations already refresh automatically.");
                    }
                    yield convert(lookResult);
                }
                case "navigate" -> navigate(block(args));
                case "move" -> {
                    float forward = (float) number(args, "forward", -1, 1);
                    float strafe = (float) number(args, "strafe", -1, 1);
                    boolean jump = flag(args, "jump");
                    if (forward == 0 && strafe == 0 && !jump) {
                        yield AutonomyState.Result.failure("zero_movement_input",
                                "Both movement inputs are zero and jump is false. No movement requested; use deliberateWait for a purposeful pause.");
                    }
                    previousMovePosition = bot.getPos();
                    movementDistance = 0;
                    bot.getActionPack().setForward(forward);
                    bot.getActionPack().setStrafing(strafe);
                    bot.getActionPack().setSprinting(flag(args, "sprint"));
                    bot.getActionPack().setJumping(jump);
                    yield null;
                }
                case "mine" -> {
                    BlockPos pos = block(args);
                    BlockHitResult hit = reachableHit(pos, null);
                    if (hit == null) yield fail("block_not_visible_or_reachable");
                    if (bot.getServerWorld().getBlockState(pos).isAir()) yield fail("target_is_air");
                    miningTarget = pos;
                    mining = new MiningController(pos, hit.getSide(), false);
                    yield null;
                }
                case "place" -> {
                    if (!(bot.getMainHandStack().getItem() instanceof BlockItem)) yield fail("held_item_not_block");
                    yield convert(BuildAction.placeBlock(bot, block(args), face(args), Hand.MAIN_HAND));
                }
                case "interact_block" -> interactBlock(block(args), face(args));
                case "attack", "interact_entity" -> interactEntity(args, action.name().equals("attack"));
                case "equip" -> InventoryAction.equipFromSlot(bot, integer(args, "slot", 0, 35)) >= 0
                        ? AutonomyState.Result.success("Selected inventory slot equipped.") : fail("empty_slot");
                case "swap" -> swap(args);
                case "drop" -> convert(InventoryAction.dropSlotEntity(bot, integer(args, "slot", 0, 35),
                        integer(args, "count", 1, 64)).isPresent() ? ActionResult.SUCCESS : ActionResult.failed("drop_failed"));
                case "use_item" -> {
                    ActionResult used = InteractAction.useItemInAir(bot, Hand.MAIN_HAND);
                    yield used.isSuccess() && bot.isUsingItem() ? null : convert(used);
                }
                case "craft" -> {
                    closeInvalidScreen();
                    yield MechanicalCrafting.craft(bot, args.get("recipe").getAsString());
                }
                case "click_slot" -> clickSlot(args);
                case "close_container" -> {
                    bot.closeHandledScreen();
                    openBlock = null;
                    yield AutonomyState.Result.success("Screen closed.");
                }
                default -> fail("unsupported_action");
            };
            if (result != null) cancelAction();
            return result;
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException e) {
            cancelAction();
            return AutonomyState.Result.failure("invalid_arguments", "Invalid or missing action arguments.");
        }
    }

    @Override public AutonomyState.Result poll() {
        bind();
        if (active == null) return fail("no_active_action");
        if (!bot.isAlive()) { cancelAction(); return fail("player_dead"); }
        if (AIBotConfig.get().profile() != OperatingProfile.STRICT_SURVIVAL || bot.isCreative() || bot.isSpectator()) {
            cancelAction(); return fail("strict_survival_required");
        }
        if (!bot.getServerWorld().getRegistryKey().getValue().toString().equals(actionDimension)) {
            cancelAction(); return fail("dimension_changed");
        }
        elapsed++;
        if (active.name().equals("move")) {
            movementDistance += bot.getPos().distanceTo(previousMovePosition);
            previousMovePosition = bot.getPos();
        }
        AutonomyState.Result result = null;
        if (mining != null) {
            if (reachableHit(miningTarget, null) == null
                    && !bot.getServerWorld().getBlockState(miningTarget).isAir()) result = fail("block_no_longer_reachable");
            else {
                ActionResult mined = mining.tick(bot.getActionPack());
                if (!mined.isInProgress()) result = mined.isSuccess()
                        && !bot.getServerWorld().getBlockState(miningTarget).isAir()
                        ? fail("block_break_rejected") : convert(mined);
            }
        } else if (active.name().equals("navigate")) {
            result = tickPath();
        } else if (active.name().equals("use_item") && !bot.isUsingItem()) {
            result = AutonomyState.Result.success("Held item use ended.");
        } else if (active.name().equals("move") && elapsed >= Math.min(100, active.maxTicks())) {
            result = movementDistance < 0.05
                    ? AutonomyState.Result.failure("movement_blocked", "Movement inputs produced no measurable displacement.")
                    : AutonomyState.Result.success(String.format(Locale.ROOT,
                            "Bounded movement ended; traveled %.2f blocks.", movementDistance));
        }
        if (result == null && elapsed >= active.maxTicks()) {
            result = active.name().equals("use_item") ? AutonomyState.Result.success("Held item released at duration limit.")
                    : fail("action_timeout");
        }
        if (result != null) cancelAction();
        return result;
    }

    @Override public void cancel() {
        if (bot != null) cancelAction();
    }

    private void cancelAction() {
        if (mining != null) mining.abort(bot);
        mining = null;
        miningTarget = null;
        active = null;
        path = List.of();
        walker = null;
        bot.getActionPack().stopAll();
    }

    private void bind() {
        AIPlayerEntity current = players.get();
        if (current == bot) return;
        JsonObject previousPlaces = observation == null ? null : observation.snapshot();
        cancel();
        bot = java.util.Objects.requireNonNull(current);
        observation = new AutonomyObservation(bot);
        observation.restore(previousPlaces);
        openBlock = null;
    }

    private AutonomyState.Result navigate(BlockPos destination) {
        if (destination.getSquaredDistance(bot.getBlockPos()) > 32 * 32
                || !observation.knownOrVisible(destination)) return fail("destination_not_visible_or_remembered");
        var route = new AStarPathfinder(bot.getServerWorld(), bot.getBlockPos(), destination,
                2_000, 20L, observation::knownOrVisible).findPath();
        if (!route.success()) return fail("path_unavailable");
        // Do not expose or traverse an inferred route through unknown space.
        if (!destination.equals(route.resolvedGoal()) || route.path().size() > 64
                || route.path().stream().anyMatch(node -> !observation.knownOrVisible(node.pos()))) {
            return fail("route_not_observed");
        }
        path = route.path();
        pathIndex = path.size() > 1 ? 1 : 0;
        return null;
    }

    private AutonomyState.Result tickPath() {
        if (pathIndex >= path.size()) return AutonomyState.Result.success("Reached selected destination.");
        BlockPos target = path.get(pathIndex).pos();
        if (!observation.knownOrVisible(target)) return fail("route_no_longer_visible_or_known");
        Vec3d point = Vec3d.ofBottomCenter(target);
        if (bot.getPos().squaredDistanceTo(point) <= 0.5 * 0.5) {
            pathIndex++;
            walker = null;
            return pathIndex >= path.size() ? AutonomyState.Result.success("Reached selected destination.") : null;
        }
        if (walker == null) walker = new WalkToController(point, 0.25);
        ActionResult step = walker.tick(bot.getActionPack());
        if (step.isFailed()) return fail("navigation_" + step.reason());
        // WalkTo's arrival check is horizontal; never call that a vertical arrival.
        if (step.isSuccess() && Math.abs(bot.getY() - point.y) > 0.5) return fail("vertical_step_unreachable");
        return null;
    }

    private AutonomyState.Result interactBlock(BlockPos target, Direction face) {
        BlockHitResult hit = reachableHit(target, face);
        if (hit == null) return fail("block_face_not_visible_or_reachable");
        LookAction.lookAt(bot, hit.getPos());
        int oldScreen = bot.currentScreenHandler.syncId;
        var result = bot.interactionManager.interactBlock(bot, bot.getServerWorld(),
                bot.getMainHandStack(), Hand.MAIN_HAND, hit);
        if (bot.currentScreenHandler.syncId != oldScreen) openBlock = target;
        return result.isAccepted() ? AutonomyState.Result.success("Block interaction accepted.") : fail("interaction_rejected");
    }

    private AutonomyState.Result interactEntity(JsonObject args, boolean attack) {
        Entity target = bot.getServerWorld().getEntityById(integer(args, "entity_id", 0, Integer.MAX_VALUE));
        if (target == null || !observation.visibleEntity(target)
                || !bot.canInteractWithEntity(target, 0.0)) return fail("entity_not_visible_or_reachable");
        return convert(attack ? InteractAction.attackEntity(bot, target)
                : InteractAction.useItemOnEntity(bot, target, Hand.MAIN_HAND));
    }

    private AutonomyState.Result swap(JsonObject args) {
        if (bot.currentScreenHandler != bot.playerScreenHandler) return fail("close_container_before_inventory_swap");
        int slot = integer(args, "slot", 0, 35);
        int hotbar = integer(args, "hotbar", 0, 8);
        bot.playerScreenHandler.onSlotClick(slot < 9 ? slot + 36 : slot, hotbar, SlotActionType.SWAP, bot);
        return AutonomyState.Result.success("Inventory swap processed.");
    }

    private AutonomyState.Result clickSlot(JsonObject args) {
        closeInvalidScreen();
        if (integer(args, "sync_id", 0, Integer.MAX_VALUE) != bot.currentScreenHandler.syncId) return fail("screen_changed");
        int slot = integer(args, "slot", 0, bot.currentScreenHandler.slots.size() - 1);
        String mode = args.get("mode").getAsString();
        SlotActionType type = switch (mode) {
            case "pickup" -> SlotActionType.PICKUP;
            case "quick_move" -> SlotActionType.QUICK_MOVE;
            case "swap" -> SlotActionType.SWAP;
            case "throw" -> SlotActionType.THROW;
            default -> throw new IllegalArgumentException("mode");
        };
        int button = integer(args, "button", 0, type == SlotActionType.SWAP ? 8 : type == SlotActionType.QUICK_MOVE ? 0 : 1);
        bot.currentScreenHandler.onSlotClick(slot, button, type, bot);
        bot.currentScreenHandler.sendContentUpdates();
        return AutonomyState.Result.success("Slot click processed; inspect resulting slots.");
    }

    private void closeInvalidScreen() {
        if (!bot.currentScreenHandler.canUse(bot)
                || (openBlock != null && reachableHit(openBlock, null) == null)) {
            bot.closeHandledScreen();
            openBlock = null;
        }
    }

    private BlockHitResult reachableHit(BlockPos pos, Direction requiredFace) {
        if (!bot.canInteractWithBlockAt(pos, 0.0)) return null;
        for (Direction direction : Direction.values()) {
            if (requiredFace != null && direction != requiredFace) continue;
            Vec3d endpoint = pos.toCenterPos().add(Vec3d.of(direction.getVector()).multiply(0.499));
            if (bot.getEyePos().squaredDistanceTo(endpoint) > bot.getBlockInteractionRange() * bot.getBlockInteractionRange()) continue;
            BlockHitResult hit = bot.getServerWorld().raycast(new RaycastContext(bot.getEyePos(), endpoint,
                    RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.ANY, bot));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)
                    && (requiredFace == null || hit.getSide() == requiredFace)) return hit;
        }
        return null;
    }

    private static AutonomyState.Result convert(ActionResult result) {
        return result.isSuccess() ? AutonomyState.Result.success("Physical action completed.")
                : fail(result.reason());
    }
    private static AutonomyState.Result fail(String reason) { return AutonomyState.Result.failure(reason, reason); }
    private static boolean flag(JsonObject obj, String name) { return obj.has(name) && obj.get(name).getAsBoolean(); }
    private static Direction face(JsonObject obj) { return Direction.valueOf(obj.get("face").getAsString().toUpperCase(Locale.ROOT)); }
    private static Vec3d coordinates(JsonObject obj) {
        return new Vec3d(number(obj, "x", -30_000_000, 30_000_000), number(obj, "y", -2048, 2048),
                number(obj, "z", -30_000_000, 30_000_000));
    }
    private static BlockPos block(JsonObject obj) {
        return new BlockPos(integer(obj, "x", -30_000_000, 30_000_000),
                integer(obj, "y", -2048, 2048), integer(obj, "z", -30_000_000, 30_000_000));
    }
    private static double number(JsonObject obj, String name, double min, double max) {
        if (!obj.get(name).isJsonPrimitive() || !obj.getAsJsonPrimitive(name).isNumber())
            throw new IllegalArgumentException(name);
        double value = obj.get(name).getAsDouble();
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException(name);
        return value;
    }
    private static int integer(JsonObject obj, String name, int min, int max) {
        double value = number(obj, name, min, max);
        if (value != Math.rint(value)) throw new IllegalArgumentException(name);
        return (int) value;
    }
}
