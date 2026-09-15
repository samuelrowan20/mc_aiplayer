package io.github.zoyluo.aibot.autonomy;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

final class AutonomyProviderTest {
    private static final Gson GSON = new Gson();
    private static final JsonArray CAPABILITIES = JsonParser.parseString("""
            [{"name":"inspect","description":"Inspect perceptible state","parameters":{"type":"object","properties":{},"additionalProperties":false}}]
            """).getAsJsonArray();

    @Test void compatibleHttpRequestUsesStructuredDecisionAndOmitsProviderSpecificReasoningByDefault() throws Exception {
        AtomicReference<JsonObject> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = response().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try (AutonomyProvider provider = AutonomyProvider.openAi(new AutonomyProvider.Config(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/", "compatible-model", "test-key", 1000, 5, "none", "low"), CAPABILITIES)) {
            AutonomyProvider.Reply reply = provider.decide(new JsonObject()).get(10, TimeUnit.SECONDS);
            assertEquals("inspect", reply.decision().action().name());
            assertEquals(20, reply.promptTokens());
            assertEquals(10, reply.completionTokens());
            assertTrue(reply.latencyMillis() >= 0);
            assertEquals("Bearer test-key", authorization.get());
            assertFalse(requestBody.get().has("thinking"));
            assertFalse(requestBody.get().has("reasoning_effort"));
            assertEquals("decide", requestBody.get().getAsJsonObject("tool_choice").getAsJsonObject("function").get("name").getAsString());
            String system = requestBody.get().getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
            assertTrue(system.startsWith(AutonomyProvider.DIRECTIVE));
            assertFalse(system.contains("GoalPlanner"));
        } finally { server.stop(0); }
    }

    @Test void ignoresPrivateReasoningAndRejectsMissingOrMultipleDecisionCalls() {
        var parsed = AutonomyProvider.OpenAi.parse(response(), 12);
        assertFalse(GSON.toJson(parsed).contains("PRIVATE_SECRET"));
        JsonObject invalid = JsonParser.parseString(response()).getAsJsonObject();
        var message = invalid.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
        JsonArray calls = message.getAsJsonArray("tool_calls");
        calls.add(calls.get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> AutonomyProvider.OpenAi.parse(invalid.toString(), 0));
        message.remove("tool_calls");
        assertThrows(IllegalArgumentException.class, () -> AutonomyProvider.OpenAi.parse(invalid.toString(), 0));
    }

    @Test void malformedOrTruncatedDecisionsCannotBecomeActions() {
        JsonObject root = JsonParser.parseString(response()).getAsJsonObject();
        JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
        choice.addProperty("finish_reason", "length");
        assertThrows(IllegalArgumentException.class, () -> AutonomyProvider.OpenAi.parse(root.toString(), 0));
        choice.addProperty("finish_reason", "tool_calls");
        choice.getAsJsonObject("message").getAsJsonArray("tool_calls").get(0).getAsJsonObject()
                .getAsJsonObject("function").addProperty("arguments", "{}");
        assertThrows(RuntimeException.class, () -> AutonomyProvider.OpenAi.parse(root.toString(), 0));
    }

    @Test void capsResponseAllocationAndCancelsPublisher() {
        AutonomyProvider.BoundedBody body = new AutonomyProvider.BoundedBody();
        AtomicBoolean cancelled = new AtomicBoolean();
        body.onSubscribe(new Flow.Subscription() {
            @Override public void request(long n) { }
            @Override public void cancel() { cancelled.set(true); }
        });
        body.onNext(List.of(ByteBuffer.wrap(new byte[200000])));
        body.onNext(List.of(ByteBuffer.wrap(new byte[100000])));
        assertTrue(cancelled.get());
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
    }

    @Test void httpErrorsDoNotExposeResponseBodyAndProviderCanRetry() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] bytes = "PRIVATE_SECRET".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try (AutonomyProvider provider = AutonomyProvider.openAi(new AutonomyProvider.Config(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "model", "", 1000, 5, "none", "low"), CAPABILITIES)) {
            for (int attempt = 0; attempt < 2; attempt++) {
                var error = assertThrows(ExecutionException.class, () -> provider.decide(new JsonObject()).get(5, TimeUnit.SECONDS));
                assertEquals("provider_http_429", error.getCause().getMessage());
                assertFalse(error.toString().contains("PRIVATE_SECRET"));
            }
        } finally { server.stop(0); }
    }

    @Test void stalledResponseBodyTimesOutAndCannotBlockTheNextRequestForever() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.sendResponseHeaders(200, 64);
            try (var output = exchange.getResponseBody()) {
                output.write('{');
                output.flush();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
        });
        server.start();
        try (AutonomyProvider provider = AutonomyProvider.openAi(new AutonomyProvider.Config(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "model", "", 1000, 1, "none", "low"), CAPABILITIES)) {
            var pending = provider.decide(new JsonObject());
            var busy = assertThrows(ExecutionException.class, () -> provider.decide(new JsonObject()).get(1, TimeUnit.SECONDS));
            assertEquals("provider_busy", busy.getCause().getMessage());
            assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
            assertTrue(pending.isDone());
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test void preservesCustomEndpointPrefixesAndUsesConfiguredReasoningWireFormat() throws Exception {
        AtomicReference<JsonObject> request = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            request.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            byte[] bytes = response().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            for (String mode : List.of("openai", "deepseek")) {
                String suffix = mode.equals("openai") ? "/proxy/models" : "/proxy/models/chat/completions";
                try (AutonomyProvider provider = AutonomyProvider.openAi(new AutonomyProvider.Config(
                        "http://127.0.0.1:" + server.getAddress().getPort() + suffix,
                        "model", "", 1000, 5, mode, "high"), CAPABILITIES)) {
                    provider.decide(new JsonObject()).get(5, TimeUnit.SECONDS);
                    assertEquals("/proxy/models/chat/completions", path.get());
                    assertEquals("high", request.get().get("reasoning_effort").getAsString());
                    if (mode.equals("deepseek")) {
                        assertEquals("enabled", request.get().getAsJsonObject("thinking").get("type").getAsString());
                        assertFalse(request.get().getAsJsonObject("thinking").has("reasoning_effort"));
                    } else assertFalse(request.get().has("thinking"));
                }
            }
        } finally { server.stop(0); }
    }

    private static String response() {
        AutonomyDecision decision = new AutonomyDecision(
                new AutonomyDecision.Intention("Observe", "Public purpose", "active"),
                new AutonomyDecision.Action("inspect", new JsonObject(), 1), null, "Relevant memory");
        JsonObject root = JsonParser.parseString("""
                {"choices":[{"finish_reason":"tool_calls","message":{"content":"PRIVATE_SECRET", "reasoning_content":"PRIVATE_SECRET", "tool_calls":[
                {"type":"function","function":{"name":"decide","arguments":""}}]}}],"usage":{"prompt_tokens":20,"completion_tokens":10}}
                """).getAsJsonObject();
        root.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message")
                .getAsJsonArray("tool_calls").get(0).getAsJsonObject().getAsJsonObject("function")
                .addProperty("arguments", GSON.toJson(decision));
        return root.toString();
    }
}
