package io.github.zoyluo.aibot.autonomy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

final class AutonomyJournalTest {
    @TempDir Path directory;

    @Test void durableOrderedJsonAndReadableLogsRotateWithinRetentionBound() throws Exception {
        var settings = new AutonomySettings(2, 20, 20, 20, 10, 4, 32, 3, 12, 100, 4096, 2);
        Path jsonl;
        try (var journal = new AutonomyJournal(directory, "test-bot", settings)) {
            jsonl = journal.jsonlPath();
            for (int index = 0; index < 80; index++) {
                JsonObject details = new JsonObject();
                details.addProperty("purpose", "Model purpose " + "x".repeat(400));
                journal.accept(new AutonomyState.Event("2026-09-15T00:00:00Z", index, "intention", details));
                if (index % 5 == 0) journal.flush().get(5, TimeUnit.SECONDS);
            }
            journal.flush().get(5, TimeUnit.SECONDS);
            assertEquals(0, journal.droppedEvents());
            assertEquals("", journal.lastError());
        }
        try (var files = Files.list(directory)) {
            var paths = files.toList();
            assertEquals(6, paths.size());
            for (Path path : paths) assertTrue(Files.size(path) <= 4096, path.toString());
        }
        long previous = -1;
        for (String line : Files.readAllLines(jsonl)) {
            JsonObject event = JsonParser.parseString(line).getAsJsonObject();
            long tick = event.get("tick").getAsLong();
            assertTrue(tick > previous);
            previous = tick;
        }
        assertEquals(79, previous);
        assertTrue(Files.readString(directory.resolve("test-bot.log")).contains("Model purpose"));
    }

    @Test void diskErrorsAreObservableWithoutThrowingIntoTickThread() throws Exception {
        Path file = directory.resolve("not-a-directory");
        Files.writeString(file, "occupied");
        try (var journal = new AutonomyJournal(file, "Bob", AutonomySettings.defaults())) {
            journal.accept(new AutonomyState.Event("now", 1, "action", new JsonObject()));
            journal.flush().get(5, TimeUnit.SECONDS);
            assertEquals(1, journal.droppedEvents());
            assertTrue(journal.lastError().startsWith("journal_io_error"));
        }
    }

    @Test void rejectsPathTraversalNamesAndOversizeRecords() {
        assertThrows(IllegalArgumentException.class,
                () -> new AutonomyJournal(directory, "../escape", AutonomySettings.defaults()));
        try (var journal = new AutonomyJournal(directory, "Bob", AutonomySettings.defaults())) {
            JsonObject details = new JsonObject();
            details.addProperty("purpose", "x".repeat(30000));
            journal.accept(new AutonomyState.Event("now", 1, "intention", details));
            assertEquals(1, journal.droppedEvents());
            assertEquals("journal_event_exceeds_bound", journal.lastError());
        }
    }
}
