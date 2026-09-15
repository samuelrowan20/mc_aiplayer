package io.github.zoyluo.aibot.autonomy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.AIBotMod;
import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.mode.OperatingProfile;
import io.github.zoyluo.aibot.persist.BotPersistence;
import io.github.zoyluo.aibot.runtime.IntentController;
import io.github.zoyluo.aibot.task.TaskManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-thread owner of autonomous bots. Stopped/paused bots remain isolated from legacy policy. */
public final class AutonomyCoordinator {
    public static final AutonomyCoordinator INSTANCE = new AutonomyCoordinator();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private AutonomySettings settings = AutonomySettings.defaults();
    private AutonomyProvider.Config providerConfig;
    private Path journalRoot;

    private AutonomyCoordinator() { }

    public void configure(MinecraftServer server, AIBotConfig config) {
        shutdown();
        JsonObject options = GSON.toJsonTree(AutonomySettings.defaults()).getAsJsonObject();
        options.addProperty("reasoningMode", "none");
        Path path = FabricLoader.getInstance().getConfigDir().resolve("aibot-autonomy.json");
        try {
            if (Files.exists(path)) {
                if (Files.size(path) > 32768) throw new IOException("autonomy config exceeds 32 KiB");
                try (var reader = Files.newBufferedReader(path)) {
                    JsonParser.parseReader(reader).getAsJsonObject().entrySet()
                            .forEach(entry -> options.add(entry.getKey(), entry.getValue()));
                }
            } else {
                Files.createDirectories(path.getParent());
                Files.writeString(path, GSON.toJson(options));
            }
            settings = GSON.fromJson(options, AutonomySettings.class);
            var llm = config.deepseek();
            providerConfig = new AutonomyProvider.Config(llm.baseUrl(), llm.model(), llm.apiKey(),
                    llm.maxTokens(), settings.providerTimeoutSeconds(),
                    options.get("reasoningMode").getAsString(), llm.reasoningEffort());
        } catch (IOException | RuntimeException exception) {
            providerConfig = null;
            AIBotMod.LOGGER.error("Autonomy configuration invalid; fix aibot-autonomy.json and restart ({})",
                    exception.getClass().getSimpleName());
        }
        journalRoot = server.getSavePath(WorldSavePath.ROOT).resolve("aibot/autonomy");
    }

    public boolean owns(AIPlayerEntity bot) { return sessions.containsKey(bot.getUuid()); }

    public void start(AIPlayerEntity bot) {
        start(bot, null);
    }

    void start(AIPlayerEntity bot, AutonomyProvider provider) {
        requireStrict();
        Session session = sessions.get(bot.getUuid());
        if (session == null) {
            clearLegacy(bot);
            session = new Session(bot, provider);
            sessions.put(bot.getUuid(), session);
        }
        session.engine.start();
        dirty(bot);
    }

    public void pause(AIPlayerEntity bot) {
        Session session = sessions.get(bot.getUuid());
        if (session != null) { session.engine.pause(); dirty(bot); }
    }

    public void resume(AIPlayerEntity bot) {
        requireStrict();
        Session session = sessions.get(bot.getUuid());
        if (session == null) throw new IllegalStateException("Use /aibot autonomy start first");
        session.engine.resume();
        dirty(bot);
    }

    public void stop(AIPlayerEntity bot) {
        Session session = sessions.get(bot.getUuid());
        if (session != null && session.engine.state().phase() != AutonomyState.Phase.STOPPED) {
            session.engine.stop();
            dirty(bot);
        }
    }

    /** Explicit switch back to legacy assigned-goal mode; stop alone is a quiescent kill switch. */
    public void manual(AIPlayerEntity bot) {
        remove(bot);
        clearLegacy(bot);
        dirty(bot);
    }

    public void remove(AIPlayerEntity bot) {
        Session removed = sessions.remove(bot.getUuid());
        if (removed != null) removed.close();
    }

    public String status(AIPlayerEntity bot) {
        Session session = sessions.get(bot.getUuid());
        if (session == null) return "manual mode";
        var state = session.engine.state();
        return "autonomy=" + state.phase() + ", intention=" +
                (state.intention() == null ? "none" : state.intention().text()) +
                ", action=" + (state.action() == null ? "none" : state.action().name()) +
                ", wait=" + (state.purposefulWait() == null ? "none" : state.purposefulWait().reason()) +
                ", provider_failures=" + state.providerFailures() +
                ", journal_dropped=" + session.journal.droppedEvents() +
                ", journal_error=" + session.journal.lastError();
    }

    public void tick(AIPlayerEntity bot) {
        Session session = sessions.get(bot.getUuid());
        if (session == null) return;
        if (AIBotConfig.get().profile() != OperatingProfile.STRICT_SURVIVAL) {
            session.engine.pause();
            return;
        }
        if (!bot.isAlive()) {
            if (session.deadTicks++ == 0) {
                session.engine.died(bot.getRecentDamageSource() == null ? "unknown" :
                        bot.getRecentDamageSource().getName());
                dirty(bot);
            }
            if (session.deadTicks >= 20 && session.deadTicks % 20 == 0) {
                try {
                    session.bot = AIPlayerManager.INSTANCE.respawnVanilla(bot);
                    session.embodiment.cancel();
                    session.engine.respawned();
                    session.deadTicks = 0;
                    session.lastHealth = session.bot.getHealth();
                    dirty(session.bot);
                } catch (RuntimeException exception) {
                    // Keep ownership and retry the mechanical lifecycle; never enter legacy recovery.
                    if (session.deadTicks % 1200 == 20) AIBotMod.LOGGER.error(
                            "Autonomy respawn failed for {} ({})", bot.getGameProfile().getName(),
                            exception.getClass().getSimpleName());
                }
            }
            return;
        }
        // Damage is an observed interruption, with no deterministic choice of response.
        if (bot.getHealth() < session.lastHealth) {
            if (session.engine.state().phase() == AutonomyState.Phase.DECIDING) {
                session.engine.notice("health_decreased");
            } else session.engine.interrupt("health_decreased");
        }
        session.lastHealth = bot.getHealth();
        session.engine.tick();
        if (bot.getServer().getTicks() % 1200 == 0) dirty(bot);
    }

    public JsonObject snapshot(AIPlayerEntity bot) {
        Session session = sessions.get(bot.getUuid());
        if (session == null) return null;
        JsonObject snapshot = session.engine.snapshot();
        snapshot.add("embodiment", session.embodiment.snapshot());
        snapshot.addProperty("wasDead", !session.bot.isAlive());
        return snapshot;
    }

    public void restore(AIPlayerEntity bot, JsonObject snapshot) {
        remove(bot);
        clearLegacy(bot);
        Session session = create(bot);
        sessions.put(bot.getUuid(), session);
        try {
            session.engine.restore(snapshot);
            if (snapshot.has("embodiment")) session.embodiment.restore(snapshot.getAsJsonObject("embodiment"));
            if (snapshot.has("wasDead") && snapshot.get("wasDead").getAsBoolean()) bot.setHealth(0);
        } catch (RuntimeException invalidSnapshot) {
            session.engine.stop();
            AIBotMod.LOGGER.error("Invalid autonomy state for {}; stopped", bot.getGameProfile().getName());
        }
        if (AIBotConfig.get().profile() != OperatingProfile.STRICT_SURVIVAL || providerConfig == null) {
            session.engine.pause();
        }
    }

    public void shutdown() {
        sessions.values().forEach(Session::close);
        sessions.clear();
    }

    private Session create(AIPlayerEntity bot) {
        return new Session(bot, null);
    }

    private void requireStrict() {
        if (AIBotConfig.get().profile() != OperatingProfile.STRICT_SURVIVAL)
            throw new IllegalStateException("Autonomy requires profile strict_survival; change config and restart");
        if (providerConfig == null) throw new IllegalStateException("Autonomy configuration is invalid; restart after fixing it");
    }

    private static void clearLegacy(AIPlayerEntity bot) {
        IntentController.INSTANCE.cancelAll(bot, IntentController.ControlOrigin.SYSTEM, "autonomy_control");
        BrainCoordinator.INSTANCE.reset(bot);
        TaskManager.INSTANCE.resetToIdle(bot);
        bot.getActionPack().stopAll();
    }

    private static void dirty(AIPlayerEntity bot) { BotPersistence.INSTANCE.markDirty(bot.getServer()); }

    private final class Session implements AutoCloseable {
        AIPlayerEntity bot;
        final AutonomyEmbodiment embodiment;
        final AutonomyEngine engine;
        final AutonomyJournal journal;
        int deadTicks;
        float lastHealth;

        Session(AIPlayerEntity bot, AutonomyProvider override) {
            this.bot = bot;
            lastHealth = bot.getHealth();
            embodiment = new AutonomyEmbodiment(() -> this.bot);
            journal = new AutonomyJournal(journalRoot, bot.getUuid().toString(), settings);
            AutonomyProvider provider = override != null ? override : providerConfig == null
                    ? context -> java.util.concurrent.CompletableFuture.failedFuture(
                            new IllegalStateException("invalid_provider_configuration"))
                    : AutonomyProvider.openAi(providerConfig, AutonomyEmbodiment.capabilities());
            engine = new AutonomyEngine(settings, provider, embodiment, event -> {
                JsonObject details = event.details().deepCopy();
                details.addProperty("bot", this.bot.getGameProfile().getName());
                details.addProperty("uuid", this.bot.getUuid().toString());
                details.addProperty("game_time", this.bot.getServerWorld().getTime());
                details.addProperty("dimension", this.bot.getServerWorld().getRegistryKey().getValue().toString());
                details.add("position", AutonomyObservation.position(this.bot.getPos()));
                details.addProperty("health", this.bot.getHealth());
                details.addProperty("hunger", this.bot.getHungerManager().getFoodLevel());
                journal.accept(new AutonomyState.Event(event.timestamp(), event.tick(), event.type(), details));
            });
        }

        @Override public void close() {
            engine.close();
            journal.close();
        }
    }
}
