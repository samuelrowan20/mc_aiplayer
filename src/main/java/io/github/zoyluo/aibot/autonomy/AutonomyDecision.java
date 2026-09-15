package io.github.zoyluo.aibot.autonomy;

import com.google.gson.JsonObject;

/** Explicit public intent and one bounded physical operation; never private reasoning. */
public record AutonomyDecision(Intention intention, Action action, Wait deliberateWait, String memorySummary) {
    public record Intention(String text, String purpose, String status) {
        public Intention {
            text = required(text, 600, "intention");
            purpose = required(purpose, 600, "purpose");
            status = required(status, 32, "status");
            if (!java.util.Set.of("active", "revised", "abandoned", "completed", "deferred").contains(status)) {
                throw new IllegalArgumentException("Invalid intention status");
            }
        }
    }

    public record Action(String name, JsonObject arguments, int maxTicks) {
        public Action {
            name = required(name, 64, "action name");
            if (arguments == null || arguments.toString().length() > 4096 || maxTicks < 1) {
                throw new IllegalArgumentException("Invalid action arguments or duration");
            }
            arguments = arguments.deepCopy();
        }
    }

    public record Wait(String reason, int ticks) {
        public Wait {
            reason = required(reason, 600, "wait reason");
            if (ticks < 1) throw new IllegalArgumentException("Wait must be bounded");
        }
    }

    public AutonomyDecision {
        if (intention == null || (action == null) == (deliberateWait == null)) {
            throw new IllegalArgumentException("Supply an intention and exactly one action or wait");
        }
        if (memorySummary != null && memorySummary.length() > 16000) {
            throw new IllegalArgumentException("Memory summary too long");
        }
    }

    static String required(String text, int limit, String field) {
        if (text == null || text.isBlank() || text.length() > limit) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return text;
    }
}
