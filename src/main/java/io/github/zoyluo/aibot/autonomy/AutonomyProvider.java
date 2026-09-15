package io.github.zoyluo.aibot.autonomy;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** Same chat-completions/function-call protocol as the manual brain, without strategic tools/history. */
public interface AutonomyProvider extends AutoCloseable {
    String DIRECTIVE = "Survive independently. Your only requirement is not to idle without purpose. "
            + "Progress is a fluid goal but must always be the target. What constitutes progress is yours to determine.";

    record Reply(AutonomyDecision decision, long latencyMillis, int promptTokens, int completionTokens) { }

    record Config(String baseUrl, String model, String apiKey, int maxTokens,
                  int timeoutSeconds, String reasoningMode, String reasoningEffort, String decisionFormat) {
        public Config(String baseUrl, String model, String apiKey, int maxTokens,
                      int timeoutSeconds, String reasoningMode, String reasoningEffort) {
            this(baseUrl, model, apiKey, maxTokens, timeoutSeconds, reasoningMode, reasoningEffort, "tool_call");
        }

        public Config {
            URI uri = URI.create(baseUrl);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || model == null || model.isBlank() || maxTokens < 1 || maxTokens > 131072
                    || timeoutSeconds < 1 || timeoutSeconds > 600
                    || !Set.of("none", "openai", "deepseek").contains(reasoningMode)
                    || !Set.of("tool_call", "json_schema").contains(decisionFormat)) {
                throw new IllegalArgumentException("Invalid autonomy provider configuration");
            }
            apiKey = apiKey == null ? "" : apiKey;
            reasoningEffort = reasoningEffort == null ? "low" : reasoningEffort;
        }
    }

    CompletableFuture<Reply> decide(JsonObject context);
    default String budgetStatus() { return "unlimited"; }
    @Override default void close() { }

    final class Deferred extends RuntimeException {
        private final Instant retryAt;
        public Deferred(String code, Instant retryAt) { super(code); this.retryAt = retryAt; }
        public Instant retryAt() { return retryAt; }
    }

    /** Definitions contain name, description and parameters (JSON schema), supplied by the mechanical registry. */
    static AutonomyProvider openAi(Config config, JsonArray capabilities) {
        return openAi(config, capabilities, null);
    }

    static AutonomyProvider openAi(Config config, JsonArray capabilities, AutonomyTokenBudget budget) {
        return new OpenAi(config, capabilities, budget);
    }

    final class OpenAi implements AutonomyProvider {
        private static final Gson GSON = new Gson();
        private final Config config;
        private final JsonObject tool;
        private final String prompt;
        private final HttpClient client;
        private final AutonomyTokenBudget budget;
        private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "AIBotProvider");
            thread.setDaemon(true);
            return thread;
        });
        private final AtomicReference<CompletableFuture<?>> active = new AtomicReference<>();
        private volatile boolean closed;

        private OpenAi(Config config, JsonArray capabilities, AutonomyTokenBudget budget) {
            this.config = config;
            this.budget = budget;
            this.tool = decisionTool(capabilities);
            this.prompt = DIRECTIVE + "\nYou choose intentions and priorities; intentions can persist across actions. "
                    + (config.decisionFormat().equals("json_schema")
                        ? "Return one JSON decision matching the supplied response schema to continue, revise, abandon, complete, defer or replace your intention, "
                        : "Use decide once to continue, revise, abandon, complete, defer or replace your intention, ")
                    + "and select exactly one physical action or deliberateWait with a reason and bounded ticks. "
                    + "20 ticks equal one second. Failed actions did not complete. Observations are local and partial; "
                    + "omitted counts indicate withheld entries, not absent objects. working_state holds current intention, "
                    + "latest result, recent events and summarized memory. Update memorySummary concisely, retaining needed memories. "
                    + "Supply only a concise public purpose, never private reasoning.";
            this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        }

        @Override public CompletableFuture<Reply> decide(JsonObject context) {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("provider_closed"));
            if (context.toString().length() > 512000) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("context_too_large"));
            }
            CompletableFuture<?> existing = active.get();
            if (existing != null && !existing.isDone()) {
                return CompletableFuture.failedFuture(new IllegalStateException("provider_busy"));
            }
            JsonObject body = new JsonObject();
            body.addProperty("model", config.model());
            body.addProperty("max_tokens", config.maxTokens());
            body.addProperty("stream", false);
            if (config.reasoningMode().equals("openai")) body.addProperty("reasoning_effort", config.reasoningEffort());
            if (config.reasoningMode().equals("deepseek")) {
                JsonObject thinking = new JsonObject();
                thinking.addProperty("type", "enabled");
                body.add("thinking", thinking);
                body.addProperty("reasoning_effort", config.reasoningEffort());
            }
            JsonArray messages = new JsonArray();
            for (String role : List.of("system", "user")) {
                JsonObject message = new JsonObject();
                message.addProperty("role", role);
                message.addProperty("content", role.equals("system") ? prompt : context.toString());
                messages.add(message);
            }
            body.add("messages", messages);
            if ("api.groq.com".equalsIgnoreCase(URI.create(config.baseUrl()).getHost())) {
                body.addProperty("reasoning_format", "hidden");
                body.addProperty("parallel_tool_calls", false);
            }
            if (config.decisionFormat().equals("json_schema")) {
                JsonObject format = new JsonObject();
                format.addProperty("type", "json_schema");
                JsonObject schema = new JsonObject();
                schema.addProperty("name", "decision");
                schema.addProperty("strict", true);
                // Complete alternatives also work with grammar-based providers that discard root
                // sibling properties when compiling a oneOf schema.
                JsonObject decisionSchema = new JsonObject();
                JsonArray alternatives = new JsonArray();
                for (String selected : List.of("action", "deliberateWait")) {
                    JsonObject branch = tool.getAsJsonObject("function").getAsJsonObject("parameters").deepCopy();
                    branch.remove("oneOf");
                    branch.getAsJsonObject("properties").remove(selected.equals("action") ? "deliberateWait" : "action");
                    JsonArray required = new JsonArray();
                    required.add("intention");
                    required.add(selected);
                    branch.add("required", required);
                    alternatives.add(branch);
                }
                decisionSchema.add("oneOf", alternatives);
                schema.add("schema", decisionSchema);
                format.add("json_schema", schema);
                body.add("response_format", format);
            } else {
                JsonArray tools = new JsonArray();
                tools.add(tool);
                body.add("tools", tools);
                JsonObject choice = new JsonObject();
                choice.addProperty("type", "function");
                JsonObject function = new JsonObject();
                function.addProperty("name", "decide");
                choice.add("function", function);
                body.add("tool_choice", choice);
            }
            String base = config.baseUrl().replaceAll("/+$", "");
            String endpoint = base.endsWith("/chat/completions") ? base : base + "/chat/completions";
            var builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
            if (!config.apiKey().isBlank()) builder.header("Authorization", "Bearer " + config.apiKey());
            var reply = new CompletableFuture<Reply>();
            if (!active.compareAndSet(existing, reply)) {
                return CompletableFuture.failedFuture(new IllegalStateException("provider_busy"));
            }
            AtomicReference<CompletableFuture<?>> transport = new AtomicReference<>();
            reply.orTimeout(config.timeoutSeconds(), TimeUnit.SECONDS);
            reply.whenComplete((result, failure) -> {
                if (reply.isCancelled() || failure instanceof TimeoutException) {
                    synchronized (transport) {
                        var request = transport.get();
                        if (request != null) request.cancel(true);
                    }
                }
            });
            // Reserve durable quota off the server thread before any network request can start.
            // Byte count overestimates byte-tokenized prompts; include all JSON/schema bytes,
            // the maximum output, and a framing allowance. Unknown usage never refunds quota.
            int reservationTokens = Math.addExact(body.toString().getBytes(StandardCharsets.UTF_8).length,
                    config.maxTokens() + 512);
            long started = System.nanoTime();
            try {
                worker.execute(() -> {
                    try {
                        if (reply.isDone()) return;
                        var reservation = budget == null ? null : budget.reserve(reservationTokens,
                                "api.groq.com".equalsIgnoreCase(URI.create(config.baseUrl()).getHost()) ? 60000 : 0);
                        CompletableFuture<HttpResponse<String>> request;
                        synchronized (transport) {
                            if (reply.isDone() || closed) return;
                            request = client.sendAsync(builder.build(), responseInfo -> new BoundedBody());
                            transport.set(request);
                        }
                        request.whenCompleteAsync((response, failure) -> {
                            try {
                                if (failure != null) { reply.completeExceptionally(failure); return; }
                                if (response.statusCode() == 429) {
                                    Instant retryAt = retryAt(response);
                                    if (budget != null) {
                                        if ("api.groq.com".equalsIgnoreCase(URI.create(config.baseUrl()).getHost())
                                                && rejectedBeforeInference(response.body())) budget.settle(reservation, 0);
                                        try { budget.deferUntil(retryAt); }
                                        catch (IOException storageFailure) {
                                            throw new Deferred("provider_http_429_budget_storage_failure", retryAt);
                                        }
                                    }
                                    throw new Deferred("provider_http_429", retryAt);
                                }
                                if (response.statusCode() != 200) {
                                    throw new IllegalStateException("provider_http_" + response.statusCode());
                                }
                                if (budget != null) {
                                    // Charge successful HTTP responses even if their decision is invalid.
                                    JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                                    JsonObject usage = root.has("usage") && root.get("usage").isJsonObject()
                                            ? root.getAsJsonObject("usage") : null;
                                    int actual = -1;
                                    if (usage != null && usage.has("prompt_tokens") && usage.has("completion_tokens")) {
                                        try {
                                            var promptValue = usage.get("prompt_tokens");
                                            var completionValue = usage.get("completion_tokens");
                                            if (promptValue.isJsonPrimitive() && promptValue.getAsJsonPrimitive().isNumber()
                                                    && completionValue.isJsonPrimitive() && completionValue.getAsJsonPrimitive().isNumber()) {
                                                int prompt = promptValue.getAsBigDecimal().intValueExact();
                                                int completion = completionValue.getAsBigDecimal().intValueExact();
                                                if (prompt >= 0 && completion >= 0) actual = Math.addExact(prompt, completion);
                                            }
                                        } catch (RuntimeException invalidUsage) { /* Retain the reservation. */ }
                                    }
                                    budget.settle(reservation, actual);
                                }
                                reply.complete(parse(response.body(), (System.nanoTime() - started) / 1_000_000,
                                        config.decisionFormat()));
                            } catch (Exception exception) {
                                reply.completeExceptionally(exception);
                            }
                        }, worker);
                    } catch (AutonomyTokenBudget.BudgetUnavailableException exception) {
                        reply.completeExceptionally(new Deferred(exception.code(), exception.nextEligibleAt()));
                    } catch (IOException exception) {
                        reply.completeExceptionally(new IllegalStateException("token_budget_storage_failure"));
                    } catch (RuntimeException exception) {
                        reply.completeExceptionally(exception);
                    }
                });
            } catch (RuntimeException exception) {
                reply.completeExceptionally(exception);
            }
            return reply;
        }

        private static Instant retryAt(HttpResponse<?> response) {
            Instant now = Instant.now();
            String value = response.headers().firstValue("retry-after").orElse("");
            try { return now.plusMillis(Math.max(1000, (long) (Double.parseDouble(value) * 1000))); }
            catch (RuntimeException ignored) {
                try { return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().isAfter(now)
                        ? ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() : now.plusSeconds(1); }
                catch (RuntimeException invalid) { return now.plusSeconds(60); }
            }
        }

        @Override public String budgetStatus() {
            if (budget == null) return "unlimited";
            var status = budget.cachedStatus();
            return status == null ? "not_checked" : status.remainingTokens() + "/" + status.limitTokens() + " tokens remaining";
        }

        @Override public void close() {
            closed = true;
            var request = active.getAndSet(null);
            if (request != null) request.cancel(true);
            client.shutdownNow();
            worker.shutdownNow();
        }

        static Reply parse(String text, long latencyMillis) {
            return parse(text, latencyMillis, "tool_call");
        }

        static Reply parse(String text, long latencyMillis, String decisionFormat) {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.size() != 1) throw new IllegalArgumentException("Expected one decision");
            JsonObject choice = choices.get(0).getAsJsonObject();
            if (choice.has("finish_reason") && "length".equals(choice.get("finish_reason").getAsString())) {
                throw new IllegalArgumentException("Truncated decision");
            }
            JsonObject message = choice.getAsJsonObject("message");
            String arguments;
            if (decisionFormat.equals("json_schema")) {
                var content = message.get("content");
                if (content == null || !content.isJsonPrimitive() || !content.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("Expected JSON decision content");
                }
                arguments = content.getAsString();
            } else {
                JsonArray calls = message.getAsJsonArray("tool_calls");
                if (calls == null || calls.size() != 1) throw new IllegalArgumentException("Expected one tool call");
                JsonObject function = calls.get(0).getAsJsonObject().getAsJsonObject("function");
                if (!"decide".equals(function.get("name").getAsString())) throw new IllegalArgumentException("Unknown tool");
                arguments = function.get("arguments").getAsString();
            }
            if (arguments.length() > 24000) throw new IllegalArgumentException("Decision exceeds bound");
            AutonomyDecision decision;
            if (decisionFormat.equals("json_schema")) {
                try (JsonReader reader = new JsonReader(new StringReader(arguments))) {
                    decision = GSON.getAdapter(AutonomyDecision.class).read(reader);
                    if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Expected one JSON decision");
                } catch (IOException exception) {
                    throw new IllegalArgumentException("Malformed JSON decision", exception);
                }
            } else decision = GSON.fromJson(arguments, AutonomyDecision.class);
            if (decision == null) throw new IllegalArgumentException("Missing decision");
            JsonObject usage = root.has("usage") && root.get("usage").isJsonObject()
                    ? root.getAsJsonObject("usage") : new JsonObject();
            return new Reply(decision, latencyMillis, tokens(usage, "prompt_tokens"), tokens(usage, "completion_tokens"));
        }

        private static int tokens(JsonObject usage, String key) {
            try {
                if (usage.has(key) && usage.get(key).isJsonPrimitive()
                        && usage.getAsJsonPrimitive(key).isNumber()) {
                    return Math.max(0, usage.get(key).getAsBigDecimal().intValueExact());
                }
            } catch (RuntimeException ignored) {
                // Usage metadata must not discard an otherwise valid decision.
            }
            return 0;
        }

        static boolean rejectedBeforeInference(String body) {
            try {
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                if (root.has("choices") || root.has("usage")) return false;
                JsonObject error = root.getAsJsonObject("error");
                return error != null && error.has("code") && error.has("type")
                        && "rate_limit_exceeded".equals(error.get("code").getAsString())
                        && Set.of("tokens", "requests").contains(error.get("type").getAsString());
            } catch (RuntimeException malformed) {
                return false;
            }
        }

        private static JsonObject decisionTool(JsonArray capabilities) {
            JsonObject schema = JsonParser.parseString("""
                    {"type":"object","additionalProperties":false,"required":["intention"],"properties":{
                      "intention":{"type":"object","additionalProperties":false,"required":["text","purpose","status"],"properties":{
                        "text":{"type":"string","maxLength":600},"purpose":{"type":"string","maxLength":600},
                        "status":{"type":"string","enum":["active","revised","abandoned","completed","deferred"]}}},
                      "action":{"oneOf":[]},
                      "deliberateWait":{"type":"object","additionalProperties":false,"required":["reason","ticks"],"properties":{
                        "reason":{"type":"string","maxLength":600},"ticks":{"type":"integer","minimum":1,"maximum":1200}}},
                      "memorySummary":{"type":"string","maxLength":4000}},
                      "oneOf":[{"required":["action"],"not":{"required":["deliberateWait"]}},
                               {"required":["deliberateWait"],"not":{"required":["action"]}}]}
                    """).getAsJsonObject();
            JsonArray variants = schema.getAsJsonObject("properties").getAsJsonObject("action").getAsJsonArray("oneOf");
            for (var element : capabilities) {
                JsonObject capability = element.getAsJsonObject();
                JsonObject variant = JsonParser.parseString("""
                        {"type":"object","additionalProperties":false,"required":["name","arguments","maxTicks"],"properties":{
                        "name":{"type":"string","enum":[]},"arguments":{},
                        "maxTicks":{"type":"integer","minimum":1,"maximum":600}}}
                        """).getAsJsonObject();
                JsonObject properties = variant.getAsJsonObject("properties");
                variant.addProperty("description", capability.get("description").getAsString());
                properties.getAsJsonObject("name").getAsJsonArray("enum").add(capability.get("name").getAsString());
                properties.add("arguments", capability.get("parameters").deepCopy());
                variants.add(variant);
            }
            JsonObject function = new JsonObject();
            function.addProperty("name", "decide");
            function.addProperty("description", "Declare your intention and one bounded physical action or purposeful wait.");
            function.add("parameters", schema);
            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            tool.add("function", function);
            return tool;
        }
    }

    /** Bounds allocation while bytes arrive, including unsuccessful HTTP response bodies. */
    final class BoundedBody implements HttpResponse.BodySubscriber<String> {
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<String> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > 262144) {
                    subscription.cancel();
                    body.completeExceptionally(new IllegalArgumentException("Provider response exceeds 256 KiB"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { body.completeExceptionally(failure); }
        @Override public void onComplete() { body.complete(bytes.toString(StandardCharsets.UTF_8)); }
    }
}
