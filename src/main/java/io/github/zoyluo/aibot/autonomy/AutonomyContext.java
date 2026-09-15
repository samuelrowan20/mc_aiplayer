package io.github.zoyluo.aibot.autonomy;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Projects current observations and a few recent events; never serializes full runtime history. */
public final class AutonomyContext {
    private static final Gson GSON = new Gson();
    private static final Set<String> EVENT_TYPES = Set.of("intention", "result", "death", "respawned",
            "respawn", "environment_change", "interrupted", "cancelled", "reload_interrupted");

    private AutonomyContext() {}

    public static JsonObject build(JsonObject observation, AutonomyState state) {
        JsonObject lastAction = latestAction(state);
        JsonObject target = lastAction.has("arguments") && lastAction.get("arguments").isJsonObject()
                ? lastAction.getAsJsonObject("arguments") : new JsonObject();
        JsonObject position = observation.has("position") && observation.get("position").isJsonObject()
                ? observation.getAsJsonObject("position") : new JsonObject();
        JsonObject visible = fields(observation, "dimension", "position", "health", "hunger", "air", "on_fire",
                "submerged", "light", "yaw", "pitch", "selected_slot", "daylight", "raining");
        copyInventory(observation, visible);
        select(observation, visible, "effects", 8, null, null);
        select(observation, visible, "visible_blocks", 16, position, target);
        select(observation, visible, "visible_entities", 8, position, null);
        select(observation, visible, "known_positions", 8, position, target);
        if (observation.has("current_screen") && observation.get("current_screen").isJsonObject()) {
            visible.add("current_screen", screen(observation.getAsJsonObject("current_screen")));
        }

        JsonObject working = new JsonObject();
        if (state.intention() != null) working.add("intention", GSON.toJsonTree(state.intention()));
        if (!lastAction.isEmpty()) working.add("lastAction", lastAction);
        if (state.lastResult() != null) working.add("lastResult", GSON.toJsonTree(state.lastResult()));
        String memory = state.memorySummary() == null ? "" : state.memorySummary();
        working.addProperty("memorySummary", AutonomyState.bounded(memory, 2000));
        if (memory.length() > 2000) working.addProperty("memorySummary_truncated_chars", memory.length() - 2000);
        JsonArray events = recentEvents(state);
        working.add("episodes", events);
        working.addProperty("episodes_omitted", state.episodes().size() - events.size());

        JsonObject request = new JsonObject();
        request.add("observation", visible);
        request.add("working_state", working);
        return request;
    }

    private static JsonObject latestAction(AutonomyState state) {
        if (state.action() != null) return GSON.toJsonTree(state.action()).getAsJsonObject();
        for (int i = state.episodes().size() - 1; i >= 0; i--) {
            var event = state.episodes().get(i);
            JsonObject details = event.details();
            if (details == null) continue;
            if (details.has("action") && details.get("action").isJsonObject()) {
                return fields(details.getAsJsonObject("action"), "name", "arguments", "maxTicks");
            }
            if (event.type().equals("action")) return fields(details, "name", "arguments", "maxTicks");
        }
        return new JsonObject();
    }

    private static JsonArray recentEvents(AutonomyState state) {
        List<JsonObject> selected = new ArrayList<>();
        Set<String> included = new HashSet<>();
        for (int i = state.episodes().size() - 1; i >= 0 && selected.size() < 4; i--) {
            var event = state.episodes().get(i);
            if (event.type().equals("decision_received")) break;
            if (!EVENT_TYPES.contains(event.type()) || event.details() == null) continue;
            JsonObject details = fields(event.details(), "ok", "code", "message", "description", "cause", "changes",
                    "text", "purpose", "status");
            // Only death/respawn locations are historical facts needed beyond current status.
            if (event.type().equals("death") || event.type().startsWith("respawn")) {
                for (String key : List.of("position", "dimension")) {
                    if (event.details().has(key)) details.add(key, event.details().get(key).deepCopy());
                }
            }
            JsonObject compact = new JsonObject();
            compact.addProperty("type", event.type());
            compact.add("details", details);
            if (included.add(compact.toString())) selected.add(compact);
        }
        JsonArray out = new JsonArray();
        for (int i = selected.size() - 1; i >= 0; i--) out.add(selected.get(i));
        return out;
    }

    private static void copyInventory(JsonObject source, JsonObject out) {
        if (!source.has("inventory") || !source.get("inventory").isJsonArray()) return;
        JsonArray original = source.getAsJsonArray("inventory");
        JsonArray inventory = new JsonArray();
        for (JsonElement value : original) {
            if (inventory.size() >= 41) break;
            if (value.isJsonObject()) inventory.add(fields(value.getAsJsonObject(), "slot", "item", "count", "damage", "max_damage"));
        }
        out.add("inventory", inventory);
        out.addProperty("inventory_omitted", original.size() - inventory.size());
    }

    private static void select(JsonObject source, JsonObject out, String key, int limit,
                               JsonObject position, JsonObject target) {
        if (!source.has(key) || !source.get(key).isJsonArray()) return;
        JsonArray original = source.getAsJsonArray(key);
        List<JsonObject> candidates = new ArrayList<>();
        for (JsonElement value : original) if (value.isJsonObject()) candidates.add(value.getAsJsonObject());
        if (position != null) {
            // Reverse known places first so equal-distance ties favor the most recently observed.
            if (key.equals("known_positions")) java.util.Collections.reverse(candidates);
            candidates.sort(Comparator.comparingInt((JsonObject value) -> sameCell(value, target) ? 0 : 1)
                    .thenComparingDouble(value -> distanceSquared(value, position)));
        }
        JsonArray selected = new JsonArray();
        for (int i = 0; i < Math.min(limit, candidates.size()); i++) selected.add(candidates.get(i).deepCopy());
        out.add(key, selected);
        out.addProperty(key + "_omitted", original.size() - selected.size());
    }

    private static JsonObject screen(JsonObject source) {
        JsonObject out = fields(source, "type", "sync_id", "cursor");
        if (!source.has("slots") || !source.get("slots").isJsonArray()) return out;
        boolean playerScreen = source.has("type") && source.get("type").getAsString().equals("PlayerScreenHandler");
        JsonArray slots = new JsonArray();
        JsonArray empty = new JsonArray();
        JsonArray original = source.getAsJsonArray("slots");
        int duplicateInventory = 0, considered = 0;
        for (JsonElement value : original) {
            if (considered >= 128) break;
            considered++;
            if (!value.isJsonObject()) continue;
            JsonObject slot = value.getAsJsonObject();
            if (!slot.has("slot")) continue;
            int id = slot.get("slot").getAsInt();
            if (playerScreen && id >= 9 && id <= 44) {
                duplicateInventory++;
            } else if (slot.has("count") && slot.get("count").getAsInt() == 0) {
                empty.add(id);
            } else {
                slots.add(fields(slot, "slot", "item", "count", "damage", "max_damage", "can_take"));
            }
        }
        out.add("slots", slots);
        out.add("empty_slots", empty);
        out.addProperty("slots_omitted", original.size() - considered);
        if (playerScreen) {
            out.addProperty("duplicate_inventory_slots_omitted", duplicateInventory);
            JsonArray mappings = new JsonArray();
            JsonObject main = new JsonObject();
            main.addProperty("screen_first", 9);
            main.addProperty("inventory_first", 9);
            main.addProperty("count", 27);
            mappings.add(main);
            JsonObject hotbar = new JsonObject();
            hotbar.addProperty("screen_first", 36);
            hotbar.addProperty("inventory_first", 0);
            hotbar.addProperty("count", 9);
            mappings.add(hotbar);
            out.add("inventory_slot_ranges", mappings);
        }
        return out;
    }

    private static JsonObject fields(JsonObject source, String... keys) {
        JsonObject out = new JsonObject();
        for (String key : keys) if (source.has(key)) out.add(key, source.get(key).deepCopy());
        return out;
    }

    private static boolean sameCell(JsonObject value, JsonObject target) {
        if (target == null || !hasCoordinates(target) || !hasCoordinates(value)) return false;
        for (String axis : List.of("x", "y", "z")) {
            if (Math.floor(value.get(axis).getAsDouble()) != Math.floor(target.get(axis).getAsDouble())) return false;
        }
        return true;
    }

    private static double distanceSquared(JsonObject a, JsonObject b) {
        if (!hasCoordinates(a) || !hasCoordinates(b)) return Double.POSITIVE_INFINITY;
        double sum = 0;
        for (String axis : List.of("x", "y", "z")) {
            double delta = a.get(axis).getAsDouble() - b.get(axis).getAsDouble();
            sum += delta * delta;
        }
        return sum;
    }

    private static boolean hasCoordinates(JsonObject value) {
        for (String axis : List.of("x", "y", "z")) {
            if (!value.has(axis) || !value.get(axis).isJsonPrimitive()
                    || !value.getAsJsonPrimitive(axis).isNumber()
                    || !Double.isFinite(value.get(axis).getAsDouble())) return false;
        }
        return true;
    }
}
