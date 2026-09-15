package io.github.zoyluo.aibot.autonomy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Ordered, bounded asynchronous journal. Filesystem failure never blocks the server tick. */
public final class AutonomyJournal implements Consumer<AutonomyState.Event>, AutoCloseable {
    private static final Gson GSON = new Gson();
    private final Path jsonl, readable;
    private final long maxBytes;
    private final int backups;
    private final ThreadPoolExecutor writer;
    private final AtomicLong dropped = new AtomicLong();
    private long reportedDropped;
    private volatile String lastError = "";

    public AutonomyJournal(Path directory, String botName, AutonomySettings settings) {
        if (!botName.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid journal bot name");
        jsonl = directory.resolve(botName + ".jsonl");
        readable = directory.resolve(botName + ".log");
        maxBytes = settings.journalMaxBytes();
        backups = settings.journalBackups();
        writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256), runnable -> {
            Thread thread = new Thread(runnable, "aibot-autonomy-journal-" + botName);
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override public void accept(AutonomyState.Event event) {
        // Capture immutable serialized content before crossing threads. No model response bodies are accepted here.
        String json = GSON.toJson(event) + "\n";
        String text = event.timestamp() + " tick=" + event.tick() + " " + event.type() + " "
                + event.details().toString() + "\n";
        if (json.getBytes(StandardCharsets.UTF_8).length > Math.min(24000, maxBytes)
                || text.getBytes(StandardCharsets.UTF_8).length > Math.min(24000, maxBytes)) {
            dropped.incrementAndGet();
            lastError = "journal_event_exceeds_bound";
            return;
        }
        try {
            writer.execute(() -> {
                try {
                    Files.createDirectories(jsonl.toAbsolutePath().getParent());
                    long lost = dropped.get();
                    if (lost > reportedDropped) {
                        JsonObject gap = new JsonObject();
                        gap.addProperty("type", "journal_gap");
                        gap.addProperty("dropped_events", lost - reportedDropped);
                        append(jsonl, gap + "\n");
                        append(readable, "journal_gap dropped_events=" + (lost - reportedDropped) + "\n");
                        reportedDropped = lost;
                    }
                    append(jsonl, json);
                    append(readable, text);
                    lastError = "";
                } catch (IOException exception) {
                    dropped.incrementAndGet();
                    lastError = "journal_io_error:" + exception.getClass().getSimpleName();
                }
            });
        } catch (RejectedExecutionException exception) {
            dropped.incrementAndGet();
            lastError = "journal_queue_full_or_closed";
        }
    }

    private void append(Path path, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (Files.exists(path) && Files.size(path) + bytes.length > maxBytes) {
            for (int index = backups; index >= 1; index--) {
                Path source = index == 1 ? path : path.resolveSibling(path.getFileName() + "." + (index - 1));
                Path destination = path.resolveSibling(path.getFileName() + "." + index);
                if (Files.exists(source)) Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public long droppedEvents() { return dropped.get(); }
    public String lastError() { return lastError; }
    public Path jsonlPath() { return jsonl; }
    public Path readablePath() { return readable; }

    /** Completes after all entries enqueued before this call. Intended for saves/tests off the tick thread. */
    public CompletableFuture<Void> flush() {
        CompletableFuture<Void> flushed = new CompletableFuture<>();
        try {
            writer.execute(() -> flushed.complete(null));
        } catch (RejectedExecutionException exception) {
            flushed.completeExceptionally(exception);
        }
        return flushed;
    }

    @Override public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                dropped.addAndGet(writer.shutdownNow().size());
                lastError = "journal_shutdown_timeout";
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            dropped.addAndGet(writer.shutdownNow().size());
            lastError = "journal_shutdown_interrupted";
        }
    }
}
