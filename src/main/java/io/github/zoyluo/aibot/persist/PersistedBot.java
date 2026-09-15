package io.github.zoyluo.aibot.persist;

public record PersistedBot(BotRecord bot, MissionRuntimeRecord missions,
                           com.google.gson.JsonObject autonomy) {
    public PersistedBot(BotRecord bot, MissionRuntimeRecord missions) {
        this(bot, missions, null);
    }

    public PersistedBot {
        missions = missions == null ? MissionRuntimeRecord.empty() : missions;
    }
}
