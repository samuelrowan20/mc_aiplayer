package io.github.zoyluo.aibot.autonomy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static io.github.zoyluo.aibot.autonomy.AutonomyState.Phase;
import static org.junit.jupiter.api.Assertions.*;

final class AutonomyEngineTest {
    private static final AutonomySettings SETTINGS = new AutonomySettings(2, 40, 12, 40, 10,
            4, 32, 3, 12, 100, 4096, 2);
    private static final AutonomyDecision.Intention INTENTION = new AutonomyDecision.Intention(
            "Model-selected intention", "Public purpose", "active");

    private static AutonomyDecision action(String arguments) {
        return new AutonomyDecision(INTENTION, new AutonomyDecision.Action("navigate",
                JsonParser.parseString(arguments).getAsJsonObject(), 8), null, "Model-owned memory");
    }

    private static AutonomyProvider.Reply reply(AutonomyDecision decision) {
        return new AutonomyProvider.Reply(decision, 10, 100, 25);
    }

    @Test void continuesWithoutHumanGoalsAndKeepsIntentionAcrossActions() {
        FakePorts ports = new FakePorts();
        int[] requests = {0};
        List<AutonomyState.Event> events = new ArrayList<>();
        AutonomyEngine engine = new AutonomyEngine(SETTINGS, context -> {
            requests[0]++;
            assertTrue(context.has("observation"));
            return CompletableFuture.completedFuture(reply(action("{\"x\":1}")));
        }, ports, events::add);
        engine.start();
        advance(engine, 50);
        assertTrue(requests[0] > 5);
        assertTrue(ports.starts > 5);
        assertEquals(INTENTION, engine.state().intention());
        assertEquals(1, events.stream().filter(event -> event.type().equals("intention")).count());
        assertTrue(engine.state().episodes().size() <= SETTINGS.maxEpisodes());
    }

    @Test void purposefulWaitStaysStillUntilBoundExpiresThenDecides() {
        FakePorts ports = new FakePorts();
        int[] requests = {0};
        AutonomyEngine engine = new AutonomyEngine(SETTINGS, context -> {
            requests[0]++;
            return CompletableFuture.completedFuture(reply(new AutonomyDecision(INTENTION, null,
                    new AutonomyDecision.Wait("An explicit model-selected reason", 10), null)));
        }, ports, event -> { });
        engine.start();
        advance(engine, 2);
        assertEquals(Phase.WAITING, engine.state().phase());
        advance(engine, 9);
        assertEquals(1, requests[0]);
        assertEquals(0, ports.starts);
        advance(engine, 4);
        assertEquals(2, requests[0]);
    }

    @Test void pausesCancelWorkAndIgnoreLateUncancellableResponse() {
        FakePorts ports = new FakePorts();
        CompletableFuture<AutonomyProvider.Reply> late = new CompletableFuture<>() {
            @Override public boolean cancel(boolean mayInterrupt) { return false; }
        };
        int[] requests = {0};
        AutonomyEngine engine = new AutonomyEngine(SETTINGS, context -> ++requests[0] == 1
                ? late : CompletableFuture.completedFuture(reply(action("{}"))), ports, event -> { });
        engine.start();
        engine.tick();
        engine.pause();
        late.complete(reply(action("{}")));
        advance(engine, 20);
        assertEquals(0, ports.starts);
        assertEquals(Phase.PAUSED, engine.state().phase());
        engine.resume();
        advance(engine, 3);
        assertEquals(1, ports.starts);
        engine.stop();
        advance(engine, 20);
        assertEquals(Phase.STOPPED, engine.state().phase());
        assertEquals(1, ports.starts);
        assertTrue(ports.cancels >= 2);
    }

    @Test void outageUsesCappedExponentialBackoffAndRecovers() {
        FakePorts ports = new FakePorts();
        int[] requests = {0};
        List<Long> delays = new ArrayList<>();
        AutonomyEngine engine = new AutonomyEngine(SETTINGS, context -> ++requests[0] <= 8
                ? CompletableFuture.failedFuture(new IllegalStateException("provider_http_429"))
                : CompletableFuture.completedFuture(reply(action("{}"))), ports,
                event -> { if (event.type().equals("provider_error")) delays.add(event.details().get("retry_ticks").getAsLong()); });
        engine.start();
        advance(engine, 400);
        assertEquals(List.of(4L, 8L, 16L, 32L, 32L, 32L, 32L, 32L), delays);
        assertTrue(ports.starts > 0);
        assertEquals(0, engine.state().providerFailures());
    }

    @Test void equivalentFailureSuppressionIgnoresArgumentOrderAndNumericFormatting() {
        FakePorts ports = new FakePorts();
        ports.result = AutonomyState.Result.failure("no_path", "No physical route");
        int[] requests = {0};
        AutonomyEngine engine = new AutonomyEngine(SETTINGS, context -> CompletableFuture.completedFuture(reply(
                action(++requests[0] % 2 == 0 ? "{\"x\":1,\"y\":2}" : "{\"y\":2.0,\"x\":1.0}"))), ports, event -> { });
        engine.start();
        advance(engine, 100);
        assertEquals(3, ports.starts);
        assertTrue(requests[0] > 10);
        assertEquals("repeated_failure_suppressed", engine.state().lastResult().code());
        assertEquals(1, engine.state().failures().size());
    }

    @Test void actionTimeoutAndDeathReturnControlWithoutChoosingARecoveryTask() {
        FakePorts ports = new FakePorts();
        ports.result = null;
        List<AutonomyState.Event> events = new ArrayList<>();
        AutonomyEngine engine = new AutonomyEngine(SETTINGS,
                context -> CompletableFuture.completedFuture(reply(action("{}"))), ports, events::add);
        engine.start();
        advance(engine, 10);
        assertEquals("action_timeout", engine.state().lastResult().code());
        assertTrue(ports.cancels > 0);
        engine.died("Observed death");
        int starts = ports.starts;
        advance(engine, 20);
        assertEquals(Phase.DEAD, engine.state().phase());
        assertEquals(starts, ports.starts);
        engine.respawned();
        advance(engine, 3);
        assertTrue(ports.starts > starts);
        assertTrue(events.stream().anyMatch(event -> event.type().equals("death")));
        assertTrue(events.stream().anyMatch(event -> event.type().equals("respawned")));
        assertEquals(INTENTION, engine.state().intention());
    }

    @Test void failureBudgetResetsOnChangedResourcesButNotTimeHealthOrPositionJitter() {
        FakePorts ports = new FakePorts();
        ports.result = AutonomyState.Result.failure("missing_resource", "Recipe ingredient unavailable");
        ports.observation = JsonParser.parseString("{\"inventory\":[],\"position\":{\"x\":0.1,\"y\":64,\"z\":0}}").getAsJsonObject();
        AutonomyEngine engine = new AutonomyEngine(SETTINGS,
                context -> CompletableFuture.completedFuture(reply(action("{}"))), ports, event -> { });
        engine.start();
        advance(engine, 50);
        assertEquals(3, ports.starts);
        ports.observation.addProperty("game_time", 6000);
        ports.observation.addProperty("health", 10);
        ports.observation.getAsJsonObject("position").addProperty("x", 0.2);
        advance(engine, 50);
        assertEquals(3, ports.starts);
        ports.observation.add("inventory", JsonParser.parseString("[{\"slot\":0,\"item\":\"newly_observed_item\",\"count\":1}]"));
        ports.result = AutonomyState.Result.success("Available now");
        advance(engine, 10);
        assertTrue(ports.starts > 3);
        assertTrue(engine.state().failures().isEmpty());
    }

    @Test void pausedDeathDoesNotResumeWithoutOperator() {
        FakePorts ports = new FakePorts();
        AutonomyEngine engine = new AutonomyEngine(SETTINGS,
                context -> CompletableFuture.completedFuture(reply(action("{}"))), ports, event -> { });
        engine.start();
        engine.pause();
        engine.died("death");
        engine.respawned();
        advance(engine, 20);
        assertEquals(Phase.PAUSED, engine.state().phase());
        assertEquals(0, ports.starts);
    }

    @Test void repeatedEnvironmentalNoticesDoNotStarveAnInflightDecision() {
        FakePorts ports = new FakePorts();
        var pending = new CompletableFuture<AutonomyProvider.Reply>();
        List<AutonomyState.Event> events = new ArrayList<>();
        AutonomyEngine engine = new AutonomyEngine(SETTINGS, context -> pending, ports, events::add);
        engine.start();
        engine.tick();
        for (int hit = 0; hit < 20; hit++) {
            engine.notice("health_decreased");
            engine.tick();
        }
        assertFalse(pending.isCancelled());
        assertEquals(Phase.DECIDING, engine.state().phase());
        assertEquals(20, events.stream().filter(event -> event.type().equals("environment_change")).count());
        pending.complete(reply(action("{}")));
        engine.tick();
        assertEquals(1, ports.starts);
    }

    @Test void stoppedDeathAndRespawnAreRememberedWithoutRestartingAutonomy() {
        FakePorts ports = new FakePorts();
        int[] decisions = {0};
        List<AutonomyState.Event> events = new ArrayList<>();
        AutonomyEngine engine = new AutonomyEngine(SETTINGS, context -> {
            decisions[0]++;
            return CompletableFuture.completedFuture(reply(action("{}")));
        }, ports, events::add);
        engine.start();
        engine.stop();
        engine.died("Observed stopped death");
        engine.respawned();
        advance(engine, 20);
        assertEquals(Phase.STOPPED, engine.state().phase());
        assertEquals(0, decisions[0]);
        assertEquals("death", engine.state().lastResult().code());
        assertEquals(1, events.stream().filter(event -> event.type().equals("death")).count());
        assertEquals(1, events.stream().filter(event -> event.type().equals("respawned")).count());
        engine.start();
        advance(engine, 2);
        assertEquals(1, decisions[0]);
    }

    @Test void reloadMakesInterruptedPhysicalOutcomeExplicitAndInvalidatesInflightRequests() {
        FakePorts ports = new FakePorts();
        ports.result = null;
        AutonomyEngine engine = new AutonomyEngine(SETTINGS,
                context -> CompletableFuture.completedFuture(reply(action("{\"x\":1}"))), ports, event -> { });
        engine.start();
        advance(engine, 2);
        assertEquals(Phase.ACTING, engine.state().phase());
        engine.restore(engine.snapshot());
        assertEquals(Phase.READY, engine.state().phase());
        assertNull(engine.state().action());
        assertEquals("reload_interrupted", engine.state().lastResult().code());
        assertTrue(engine.state().lastResult().message().contains("outcome is unknown"));
        assertTrue(engine.state().episodes().stream().anyMatch(event -> event.type().equals("reload_interrupted")
                && event.details().has("action")));
        assertEquals(1, ports.starts);

        CompletableFuture<AutonomyProvider.Reply> request = new CompletableFuture<>();
        AutonomyEngine deciding = new AutonomyEngine(SETTINGS, context -> request, ports, event -> { });
        deciding.start();
        deciding.tick();
        deciding.restore(deciding.snapshot());
        assertTrue(request.isCancelled());
        assertEquals(Phase.READY, deciding.state().phase());
        assertEquals("reload_interrupted", deciding.state().lastResult().code());
        assertTrue(deciding.state().lastResult().message().contains("no returned decision was applied"));
    }

    @Test void saveRestorePreservesWaitMemoryAndFailureBoundsWithoutReplayingPhysicalAction() {
        FakePorts ports = new FakePorts();
        AutonomyProvider provider = context -> CompletableFuture.completedFuture(reply(
                new AutonomyDecision(INTENTION, null, new AutonomyDecision.Wait("Observing", 30), "Kept memory")));
        AutonomyEngine engine = new AutonomyEngine(SETTINGS, provider, ports, event -> { });
        engine.start();
        advance(engine, 10);
        JsonObject snapshot = engine.snapshot();
        AutonomyEngine restored = new AutonomyEngine(SETTINGS, provider, ports, event -> { });
        restored.restore(snapshot);
        assertEquals(Phase.WAITING, restored.state().phase());
        assertEquals(engine.state().waitRemainingTicks(), restored.state().waitRemainingTicks());
        assertEquals("Kept memory", restored.state().memorySummary());
        assertEquals(INTENTION, restored.state().intention());
        advance(restored, 25);
        assertNotEquals(Phase.STOPPED, restored.state().phase());

        snapshot.addProperty("phase", "ACTING");
        restored.restore(snapshot);
        assertEquals(Phase.READY, restored.state().phase());
        assertNull(restored.state().action());
    }

    @Test void acceleratedTwentyFourHourSoakHasBoundedStateAndRecoversAcrossLifecycleEvents() {
        FakePorts ports = new FakePorts();
        int[] requests = {0};
        int[] eventCount = {0};
        int[] maxContext = {0};
        AutonomyProvider provider = context -> {
            maxContext[0] = Math.max(maxContext[0], context.toString().length());
            int sequence = ++requests[0];
            if (sequence % 23 == 0) return CompletableFuture.failedFuture(new IllegalStateException("provider_http_503"));
            ports.result = sequence % 7 == 0 ? AutonomyState.Result.failure("no_path", "Unavailable")
                    : AutonomyState.Result.success("Performed selected operation");
            AutonomyDecision decision = sequence % 11 == 0
                    ? new AutonomyDecision(INTENTION, null, new AutonomyDecision.Wait("Model purpose", 40), "x".repeat(200))
                    : action("{\"x\":" + (sequence % 40) + "}");
            return CompletableFuture.completedFuture(reply(decision));
        };
        // Default pacing keeps this equivalent to 24 hours at 20 ticks/second, without sleeping.
        AutonomyEngine engine = new AutonomyEngine(AutonomySettings.defaults(), provider, ports, event -> eventCount[0]++);
        engine.start();
        for (int tick = 0; tick < 24 * 60 * 60 * 20; tick++) {
            if (tick % 12000 == 3000) engine.died("soak death");
            if (tick % 12000 == 3100) engine.respawned();
            if (tick % 18000 == 6000) engine.pause();
            if (tick % 18000 == 6200) engine.resume();
            if (tick % 24000 == 10000) engine.restore(engine.snapshot());
            engine.tick();
        }
        assertTrue(requests[0] > 20000);
        assertTrue(ports.starts > 20000);
        assertTrue(eventCount[0] > 50000);
        assertTrue(maxContext[0] < 40000, "bounded serialized context=" + maxContext[0]);
        assertTrue(engine.state().episodes().size() <= 32);
        assertTrue(engine.state().failures().size() <= 32);
        assertTrue(engine.snapshot().toString().length() < 40000);
        assertNotEquals(Phase.STOPPED, engine.state().phase());
        System.out.println("Autonomy soak: simulated_ticks=1728000 requests=" + requests[0]
                + " actions=" + ports.starts + " events=" + eventCount[0] + " max_context_chars=" + maxContext[0]);
    }

    private static void advance(AutonomyEngine engine, int count) { for (int index = 0; index < count; index++) engine.tick(); }

    private static final class FakePorts implements AutonomyEngine.Ports {
        int starts, cancels;
        JsonObject observation = JsonParser.parseString("{\"health\":20,\"hunger\":20}").getAsJsonObject();
        AutonomyState.Result result = AutonomyState.Result.success("Done");
        @Override public JsonObject observe() { return observation; }
        @Override public AutonomyState.Result start(AutonomyDecision.Action action) { starts++; return result; }
        @Override public AutonomyState.Result poll() { return result; }
        @Override public void cancel() { cancels++; }
    }
}
