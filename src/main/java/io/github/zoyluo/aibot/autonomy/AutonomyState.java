package io.github.zoyluo.aibot.autonomy;

import com.google.gson.JsonObject;
import java.util.List;

/** Immutable view, also the bounded persistence representation. All ticks are engine-local. */
public record AutonomyState(Phase phase, long tick, AutonomyDecision.Intention intention,
                            AutonomyDecision.Action action, AutonomyDecision.Wait purposefulWait,
                            long waitRemainingTicks, Result lastResult, String memorySummary,
                            List<Event> episodes, List<Failure> failures, String failureEvidence, int providerFailures,
                            long retryRemainingTicks) {
    public enum Phase { STOPPED, PAUSED, READY, DECIDING, ACTING, WAITING, BACKOFF, DEAD }
    public record Result(boolean ok, String code, String message) {
        public Result {
            code = bounded(code, 100);
            message = bounded(message, 1200);
        }
        public static Result success(String message) { return new Result(true, "success", message); }
        public static Result failure(String code, String message) { return new Result(false, code, message); }
    }
    public record Failure(String signature, int count) { }
    public record Event(String timestamp, long tick, String type, JsonObject details) { }
    static String bounded(String value, int limit) {
        return value == null ? "" : value.substring(0, Math.min(limit, value.length()));
    }
}
