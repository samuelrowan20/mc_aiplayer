package io.github.zoyluo.aibot.autonomy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.LinkedHashSet;
import java.util.Set;

/** Bounded, neutral first-hit perception. Never enumerates chunk contents or ranks resources. */
public final class AutonomyObservation {
    public static final int RADIUS = 16;
    private static final int MAX_KNOWN = 128;
    private final AIPlayerEntity bot;
    private final Set<BlockPos> known = new LinkedHashSet<>();
    private String dimension;

    public AutonomyObservation(AIPlayerEntity bot) { this.bot = bot; }

    public JsonObject snapshot() {
        JsonObject out = new JsonObject();
        out.addProperty("dimension", dimension);
        JsonArray positions = new JsonArray();
        known.forEach(pos -> positions.add(position(Vec3d.of(pos))));
        out.add("positions", positions);
        return out;
    }

    public void restore(JsonObject data) {
        known.clear();
        if (data == null || !data.has("dimension") || data.get("dimension").isJsonNull()) return;
        dimension = data.get("dimension").getAsString();
        if (!dimension.equals(bot.getServerWorld().getRegistryKey().getValue().toString())) return;
        if (!data.has("positions") || !data.get("positions").isJsonArray()) return;
        for (var element : data.getAsJsonArray("positions")) {
            if (known.size() >= MAX_KNOWN) break;
            try {
                JsonObject pos = element.getAsJsonObject();
                int x = pos.get("x").getAsInt(), y = pos.get("y").getAsInt(), z = pos.get("z").getAsInt();
                if (Math.abs((long)x) <= 30_000_000 && Math.abs((long)z) <= 30_000_000 && y >= -2048 && y <= 2048)
                    remember(new BlockPos(x, y, z));
            } catch (RuntimeException ignored) { /* A malformed remembered place is discarded. */ }
        }
    }

    public JsonObject observe() {
        String currentDimension = bot.getServerWorld().getRegistryKey().getValue().toString();
        if (!currentDimension.equals(dimension)) {
            known.clear();
            dimension = currentDimension;
        }
        remember(bot.getBlockPos());
        JsonObject out = new JsonObject();
        out.addProperty("dimension", dimension);
        if (bot.getServerWorld().isSkyVisible(bot.getBlockPos())) {
            out.addProperty("daylight", bot.getServerWorld().isDay());
            out.addProperty("raining", bot.getServerWorld().isRaining());
        }
        out.addProperty("health", bot.getHealth());
        out.addProperty("hunger", bot.getHungerManager().getFoodLevel());
        out.addProperty("air", bot.getAir());
        out.addProperty("on_fire", bot.isOnFire());
        out.addProperty("submerged", bot.isSubmergedInWater());
        out.addProperty("light", bot.getServerWorld().getLightLevel(bot.getBlockPos()));
        out.addProperty("yaw", bot.getYaw());
        out.addProperty("pitch", bot.getPitch());
        out.add("position", position(bot.getPos()));
        out.addProperty("selected_slot", bot.getInventory().selectedSlot);
        JsonArray effects = new JsonArray();
        bot.getStatusEffects().forEach(effect -> {
            JsonObject value = new JsonObject();
            value.addProperty("id", Registries.STATUS_EFFECT.getId(effect.getEffectType().value()).toString());
            value.addProperty("amplifier", effect.getAmplifier());
            value.addProperty("ticks", effect.getDuration());
            effects.add(value);
        });
        out.add("effects", effects);
        JsonArray inventory = new JsonArray();
        for (int slot = 0; slot < bot.getInventory().size(); slot++) {
            ItemStack stack = bot.getInventory().getStack(slot);
            if (!stack.isEmpty()) inventory.add(stack(slot, stack));
        }
        out.add("inventory", inventory);

        // Uniform angular sampling has a fixed cost independent of world/chunk size.
        Set<BlockPos> seen = new LinkedHashSet<>();
        JsonArray blocks = new JsonArray();
        for (int pitch = -75; pitch <= 75; pitch += 15) {
            for (int yaw = 0; yaw < 360; yaw += 15) {
                Vec3d end = bot.getEyePos().add(Vec3d.fromPolar(pitch, yaw).multiply(RADIUS));
                BlockHitResult hit = ray(end);
                if (blocks.size() >= 96 || hit.getType() != HitResult.Type.BLOCK || !seen.add(hit.getBlockPos())) continue;
                BlockPos pos = hit.getBlockPos();
                JsonObject block = position(pos.toCenterPos());
                block.addProperty("x", pos.getX());
                block.addProperty("y", pos.getY());
                block.addProperty("z", pos.getZ());
                block.addProperty("state", bot.getServerWorld().getBlockState(pos).toString());
                block.addProperty("face", hit.getSide().asString());
                blocks.add(block);
                BlockPos adjacent = pos.offset(hit.getSide());
                if (visibleCell(adjacent)) remember(adjacent);
            }
        }
        out.add("visible_blocks", blocks);
        JsonArray entities = new JsonArray();
        bot.getServerWorld().getOtherEntities(bot, bot.getBoundingBox().expand(RADIUS), this::visibleEntity)
                .stream().sorted(java.util.Comparator.comparingDouble(bot::squaredDistanceTo)).limit(32)
                .forEach(entity -> {
                    JsonObject value = position(entity.getPos());
                    value.addProperty("entity_id", entity.getId());
                    value.addProperty("type", Registries.ENTITY_TYPE.getId(entity.getType()).toString());
                    entities.add(value);
                });
        out.add("visible_entities", entities);
        JsonArray places = new JsonArray();
        known.forEach(pos -> places.add(position(Vec3d.of(pos))));
        out.add("known_positions", places);
        JsonObject screen = new JsonObject();
        screen.addProperty("type", bot.currentScreenHandler.getClass().getSimpleName());
        screen.addProperty("sync_id", bot.currentScreenHandler.syncId);
        JsonArray slots = new JsonArray();
        for (int i = 0; i < Math.min(128, bot.currentScreenHandler.slots.size()); i++) {
            JsonObject value = stack(i, bot.currentScreenHandler.getSlot(i).getStack());
            value.addProperty("can_take", bot.currentScreenHandler.getSlot(i).canTakeItems(bot));
            slots.add(value);
        }
        screen.add("slots", slots);
        screen.add("cursor", stack(-1, bot.currentScreenHandler.getCursorStack()));
        out.add("current_screen", screen);
        return out;
    }

    public boolean knownOrVisible(BlockPos pos) {
        return known.contains(pos) || visibleCell(pos);
    }

    public boolean visibleCell(BlockPos pos) {
        if (bot.getEyePos().squaredDistanceTo(pos.toCenterPos()) > RADIUS * RADIUS) return false;
        BlockHitResult hit = ray(pos.toCenterPos());
        if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos)) return true;
        // A ray aimed inside a distant ground block enters the nearer floor first. Its
        // exposed top face can still be visible. Prove a first hit on that exact face
        // before permitting any topology read; sealed blocks remain unobservable.
        for (Direction face : Direction.values()) {
            Vec3d endpoint = pos.toCenterPos().add(Vec3d.of(face.getVector()).multiply(0.499));
            if (bot.getEyePos().squaredDistanceTo(endpoint) > RADIUS * RADIUS) continue;
            BlockHitResult faceHit = ray(endpoint);
            if (faceHit.getType() == HitResult.Type.BLOCK && faceHit.getBlockPos().equals(pos)
                    && faceHit.getSide() == face) return true;
        }
        return false;
    }

    public boolean visibleEntity(Entity entity) {
        return entity != bot && entity.isAlive() && !entity.isInvisible() && !entity.isInvisibleTo(bot)
                && !entity.isSpectator() && bot.squaredDistanceTo(entity) <= RADIUS * RADIUS
                && bot.canSee(entity);
    }

    private BlockHitResult ray(Vec3d endpoint) {
        return bot.getServerWorld().raycast(new RaycastContext(bot.getEyePos(), endpoint,
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.ANY, bot));
    }

    private void remember(BlockPos pos) {
        known.remove(pos);
        known.add(pos.toImmutable());
        while (known.size() > MAX_KNOWN) known.remove(known.iterator().next());
    }

    public static JsonObject position(Vec3d pos) {
        JsonObject value = new JsonObject();
        value.addProperty("x", pos.x);
        value.addProperty("y", pos.y);
        value.addProperty("z", pos.z);
        return value;
    }

    private static JsonObject stack(int slot, ItemStack stack) {
        JsonObject value = new JsonObject();
        value.addProperty("slot", slot);
        value.addProperty("item", Registries.ITEM.getId(stack.getItem()).toString());
        value.addProperty("count", stack.getCount());
        if (stack.isDamageable()) {
            value.addProperty("damage", stack.getDamage());
            value.addProperty("max_damage", stack.getMaxDamage());
        }
        return value;
    }
}
