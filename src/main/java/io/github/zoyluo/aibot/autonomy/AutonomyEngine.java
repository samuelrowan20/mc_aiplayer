package io.github.zoyluo.aibot.autonomy;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static io.github.zoyluo.aibot.autonomy.AutonomyState.Phase;
import static io.github.zoyluo.aibot.autonomy.AutonomyState.Result;

/** Server-thread-owned lifecycle. Network completions are consumed only by tick(). */
public final class AutonomyEngine implements AutoCloseable {
    public interface Ports {
        JsonObject observe();
        Result start(AutonomyDecision.Action action);
        Result poll();
        void cancel();
    }

    private static final Gson GSON = new Gson();
    private final AutonomySettings settings;
    private final AutonomyProvider provider;
    private final Ports ports;
    private final Consumer<AutonomyState.Event> journal;
    private final ArrayDeque<AutonomyState.Event> episodes = new ArrayDeque<>();
    private final LinkedHashMap<String, Integer> failures = new LinkedHashMap<>();
    private Phase phase = Phase.STOPPED;
    private long tick, nextDecision, actionDeadline, waitDeadline, requestDeadlineNanos;
    private int providerFailures;
    private AutonomyDecision.Intention intention;
    private AutonomyDecision.Action action;
    private AutonomyDecision.Wait purposefulWait;
    private Result lastResult;
    private String memorySummary = "";
    private String failureEvidence = "";
    private JsonObject observation = new JsonObject();
    private CompletableFuture<AutonomyProvider.Reply> pending;

    public AutonomyEngine(AutonomySettings settings, AutonomyProvider provider, Ports ports,
                          Consumer<AutonomyState.Event> journal) {
        this.settings = settings;
        this.provider = provider;
        this.ports = ports;
        this.journal = journal;
    }

    public void start() {
        if (phase != Phase.STOPPED) return;
        phase = Phase.READY;
        nextDecision = tick;
        event("started", new JsonObject());
    }

    public void pause() {
        if (phase == Phase.STOPPED || phase == Phase.PAUSED) return;
        cancelWork("paused");
        phase = Phase.PAUSED;
        event("paused", new JsonObject());
    }

    public void resume() {
        if (phase != Phase.PAUSED) return;
        phase = Phase.READY;
        nextDecision = Math.max(tick, nextDecision);
        event("resumed", new JsonObject());
    }

    public void stop() {
        cancelWork("stopped");
        phase = Phase.STOPPED;
        event("stopped", new JsonObject());
    }

    public void died(String description) {
        if (phase == Phase.STOPPED) {
            lastResult = Result.failure("death", description);
            event("death", GSON.toJsonTree(lastResult).getAsJsonObject());
            return;
        }
        boolean paused = phase == Phase.PAUSED;
        cancelWork("death");
        phase = paused ? Phase.PAUSED : Phase.DEAD;
        lastResult = Result.failure("death", description);
        event("death", GSON.toJsonTree(lastResult).getAsJsonObject());
    }

    public void respawned() {
        if (phase == Phase.STOPPED) {
            event("respawned", new JsonObject());
            return;
        }
        if (phase != Phase.PAUSED) phase = Phase.READY;
        nextDecision = Math.max(tick, nextDecision);
        event("respawned", new JsonObject());
    }

    public void interrupt(String reason) {
        if (phase == Phase.STOPPED || phase == Phase.PAUSED || phase == Phase.DEAD) return;
        cancelWork(reason);
        phase = Phase.READY;
        event("interrupted", GSON.toJsonTree(lastResult).getAsJsonObject());
    }

    /** Factual updates during a bounded network request must not repeatedly cancel that request. */
    public void notice(String description) {
        if (phase == Phase.STOPPED || phase == Phase.PAUSED) return;
        JsonObject details = new JsonObject();
        details.addProperty("description", AutonomyState.bounded(description, 600));
        event("environment_change", details);
    }

    public void tick() {
        tick++;
        if (phase == Phase.STOPPED || phase == Phase.PAUSED || phase == Phase.DEAD) return;
        try {
            if (phase == Phase.DECIDING) {
                if (pending.isDone()) {
                    var response = pending.join();
                    pending = null;
                    JsonObject metrics = new JsonObject();
                    metrics.addProperty("latency_ms", response.latencyMillis());
                    metrics.addProperty("prompt_tokens", response.promptTokens());
                    metrics.addProperty("completion_tokens", response.completionTokens());
                    event("decision_received", metrics);
                    apply(response.decision());
                    providerFailures = 0;
                } else if (System.nanoTime() >= requestDeadlineNanos) {
                    pending.cancel(true);
                    pending = null;
                    providerFailed("provider_timeout");
                }
            } else if (phase == Phase.ACTING) {
                Result result = ports.poll();
                if (result != null) complete(result);
                else if (tick >= actionDeadline) {
                    ports.cancel();
                    complete(Result.failure("action_timeout", "Bounded action or maximum decision interval expired"));
                }
            } else if (phase == Phase.WAITING && tick >= waitDeadline) {
                purposefulWait = null;
                complete(Result.success("Purposeful wait ended"));
            }
            if ((phase == Phase.READY || phase == Phase.BACKOFF) && tick >= nextDecision) decide();
        } catch (RuntimeException exception) {
            if (phase == Phase.DECIDING || pending != null) {
                invalidateRequest();
                // Error types only: remote response bodies may contain private reasoning or credentials.
                Throwable failure = rootCause(exception);
                String message = failure.getMessage();
                providerFailed(message != null && message.matches("provider_http_[0-9]{3}")
                        ? message : "provider_failure:" + failure.getClass().getSimpleName());
            } else {
                ports.cancel();
                complete(Result.failure("mechanical_exception", exception.getClass().getSimpleName()));
            }
        }
    }

    private void decide() {
        observation = ports.observe().deepCopy();
        if (observation.toString().length() > 48000) throw new IllegalStateException("Observation exceeds bound");
        JsonObject evidence = new JsonObject();
        for (String key : List.of("inventory", "visible_blocks", "current_screen", "dimension")) {
            if (observation.has(key)) evidence.add(key, observation.get(key));
        }
        // Quantize position to blocks so idle coordinate jitter cannot reset a failure budget.
        if (observation.has("position") && observation.get("position").isJsonObject()) {
            JsonObject position = new JsonObject();
            for (String axis : List.of("x", "y", "z")) {
                if (observation.getAsJsonObject("position").has(axis)) {
                    position.addProperty(axis, Math.floor(observation.getAsJsonObject("position").get(axis).getAsDouble()));
                }
            }
            evidence.add("position", position);
        }
        String observedEvidence = digest(canonical(evidence));
        if (!observedEvidence.equals(failureEvidence)) {
            failures.clear();
            failureEvidence = observedEvidence;
        }
        JsonObject context = new JsonObject();
        context.add("observation", observation);
        context.add("working_state", GSON.toJsonTree(state()));
        phase = Phase.DECIDING;
        nextDecision = tick + settings.minDecisionTicks();
        requestDeadlineNanos = System.nanoTime() + settings.providerTimeoutSeconds() * 1_000_000_000L;
        pending = provider.decide(context);
        if (pending == null) throw new IllegalStateException("Missing provider future");
    }

    private void apply(AutonomyDecision decision) {
        if (decision == null) throw new IllegalArgumentException("Missing decision");
        if (decision.memorySummary() != null) {
            memorySummary = AutonomyState.bounded(decision.memorySummary(), settings.maxSummaryChars());
        }
        if (!decision.intention().equals(intention)) {
            intention = decision.intention();
            event("intention", GSON.toJsonTree(intention).getAsJsonObject());
        }
        if (decision.deliberateWait() != null) {
            purposefulWait = new AutonomyDecision.Wait(decision.deliberateWait().reason(),
                    Math.min(decision.deliberateWait().ticks(), Math.min(settings.maxWaitTicks(), settings.maxDecisionTicks())));
            waitDeadline = tick + purposefulWait.ticks();
            phase = Phase.WAITING;
            event("purposeful_wait", GSON.toJsonTree(purposefulWait).getAsJsonObject());
            return;
        }
        action = new AutonomyDecision.Action(decision.action().name(), decision.action().arguments(),
                Math.min(decision.action().maxTicks(), Math.min(settings.maxActionTicks(), settings.maxDecisionTicks())));
        if (failures.getOrDefault(signature(action), 0) >= settings.repeatedFailureLimit()) {
            complete(Result.failure("repeated_failure_suppressed",
                    "This equivalent primitive already failed repeatedly. Select a different operation or deliberate wait."));
            return;
        }
        actionDeadline = tick + action.maxTicks();
        phase = Phase.ACTING;
        event("action", GSON.toJsonTree(action).getAsJsonObject());
        Result result = ports.start(action);
        if (result != null) complete(result);
    }

    private void complete(Result result) {
        lastResult = result;
        JsonObject details = GSON.toJsonTree(result).getAsJsonObject();
        if (action != null) {
            details.add("action", GSON.toJsonTree(action));
            String signature = signature(action);
            if (result.ok()) failures.remove(signature);
            else if (!result.code().equals("repeated_failure_suppressed")) {
                failures.put(signature, Math.min(settings.repeatedFailureLimit(), failures.getOrDefault(signature, 0) + 1));
                while (failures.size() > 32) failures.remove(failures.keySet().iterator().next());
            }
        }
        event("result", details);
        action = null;
        purposefulWait = null;
        phase = Phase.READY;
        nextDecision = Math.max(nextDecision, tick + settings.minDecisionTicks());
    }

    private void providerFailed(String code) {
        providerFailures = Math.min(30, providerFailures + 1);
        long delay = Math.min(settings.maxBackoffTicks(),
                (long) settings.initialBackoffTicks() << (providerFailures - 1));
        nextDecision = tick + delay;
        phase = Phase.BACKOFF;
        JsonObject details = new JsonObject();
        details.addProperty("code", code);
        details.addProperty("retry_ticks", delay);
        event("provider_error", details);
    }

    private void invalidateRequest() {
        if (pending != null) pending.cancel(true);
        pending = null;
    }

    private void cancelWork(String reason) {
        invalidateRequest();
        ports.cancel();
        if (action != null || purposefulWait != null) {
            lastResult = Result.failure("cancelled", reason);
            JsonObject details = GSON.toJsonTree(lastResult).getAsJsonObject();
            if (action != null) details.add("action", GSON.toJsonTree(action));
            event("cancelled", details);
        } else lastResult = Result.failure("interruption", reason);
        action = null;
        purposefulWait = null;
    }

    private void event(String type, JsonObject details) {
        for (String key : List.of("position", "health", "hunger", "game_time", "dimension")) {
            if (observation.has(key)) details.add(key, observation.get(key).deepCopy());
        }
        var event = new AutonomyState.Event(Instant.now().toString(), tick, type, details.deepCopy());
        episodes.addLast(event);
        while (episodes.size() > settings.maxEpisodes()) episodes.removeFirst();
        journal.accept(event);
    }

    public AutonomyState state() {
        return new AutonomyState(phase, tick, intention, action, purposefulWait,
                purposefulWait == null ? 0 : Math.max(0, waitDeadline - tick), lastResult, memorySummary,
                List.copyOf(episodes), failures.entrySet().stream()
                        .map(entry -> new AutonomyState.Failure(entry.getKey(), entry.getValue())).toList(),
                failureEvidence, providerFailures, Math.max(0, nextDecision - tick));
    }

    public JsonObject snapshot() { return GSON.toJsonTree(state()).getAsJsonObject(); }

    public void restore(JsonObject snapshot) {
        if (snapshot == null || snapshot.toString().length() > 512000) return;
        AutonomyState saved = GSON.fromJson(snapshot, AutonomyState.class);
        if (saved == null || saved.phase() == null) return;
        cancelWork("reload");
        tick = Math.max(0, saved.tick());
        intention = saved.intention();
        lastResult = saved.lastResult();
        memorySummary = AutonomyState.bounded(saved.memorySummary(), settings.maxSummaryChars());
        episodes.clear();
        if (saved.episodes() != null) {
            for (var event : saved.episodes()) {
                if (event != null) episodes.addLast(event);
                while (episodes.size() > settings.maxEpisodes()) episodes.removeFirst();
            }
        }
        failures.clear();
        if (saved.failures() != null) {
            for (var failure : saved.failures()) {
                if (failure != null && failure.signature() != null && failures.size() < 32) {
                    failures.put(failure.signature(), Math.min(settings.repeatedFailureLimit(), Math.max(0, failure.count())));
                }
            }
        }
        providerFailures = Math.max(0, Math.min(30, saved.providerFailures()));
        failureEvidence = AutonomyState.bounded(saved.failureEvidence(), 64);
        nextDecision = tick + Math.max(0, Math.min(settings.maxBackoffTicks(), saved.retryRemainingTicks()));
        phase = switch (saved.phase()) {
            case STOPPED -> Phase.STOPPED;
            case PAUSED -> Phase.PAUSED;
            case BACKOFF -> Phase.BACKOFF;
            default -> Phase.READY;
        };
        if (saved.phase() == Phase.WAITING && saved.purposefulWait() != null && saved.waitRemainingTicks() > 0) {
            purposefulWait = new AutonomyDecision.Wait(saved.purposefulWait().reason(),
                    (int) Math.min(saved.waitRemainingTicks(), Math.min(settings.maxWaitTicks(), settings.maxDecisionTicks())));
            waitDeadline = tick + purposefulWait.ticks();
            phase = Phase.WAITING;
        }
        // Physical jobs and in-flight HTTP are never replayed after reload. Preserve outcome uncertainty
        // explicitly, rather than presenting a previous successful action as the interrupted job's result.
        if (saved.phase() == Phase.ACTING || saved.phase() == Phase.DECIDING) {
            lastResult = Result.failure("reload_interrupted", saved.phase() == Phase.ACTING
                    ? "Saved physical action was interrupted by reload; its final outcome is unknown."
                    : "Saved provider request was interrupted by reload; no returned decision was applied.");
            JsonObject details = GSON.toJsonTree(lastResult).getAsJsonObject();
            if (saved.action() != null) details.add("action", GSON.toJsonTree(saved.action()));
            event("reload_interrupted", details);
        }
        event("restored", new JsonObject());
    }

    private static String signature(AutonomyDecision.Action action) {
        return digest(action.name() + canonical(action.arguments()));
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String canonical(JsonElement value) {
        if (!value.isJsonObject()) {
            if (value.isJsonArray()) {
                List<String> array = new ArrayList<>();
                value.getAsJsonArray().forEach(item -> array.add(canonical(item)));
                return array.toString();
            }
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                return value.getAsBigDecimal().stripTrailingZeros().toPlainString();
            }
            return value.toString();
        }
        Map<String, String> sorted = new java.util.TreeMap<>();
        value.getAsJsonObject().entrySet().forEach(entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));
        return sorted.toString();
    }

    private static Throwable rootCause(Throwable exception) {
        while (exception.getCause() != null && exception.getCause() != exception) exception = exception.getCause();
        return exception;
    }

    @Override public void close() {
        invalidateRequest();
        ports.cancel();
        provider.close();
    }
}
