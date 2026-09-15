package io.github.zoyluo.aibot.autonomy;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

final class AutonomyTokenBudgetTest {
    @TempDir Path directory;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-15T23:59:59Z"));

    @Test void exactLimitIsDurablyReservedAndOverLimitIsDenied() throws Exception {
        Path path = directory.resolve("groq-tokens.json");
        var budget = new AutonomyTokenBudget(path, 200000, clock);
        assertNull(budget.cachedStatus());
        var reservation = budget.reserve(200000);
        assertTrue(Files.readString(path).contains(reservation.id()));
        assertEquals(200000, budget.cachedStatus().usedTokens());
        assertEquals(0, budget.cachedStatus().remainingTokens());
        var denied = assertThrows(AutonomyTokenBudget.BudgetUnavailableException.class, () -> budget.reserve(1));
        assertEquals(clock.instant().plus(Duration.ofHours(24)).plusSeconds(600), denied.nextEligibleAt());
        assertEquals(200000, budget.status().usedTokens());
        assertThrows(IllegalArgumentException.class, () -> new AutonomyTokenBudget(path, 200001, clock));
        var impossible = assertThrows(AutonomyTokenBudget.BudgetUnavailableException.class, () -> budget.reserve(200001));
        assertEquals(Instant.MAX, impossible.nextEligibleAt());
    }

    @Test void concurrentBotsAndIndependentInstancesCannotDoubleSpendRemainingBudget() throws Exception {
        Path path = directory.resolve("shared.json");
        var first = new AutonomyTokenBudget(path, 100, clock);
        var second = new AutonomyTokenBudget(path, 100, clock);
        try (var workers = Executors.newFixedThreadPool(8)) {
            var reservations = new ArrayList<CompletableFuture<Boolean>>();
            for (int index = 0; index < 40; index++) {
                var budget = index % 2 == 0 ? first : second;
                reservations.add(CompletableFuture.supplyAsync(() -> {
                    try { budget.reserve(10); return true; }
                    catch (AutonomyTokenBudget.BudgetUnavailableException exhausted) { return false; }
                    catch (IOException storage) { throw new UncheckedIOException(storage); }
                }, workers));
            }
            CompletableFuture.allOf(reservations.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
            assertEquals(10, reservations.stream().filter(CompletableFuture::join).count());
        }
        assertEquals(100, first.status().usedTokens());
        assertEquals(100, second.status().usedTokens());
        assertEquals(10, JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonArray("charges").size());
    }

    @Test void actualUsageRefundsUnusedTokensAndSettlementIsIdempotent() throws Exception {
        var budget = new AutonomyTokenBudget(directory.resolve("settled.json"), 100, clock);
        var first = budget.reserve(80);
        clock.advance(Duration.ofMinutes(5));
        Instant completed = clock.instant();
        budget.settle(first, 30);
        assertEquals(30, budget.status().usedTokens());
        assertEquals(70, budget.cachedStatus().remainingTokens());
        clock.advance(Duration.ofHours(1));
        budget.settle(first, 30);
        budget.reserve(70);
        assertEquals(completed.plus(Duration.ofHours(24)), budget.status().nextEligibleAt());
        assertThrows(IOException.class, () -> budget.settle(first, 20));
        assertEquals(100, budget.status().usedTokens());
    }

    @Test void unknownUsageRetainsFullReservationAndOverrunChargesActualUsage() throws Exception {
        Path path = directory.resolve("uncertain.json");
        var budget = new AutonomyTokenBudget(path, 100, clock);
        var reservation = budget.reserve(80);
        budget.settle(reservation, -1);
        assertEquals(80, budget.status().usedTokens());
        clock.advance(Duration.ofMinutes(2));
        budget.settle(reservation, 120);
        assertEquals(120, budget.status().usedTokens());
        assertEquals(0, budget.status().remainingTokens());
        assertEquals(120, new AutonomyTokenBudget(path, 100, clock).status().usedTokens());
        var denied = assertThrows(AutonomyTokenBudget.BudgetUnavailableException.class, () -> budget.reserve(1));
        assertEquals(clock.instant().plus(Duration.ofHours(24)), denied.nextEligibleAt());
    }

    @Test void midnightAndRestartDoNotResetReservationsAndPendingHoldsMaximumRequestDuration() throws Exception {
        Path path = directory.resolve("restart.json");
        var budget = new AutonomyTokenBudget(path, 100, clock);
        Instant started = clock.instant();
        budget.reserve(100);
        clock.advance(Duration.ofSeconds(2));
        budget = new AutonomyTokenBudget(path, 100, clock);
        assertEquals(100, budget.status().usedTokens());
        clock.set(started.plus(Duration.ofHours(24)));
        assertEquals(100, budget.status().usedTokens());
        clock.advance(Duration.ofSeconds(599));
        assertEquals(100, budget.status().usedTokens());
        clock.advance(Duration.ofSeconds(1));
        assertEquals(0, budget.status().usedTokens());
        budget.reserve(100);
        assertEquals(100, new AutonomyTokenBudget(path, 100, clock).status().usedTokens());
    }

    @Test void settledUsageExpiresTwentyFourHoursAfterCompletionAndLateCompletionStillCharges() throws Exception {
        Path path = directory.resolve("expiry.json");
        var budget = new AutonomyTokenBudget(path, 100, clock);
        Instant started = clock.instant();
        var first = budget.reserve(100);
        clock.advance(Duration.ofMinutes(9));
        budget.settle(first, 100);
        clock.set(started.plus(Duration.ofHours(24)).plus(Duration.ofMinutes(8)));
        assertEquals(100, budget.status().usedTokens());
        clock.advance(Duration.ofMinutes(1));
        assertEquals(0, budget.status().usedTokens());
        var pending = budget.reserve(100);
        clock.advance(Duration.ofHours(24).plusMinutes(10));
        budget.reserve(100);
        budget.settle(pending, 40);
        assertEquals(140, budget.status().usedTokens());
        assertThrows(AutonomyTokenBudget.BudgetUnavailableException.class, () -> budget.reserve(1));
    }

    @Test void sharedProviderCooldownIsDurableAndCannotBeShortened() throws Exception {
        Path path = directory.resolve("cooldown.json");
        var first = new AutonomyTokenBudget(path, 100, clock);
        Instant until = clock.instant().plusSeconds(600);
        first.deferUntil(until);
        var second = new AutonomyTokenBudget(path, 100, clock);
        second.deferUntil(clock.instant().plusSeconds(30));
        assertEquals(until, second.cachedStatus().cooldownUntil());
        assertEquals(0, second.status().usedTokens());
        var denied = assertThrows(AutonomyTokenBudget.BudgetUnavailableException.class, () -> second.reserve(10));
        assertEquals(until, denied.nextEligibleAt());
        clock.advance(Duration.ofMinutes(10));
        first.reserve(10);
        assertEquals(10, first.status().usedTokens());
    }

    @Test void malformedAndInvalidLedgersFailClosedWithoutOverwritingEvidence() throws Exception {
        Path path = directory.resolve("bad.json");
        var budget = new AutonomyTokenBudget(path, 100, clock);
        for (String corrupt : new String[]{"", "null", "{}", "{broken}", "{\"version\":2,\"charges\":[]}",
                "{\"version\":1,\"charges\":[]} {}"}) {
            Files.writeString(path, corrupt);
            assertThrows(IOException.class, () -> budget.reserve(10));
            assertThrows(IOException.class, budget::status);
            assertEquals(corrupt, Files.readString(path));
        }
        Files.delete(path);
        budget.reserve(10);
        var data = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        data.getAsJsonArray("charges").get(0).getAsJsonObject().addProperty("reservedTokens", -100);
        Files.writeString(path, data.toString());
        assertThrows(IOException.class, () -> new AutonomyTokenBudget(path, 100, clock).reserve(10));
    }

    @Test void unavailableStorageCannotAdmitRequestsOrEraseExistingCharges() throws Exception {
        Path path = directory.resolve("locked.json");
        var budget = new AutonomyTokenBudget(path, 100, clock);
        budget.reserve(60);
        String original = Files.readString(path);
        Path lock = path.resolveSibling(path.getFileName() + ".lock");
        Files.delete(lock);
        Files.createDirectory(lock);
        assertThrows(IOException.class, () -> budget.reserve(10));
        assertEquals(original, Files.readString(path));
        Path invalidParent = directory.resolve("file-as-parent");
        Files.writeString(invalidParent, "unavailable");
        var unavailable = new AutonomyTokenBudget(invalidParent.resolve("ledger.json"), 100, clock);
        assertThrows(IOException.class, () -> unavailable.reserve(10));
    }

    @Test void zeroUsageReleasesItsReservationWithoutAccumulatingEmptyCharges() throws Exception {
        Path path = directory.resolve("zero.json");
        var budget = new AutonomyTokenBudget(path, 100, clock);
        var reservation = budget.reserve(100);
        budget.settle(reservation, 0);
        budget.settle(reservation, 0);
        assertEquals(0, budget.status().usedTokens());
        assertEquals(0, JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonArray("charges").size());
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        void set(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
