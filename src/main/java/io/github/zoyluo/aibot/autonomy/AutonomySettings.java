package io.github.zoyluo.aibot.autonomy;

/** Mechanical bounds only; none of these settings rank Minecraft objectives. */
public record AutonomySettings(int minDecisionTicks, int maxDecisionTicks, int maxActionTicks,
                               int maxWaitTicks, int providerTimeoutSeconds, int initialBackoffTicks,
                               int maxBackoffTicks, int repeatedFailureLimit, int maxEpisodes,
                               int maxSummaryChars, long journalMaxBytes, int journalBackups) {
    public AutonomySettings {
        if (minDecisionTicks < 1 || maxDecisionTicks < minDecisionTicks || maxActionTicks < 1
                || maxWaitTicks < 1 || providerTimeoutSeconds < 1 || initialBackoffTicks < 1
                || maxBackoffTicks < initialBackoffTicks || repeatedFailureLimit < 1
                || maxEpisodes < 1 || maxEpisodes > 128 || maxSummaryChars < 1 || maxSummaryChars > 16000
                || journalMaxBytes < 4096 || journalBackups < 1 || journalBackups > 32) {
            throw new IllegalArgumentException("Invalid autonomy bounds");
        }
    }

    public static AutonomySettings defaults() {
        return new AutonomySettings(40, 1200, 600, 1200, 90, 100, 6000, 3, 32, 4000,
                16L * 1024 * 1024, 8);
    }
}
