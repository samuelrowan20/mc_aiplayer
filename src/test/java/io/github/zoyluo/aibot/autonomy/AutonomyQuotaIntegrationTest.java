package io.github.zoyluo.aibot.autonomy;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP/provider/engine admission tests; all requests stay on the loopback interface. */
final class AutonomyQuotaIntegrationTest {
    private static final Gson GSON = new Gson();
    private static final JsonArray CAPABILITIES = JsonParser.parseString("""
            [{"name":"inspect","description":"Inspect perceptible state","parameters":{"type":"object","properties":{},"additionalProperties":false}}]
            """).getAsJsonArray();
    private static final AutonomySettings SETTINGS = new AutonomySettings(2, 40, 12, 40, 5,
            2, 8, 3, 12, 100, 4096, 2);
    @TempDir Path directory;

    @Test void successfulRequestIsReservedBeforeHttpAndSettledFromActualUsage() throws Exception {
        Path ledger = directory.resolve("success.json");
        var budget = new AutonomyTokenBudget(ledger, 200000);
        try (var fixture = new Fixture(ledger); var provider = fixture.provider(budget)) {
            var reply = provider.decide(new JsonObject()).get(5, TimeUnit.SECONDS);
            assertEquals("inspect", reply.decision().action().name());
            assertEquals(1, fixture.requests.get());
            assertTrue(fixture.reservationWasDurable.get());
            assertTrue(fixture.lastReservation.get() > 30);
            assertEquals(30, budget.status().usedTokens());
            assertEquals(199970, budget.cachedStatus().remainingTokens());
            assertEquals(30, new AutonomyTokenBudget(ledger, 200000).status().usedTokens());
            assertFalse(Files.readString(ledger).contains("PRIVATE_SECRET"));
            assertFalse(Files.readString(ledger).contains("test-api-key"));
        }
    }

    @Test void absentOrUntrustworthyUsageNeverRefundsTheConservativeReservation() throws Exception {
        Path ledger = directory.resolve("unknown-usage.json");
        var budget = new AutonomyTokenBudget(ledger, 200000);
        try (var fixture = new Fixture(ledger); var provider = fixture.provider(budget)) {
            for (String usage : List.of("absent", "null", "{}", "{\"prompt_tokens\":20}",
                    "{\"prompt_tokens\":-1,\"completion_tokens\":10}",
                    "{\"prompt_tokens\":1.5,\"completion_tokens\":10}",
                    "{\"prompt_tokens\":\"20\",\"completion_tokens\":10}",
                    "{\"prompt_tokens\":null,\"completion_tokens\":10}",
                    "{\"prompt_tokens\":\"PRIVATE_SECRET\",\"completion_tokens\":10}",
                    "{\"prompt_tokens\":2147483647,\"completion_tokens\":10}")) {
                long before = budget.status().usedTokens();
                fixture.body.set(response(usage));
                var reply = provider.decide(new JsonObject()).get(5, TimeUnit.SECONDS);
                assertNotNull(reply.decision(), "Invalid usage should not discard an otherwise valid decision");
                assertEquals(before + fixture.lastReservation.get(), budget.status().usedTokens(), usage);
            }
            assertEquals(10, fixture.requests.get());
            assertFalse(Files.readString(ledger).contains("PRIVATE_SECRET"));
        }
    }

    @Test void anotherBotCannotSendHttpWhenTheSharedRemainingBudgetIsInsufficient() throws Exception {
        Path ledger = directory.resolve("shared.json");
        var firstBudget = new AutonomyTokenBudget(ledger, 200000);
        try (var fixture = new Fixture(ledger); var first = fixture.provider(firstBudget)) {
            fixture.body.set(response("absent"));
            first.decide(new JsonObject()).get(5, TimeUnit.SECONDS);
            int requestCost = Math.toIntExact(fixture.lastReservation.get());
            firstBudget.reserve(Math.toIntExact(firstBudget.status().remainingTokens() - requestCost + 1));
            var secondBudget = new AutonomyTokenBudget(ledger, 200000);
            try (var second = fixture.provider(secondBudget)) {
                var failure = assertThrows(ExecutionException.class,
                        () -> second.decide(new JsonObject()).get(5, TimeUnit.SECONDS));
                var deferred = assertInstanceOf(AutonomyProvider.Deferred.class, failure.getCause());
                assertTrue(deferred.retryAt().isAfter(Instant.now()));
                assertEquals(1, fixture.requests.get());
                assertEquals(requestCost - 1, secondBudget.status().remainingTokens());
            }
        }
    }

    @Test void numericAndHttpDateRetryAfterPersistAcrossProvidersAndPreventEarlyHttp() throws Exception {
        Instant absolute = Instant.now().plusSeconds(240).truncatedTo(ChronoUnit.SECONDS);
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(absolute, ZoneOffset.UTC));
        for (String retryAfter : List.of("120", date)) {
            Path ledger = directory.resolve(retryAfter.equals("120") ? "numeric-429.json" : "date-429.json");
            var budget = new AutonomyTokenBudget(ledger, 200000);
            try (var fixture = new Fixture(ledger); var provider = fixture.provider(budget)) {
                fixture.status.set(429);
                fixture.retryAfter.set(retryAfter);
                fixture.body.set("PRIVATE_SECRET response body");
                Instant before = Instant.now();
                var failure = assertThrows(ExecutionException.class,
                        () -> provider.decide(new JsonObject()).get(5, TimeUnit.SECONDS));
                var deferred = assertInstanceOf(AutonomyProvider.Deferred.class, failure.getCause());
                assertEquals("provider_http_429", deferred.getMessage());
                if (retryAfter.equals("120")) {
                    assertFalse(deferred.retryAt().isBefore(before.plusSeconds(120)));
                    assertFalse(deferred.retryAt().isAfter(Instant.now().plusSeconds(120)));
                } else assertEquals(absolute, deferred.retryAt());
                assertEquals(deferred.retryAt().toEpochMilli(), budget.status().cooldownUntil().toEpochMilli());
                assertFalse(failure.toString().contains("PRIVATE_SECRET"));
                assertFalse(Files.readString(ledger).contains("PRIVATE_SECRET"));
                var reloaded = new AutonomyTokenBudget(ledger, 200000);
                try (var other = fixture.provider(reloaded)) {
                    var retry = assertThrows(ExecutionException.class,
                            () -> other.decide(new JsonObject()).get(5, TimeUnit.SECONDS));
                    assertInstanceOf(AutonomyProvider.Deferred.class, retry.getCause());
                    assertEquals(deferred.retryAt().toEpochMilli(), reloaded.status().cooldownUntil().toEpochMilli());
                    assertEquals(1, fixture.requests.get());
                }
            }
        }
    }

    @Test void enginePreservesProviderWallDeadlineBeyondTickBackoffAcrossReloadAndResumesAfterExpiry() throws Exception {
        Path ledger = directory.resolve("engine-429.json");
        List<AutonomyState.Event> events = new ArrayList<>();
        try (var fixture = new Fixture(ledger)) {
            fixture.status.set(429);
            fixture.retryAfter.set("600");
            fixture.body.set("PRIVATE_SECRET response body");
            JsonObject snapshot;
            try (var engine = new AutonomyEngine(SETTINGS,
                    fixture.provider(new AutonomyTokenBudget(ledger, 200000)), new Ports(), events::add)) {
                engine.start();
                awaitPhase(engine, AutonomyState.Phase.BACKOFF);
                snapshot = engine.snapshot();
                assertTrue(snapshot.get("providerRetryAtMillis").getAsLong() > System.currentTimeMillis() + 500000);
                assertTrue(engine.state().retryRemainingTicks() > SETTINGS.maxBackoffTicks());
                for (int tick = 0; tick < 10000; tick++) engine.tick();
                assertEquals(1, fixture.requests.get());
            }
            // Advance only the ledger clock; use an explicit expired snapshot deadline below instead
            // of sleeping ten minutes. Normal production clocks both use UTC wall time.
            var futureBudget = new AutonomyTokenBudget(ledger, 200000,
                    Clock.fixed(Instant.now().plusSeconds(1000), ZoneOffset.UTC));
            Ports ports = new Ports();
            try (var restored = new AutonomyEngine(SETTINGS, fixture.provider(futureBudget), ports, events::add)) {
                restored.restore(snapshot);
                for (int tick = 0; tick < 10000; tick++) restored.tick();
                assertEquals(snapshot.get("providerRetryAtMillis"), restored.snapshot().get("providerRetryAtMillis"));
                assertEquals(1, fixture.requests.get());
                fixture.status.set(200);
                fixture.body.set(response("{\"prompt_tokens\":20,\"completion_tokens\":10}"));
                snapshot.addProperty("providerRetryAtMillis", System.currentTimeMillis() - 1);
                restored.restore(snapshot);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (ports.starts == 0 && System.nanoTime() < deadline) {
                    restored.tick();
                    Thread.sleep(1);
                }
                assertEquals(1, ports.starts);
                assertEquals(2, fixture.requests.get());
                assertFalse(restored.snapshot().toString().contains("PRIVATE_SECRET"));
            }
            assertFalse(GSON.toJson(events).contains("PRIVATE_SECRET"));
        }
    }

    @Test void permanentlyOversizedReservationDefersEngineWithoutOverflowOrHttp() throws Exception {
        Path ledger = directory.resolve("impossible.json");
        try (var fixture = new Fixture(ledger); var engine = new AutonomyEngine(SETTINGS,
                fixture.provider(new AutonomyTokenBudget(ledger, 1)), new Ports(), event -> { })) {
            engine.start();
            awaitPhase(engine, AutonomyState.Phase.BACKOFF);
            assertEquals(Long.MAX_VALUE, engine.snapshot().get("providerRetryAtMillis").getAsLong());
            for (int tick = 0; tick < 10000; tick++) engine.tick();
            assertEquals(AutonomyState.Phase.BACKOFF, engine.state().phase());
            assertEquals(0, fixture.requests.get());
            var ports = new Ports();
            try (var recovered = new AutonomyEngine(SETTINGS,
                    fixture.provider(new AutonomyTokenBudget(ledger, 200000)), ports, event -> { })) {
                recovered.restore(engine.snapshot());
                assertEquals(0, recovered.snapshot().get("providerRetryAtMillis").getAsLong());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (ports.starts == 0 && System.nanoTime() < deadline) {
                    recovered.tick();
                    Thread.sleep(1);
                }
                assertEquals(1, ports.starts);
                assertEquals(1, fixture.requests.get());
            }
        }
    }

    private static void awaitPhase(AutonomyEngine engine, AutonomyState.Phase phase) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (engine.state().phase() != phase && System.nanoTime() < deadline) {
            engine.tick();
            Thread.sleep(1);
        }
        assertEquals(phase, engine.state().phase());
    }

    private static String response(String usage) {
        var decision = new AutonomyDecision(new AutonomyDecision.Intention("Observe", "Public purpose", "active"),
                new AutonomyDecision.Action("inspect", new JsonObject(), 1), null, "Model memory");
        JsonObject root = JsonParser.parseString("""
                {"choices":[{"finish_reason":"tool_calls","message":{"content":"PRIVATE_SECRET","reasoning_content":"PRIVATE_SECRET",
                "tool_calls":[{"type":"function","function":{"name":"decide","arguments":""}}]}}]}
                """).getAsJsonObject();
        root.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message")
                .getAsJsonArray("tool_calls").get(0).getAsJsonObject().getAsJsonObject("function")
                .addProperty("arguments", GSON.toJson(decision));
        if (!usage.equals("absent")) root.add("usage", JsonParser.parseString(usage));
        return root.toString();
    }

    private static final class Ports implements AutonomyEngine.Ports {
        int starts;
        @Override public JsonObject observe() { return new JsonObject(); }
        @Override public AutonomyState.Result start(AutonomyDecision.Action action) {
            starts++;
            return AutonomyState.Result.success("Done");
        }
        @Override public AutonomyState.Result poll() { return null; }
        @Override public void cancel() { }
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger status = new AtomicInteger(200);
        final AtomicReference<String> body = new AtomicReference<>(response("{\"prompt_tokens\":20,\"completion_tokens\":10}"));
        final AtomicReference<String> retryAfter = new AtomicReference<>();
        final AtomicLong lastReservation = new AtomicLong();
        final AtomicBoolean reservationWasDurable = new AtomicBoolean();

        Fixture(Path ledger) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                requests.incrementAndGet();
                var charges = JsonParser.parseString(Files.readString(ledger)).getAsJsonObject().getAsJsonArray("charges");
                JsonObject reservation = charges.get(charges.size() - 1).getAsJsonObject();
                lastReservation.set(reservation.get("reservedTokens").getAsLong());
                reservationWasDurable.set(!reservation.has("actualTokens") && lastReservation.get() > 0);
                exchange.getRequestBody().readAllBytes();
                if (retryAfter.get() != null) exchange.getResponseHeaders().set("Retry-After", retryAfter.get());
                byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status.get(), bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start();
        }

        AutonomyProvider provider(AutonomyTokenBudget budget) {
            return AutonomyProvider.openAi(new AutonomyProvider.Config("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-model", "test-api-key", 256, 5, "none", "low"), CAPABILITIES, budget);
        }

        @Override public void close() { server.stop(0); }
    }
}
