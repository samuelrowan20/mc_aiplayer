package io.github.zoyluo.aibot.autonomy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class AutonomyContextTest {
    @Test void growingHistoryDoesNotGrowTheRequestAndRuntimeInternalsStayPrivate() {
        List<AutonomyState.Event> recent = List.of(
                event("decision_received", "{\"latency_ms\":123}"),
                event("death", "{\"code\":\"death\",\"message\":\"Fell\",\"position\":{\"x\":1,\"y\":2,\"z\":3}}"),
                event("respawned", "{}"),
                event("provider_error", "{\"retry_ticks\":100,\"secret_retry_payload\":true}"),
                event("environment_change", "{\"description\":\"Health changed from 20 to 16\"}"));
        JsonObject small = AutonomyContext.build(observation(), state(recent, null, "Model memory"));
        List<AutonomyState.Event> expanded = new ArrayList<>();
        for (int i = 0; i < 2000; i++) expanded.add(event("result", "{\"message\":\"OLD_HISTORY_PAYLOAD\"}"));
        expanded.addAll(recent);
        JsonObject large = AutonomyContext.build(observation(), state(expanded, null, "Model memory"));
        assertTrue(Math.abs(large.toString().length() - small.toString().length()) <= 4);
        assertEquals(small.getAsJsonObject("working_state").get("episodes"),
                large.getAsJsonObject("working_state").get("episodes"));
        String request = large.toString();
        for (String excluded : List.of("OLD_HISTORY_PAYLOAD", "SECRET_FAILURE_HASH", "failureEvidence",
                "providerFailures", "retryRemainingTicks", "latency_ms", "secret_retry_payload", "timestamp")) {
            assertFalse(request.contains(excluded), excluded);
        }
        JsonObject working = large.getAsJsonObject("working_state");
        assertEquals("blocked", working.getAsJsonObject("lastResult").get("code").getAsString());
        assertTrue(working.getAsJsonArray("episodes").toString().contains("\"death\""));
        assertTrue(working.getAsJsonArray("episodes").toString().contains("\"respawned\""));
        assertEquals(2002, working.get("episodes_omitted").getAsInt());
    }

    @Test void selectedTargetComesFirstWithoutResourceRankingAndNearbyListsAreBounded() {
        JsonObject observation = observation();
        JsonArray blocks = new JsonArray(), entities = new JsonArray(), places = new JsonArray();
        for (int x = 1; x <= 40; x++) {
            JsonObject block = position(x);
            block.addProperty("state", x == 39 ? "minecraft:diamond_ore" : "minecraft:stone");
            blocks.add(block);
            entities.add(position(41 - x));
            places.add(position(x));
        }
        observation.add("visible_blocks", blocks);
        observation.add("visible_entities", entities);
        observation.add("known_positions", places);
        var events = List.of(event("decision_received", "{}"), event("result",
                "{\"ok\":false,\"code\":\"blocked\",\"action\":{\"name\":\"navigate\",\"arguments\":{\"x\":40,\"y\":0,\"z\":0},\"maxTicks\":100}}"));
        JsonObject request = AutonomyContext.build(observation, state(events, null, ""));
        JsonObject compact = request.getAsJsonObject("observation");
        assertEquals(16, compact.getAsJsonArray("visible_blocks").size());
        assertEquals(40, compact.getAsJsonArray("visible_blocks").get(0).getAsJsonObject().get("x").getAsInt());
        assertEquals(1, compact.getAsJsonArray("visible_blocks").get(1).getAsJsonObject().get("x").getAsInt());
        assertFalse(compact.getAsJsonArray("visible_blocks").toString().contains("diamond_ore"));
        assertEquals(24, compact.get("visible_blocks_omitted").getAsInt());
        assertEquals(8, compact.getAsJsonArray("visible_entities").size());
        assertEquals(1, compact.getAsJsonArray("visible_entities").get(0).getAsJsonObject().get("x").getAsInt());
        assertEquals(8, compact.getAsJsonArray("known_positions").size());
        assertEquals(40, compact.getAsJsonArray("known_positions").get(0).getAsJsonObject().get("x").getAsInt());
        assertEquals("navigate", request.getAsJsonObject("working_state").getAsJsonObject("lastAction").get("name").getAsString());
        // Returned data is detached: callers cannot mutate the original observation/history.
        compact.getAsJsonArray("visible_blocks").get(0).getAsJsonObject().addProperty("state", "changed");
        assertEquals("minecraft:stone", blocks.get(39).getAsJsonObject().get("state").getAsString());
    }

    @Test void modelMemoryAndDistinctEventsAreCappedAndOlderDecisionsAreNotRepeated() {
        var events = List.of(event("death", "{\"message\":\"OLD_DEATH_ALREADY_CONSIDERED\"}"),
                event("decision_received", "{}"),
                event("intention", "{\"text\":\"New intention\"}"),
                event("environment_change", "{\"description\":\"Same change\"}"),
                event("environment_change", "{\"description\":\"Same change\"}"),
                event("cancelled", "{\"code\":\"cancelled\",\"message\":\"Changed circumstances\"}"),
                event("interrupted", "{\"code\":\"interruption\"}"),
                event("result", "{\"ok\":false,\"code\":\"blocked\"}"));
        JsonObject working = AutonomyContext.build(observation(), state(events, null, "m".repeat(5000)))
                .getAsJsonObject("working_state");
        assertEquals(2000, working.get("memorySummary").getAsString().length());
        assertEquals(3000, working.get("memorySummary_truncated_chars").getAsInt());
        assertEquals(4, working.getAsJsonArray("episodes").size());
        assertFalse(working.toString().contains("OLD_DEATH_ALREADY_CONSIDERED"));
        long changes = working.getAsJsonArray("episodes").asList().stream()
                .filter(value -> value.getAsJsonObject().get("type").getAsString().equals("environment_change")).count();
        assertEquals(1, changes);
    }

    @Test void playerScreenRetainsCraftingArmorAndExplicitInventoryToScreenMappings() {
        JsonObject observation = observation();
        JsonObject screen = json("{\"type\":\"PlayerScreenHandler\",\"sync_id\":0,\"cursor\":{\"item\":\"minecraft:air\",\"count\":0}}");
        JsonArray slots = new JsonArray();
        for (int id = 0; id <= 45; id++) slots.add(slot(id, id == 5 || id == 36 ? 1 : 0));
        screen.add("slots", slots);
        observation.add("current_screen", screen);
        JsonObject compact = AutonomyContext.build(observation, state(List.of(), null, ""))
                .getAsJsonObject("observation").getAsJsonObject("current_screen");
        assertEquals(1, compact.getAsJsonArray("slots").size());
        assertEquals(5, compact.getAsJsonArray("slots").get(0).getAsJsonObject().get("slot").getAsInt());
        assertTrue(compact.getAsJsonArray("empty_slots").contains(new com.google.gson.JsonPrimitive(0)));
        assertTrue(compact.getAsJsonArray("empty_slots").contains(new com.google.gson.JsonPrimitive(45)));
        assertEquals(36, compact.get("duplicate_inventory_slots_omitted").getAsInt());
        JsonObject hotbar = compact.getAsJsonArray("inventory_slot_ranges").get(1).getAsJsonObject();
        assertEquals(36, hotbar.get("screen_first").getAsInt());
        assertEquals(0, hotbar.get("inventory_first").getAsInt());
        assertEquals(9, hotbar.get("count").getAsInt());
        assertEquals(0, compact.get("sync_id").getAsInt());
        assertTrue(compact.has("cursor"));
    }

    @Test void externalScreenPreservesOccupiedSlotsAndCompactEmptyIndicesWithinBound() {
        JsonObject observation = observation();
        JsonObject screen = json("{\"type\":\"GenericContainerScreenHandler\",\"sync_id\":7,\"cursor\":{\"count\":0}}");
        JsonArray slots = new JsonArray();
        for (int id = 0; id < 140; id++) slots.add(slot(id, id == 0 || id == 64 || id == 129 ? 3 : 0));
        screen.add("slots", slots);
        observation.add("current_screen", screen);
        JsonObject compact = AutonomyContext.build(observation, state(List.of(), null, ""))
                .getAsJsonObject("observation").getAsJsonObject("current_screen");
        assertEquals(2, compact.getAsJsonArray("slots").size());
        assertEquals(64, compact.getAsJsonArray("slots").get(1).getAsJsonObject().get("slot").getAsInt());
        assertEquals(126, compact.getAsJsonArray("empty_slots").size());
        assertEquals(12, compact.get("slots_omitted").getAsInt());
        assertEquals(7, compact.get("sync_id").getAsInt());
        assertFalse(compact.has("inventory_slot_ranges"));
    }

    @Test void rejectedMalformedTargetDoesNotPreventTheNextDecisionContext() {
        JsonObject observation = observation();
        JsonArray blocks = new JsonArray();
        blocks.add(position(1));
        observation.add("visible_blocks", blocks);
        var invalid = new AutonomyDecision.Action("navigate", json("{\"x\":\"invalid\",\"y\":0,\"z\":0}"), 20);
        JsonObject request = assertDoesNotThrow(() -> AutonomyContext.build(observation, state(List.of(), invalid, "")));
        assertEquals("blocked", request.getAsJsonObject("working_state").getAsJsonObject("lastResult").get("code").getAsString());
        assertEquals(1, request.getAsJsonObject("observation").getAsJsonArray("visible_blocks").size());
    }

    private static JsonObject observation() {
        return json("{\"position\":{\"x\":0,\"y\":0,\"z\":0},\"health\":20,\"hunger\":20,\"inventory\":[{\"slot\":0,\"item\":\"minecraft:stone\",\"count\":1}],\"internal_debug\":\"NOT_FOR_MODEL\"}");
    }

    private static AutonomyState state(List<AutonomyState.Event> events, AutonomyDecision.Action action, String memory) {
        return new AutonomyState(AutonomyState.Phase.READY, 42,
                new AutonomyDecision.Intention("Selected intention", "Public purpose", "active"), action, null, 0,
                AutonomyState.Result.failure("blocked", "Selected action failed"), memory, events,
                List.of(new AutonomyState.Failure("SECRET_FAILURE_HASH", 3)), "SECRET_FAILURE_HASH", 9, 1200);
    }

    private static AutonomyState.Event event(String type, String details) {
        return new AutonomyState.Event("private timestamp", 1, type, json(details));
    }

    private static JsonObject slot(int id, int count) {
        JsonObject value = new JsonObject();
        value.addProperty("slot", id);
        value.addProperty("item", count == 0 ? "minecraft:air" : "minecraft:stone");
        value.addProperty("count", count);
        value.addProperty("can_take", true);
        return value;
    }

    private static JsonObject position(int x) {
        JsonObject value = new JsonObject();
        value.addProperty("x", x);
        value.addProperty("y", 0);
        value.addProperty("z", 0);
        return value;
    }

    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
}
