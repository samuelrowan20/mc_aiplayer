package io.github.zoyluo.aibot.autonomy;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.github.zoyluo.aibot.persist.AtomicSnapshotFile;
import java.io.IOException;
import java.io.StringReader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Durable provider-wide admission control. All disk operations belong on a provider worker. */
public final class AutonomyTokenBudget {
    private static final Gson GSON = new Gson();
    private static final long WINDOW_MILLIS = Duration.ofHours(24).toMillis();
    private static final long PENDING_WINDOW_MILLIS = WINDOW_MILLIS + Duration.ofSeconds(600).toMillis();
    private static final long MAX_FILE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ENTRIES = 20000;
    private static final ConcurrentHashMap<Path, Object> LOCKS = new ConcurrentHashMap<>();

    public record Reservation(String id, long startedAtMillis, int reservedTokens) {
        public Reservation {
            if (id == null || !id.matches("[0-9a-fA-F-]{36}") || startedAtMillis < 0
                    || startedAtMillis > Long.MAX_VALUE - PENDING_WINDOW_MILLIS || reservedTokens < 1) {
                throw new IllegalArgumentException("Invalid reservation");
            }
        }
    }
    public record Status(int limitTokens, long usedTokens, long remainingTokens,
                         Instant nextEligibleAt, Instant cooldownUntil) { }

    public static final class BudgetUnavailableException extends IOException {
        private final String code;
        private final Instant nextEligibleAt;

        private BudgetUnavailableException(String code, Instant nextEligibleAt) {
            super(code);
            this.code = code;
            this.nextEligibleAt = nextEligibleAt;
        }

        public String code() { return code; }
        public Instant nextEligibleAt() { return nextEligibleAt; }
    }

    private record Charge(String id, long startedAtMillis, Long completedAtMillis,
                          int reservedTokens, Integer actualTokens) {
        long tokens() { return actualTokens == null ? reservedTokens : actualTokens; }
        long expiresAtMillis() {
            return completedAtMillis == null ? startedAtMillis + PENDING_WINDOW_MILLIS
                    : completedAtMillis + WINDOW_MILLIS;
        }
    }

    private record Ledger(int version, List<Charge> charges, Long cooldownUntilMillis) { }
    @FunctionalInterface private interface Operation<T> { T apply(Ledger ledger, long now) throws IOException; }

    private final Path path;
    private final int limit;
    private final Clock clock;
    private volatile Status cachedStatus;

    public AutonomyTokenBudget(Path path, int dailyLimit) {
        this(path, dailyLimit, Clock.systemUTC());
    }

    public AutonomyTokenBudget(Path path, int dailyLimit, Clock clock) {
        if (dailyLimit < 1 || dailyLimit > 200000) throw new IllegalArgumentException("Token limit must be 1..200000");
        this.path = path.toAbsolutePath().normalize();
        this.limit = dailyLimit;
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    /** A successful return means this reservation reached durable storage before HTTP may begin. */
    public Reservation reserve(int worstCaseTokens) throws IOException {
        return reserve(worstCaseTokens, 0);
    }

    /** Persist request spacing in the same transaction as admission, shared by all bots. */
    public Reservation reserve(int worstCaseTokens, long minimumIntervalMillis) throws IOException {
        if (worstCaseTokens < 1) throw new IllegalArgumentException("Reservation must be positive");
        if (minimumIntervalMillis < 0 || minimumIntervalMillis > 600000)
            throw new IllegalArgumentException("Request interval must be 0..600000 milliseconds");
        return locked((ledger, now) -> {
            if (worstCaseTokens > limit) {
                throw new BudgetUnavailableException("reservation_exceeds_daily_limit", Instant.MAX);
            }
            Instant eligible = nextEligible(ledger, now, worstCaseTokens);
            if (eligible.isAfter(Instant.ofEpochMilli(now))) {
                throw new BudgetUnavailableException("daily_token_budget_or_provider_cooldown", eligible);
            }
            if (ledger.charges().size() >= MAX_ENTRIES) {
                long expiry = ledger.charges().stream().mapToLong(Charge::expiresAtMillis).min().orElse(now);
                throw new BudgetUnavailableException("token_ledger_capacity", Instant.ofEpochMilli(expiry));
            }
            Reservation reservation = new Reservation(UUID.randomUUID().toString(), now, worstCaseTokens);
            ledger.charges().add(new Charge(reservation.id(), now, null, worstCaseTokens, null));
            write(minimumIntervalMillis == 0 ? ledger : new Ledger(1, ledger.charges(),
                    Math.max(ledger.cooldownUntilMillis() == null ? now : ledger.cooldownUntilMillis(),
                            now + minimumIntervalMillis)), now);
            return reservation;
        });
    }

    /** Negative usage means uncertain usage: retain the full pending reservation and its conservative expiry. */
    public void settle(Reservation reservation, int actualTokens) throws IOException {
        if (reservation == null || reservation.id() == null || reservation.reservedTokens() < 1
                || reservation.startedAtMillis() < 0) throw new IllegalArgumentException("Invalid reservation");
        if (actualTokens < 0) return;
        locked((ledger, now) -> {
            Charge existing = ledger.charges().stream().filter(charge -> charge.id().equals(reservation.id()))
                    .findFirst().orElse(null);
            if (existing != null) {
                if (existing.startedAtMillis() != reservation.startedAtMillis()
                        || existing.reservedTokens() != reservation.reservedTokens()) {
                    throw new IOException("Reservation does not match ledger");
                }
                if (existing.actualTokens() != null) {
                    if (existing.actualTokens() != actualTokens) throw new IOException("Conflicting usage settlement");
                    return null;
                }
                ledger.charges().remove(existing);
            }
            // A late completion is charged even if its original pending reservation already expired.
            if (actualTokens > 0) {
                if (ledger.charges().size() >= MAX_ENTRIES) throw new IOException("Token ledger capacity exceeded");
                ledger.charges().add(new Charge(reservation.id(), reservation.startedAtMillis(), now,
                        reservation.reservedTokens(), actualTokens));
            }
            write(ledger, now);
            return null;
        });
    }

    /** Persist a provider-wide Retry-After deadline; a shorter response cannot release a longer cooldown. */
    public void deferUntil(Instant deadline) throws IOException {
        java.util.Objects.requireNonNull(deadline);
        locked((ledger, now) -> {
            long existing = ledger.cooldownUntilMillis() == null ? now : ledger.cooldownUntilMillis();
            long until;
            try { until = deadline.toEpochMilli(); }
            catch (ArithmeticException invalid) { throw new IllegalArgumentException("Cooldown deadline exceeds range", invalid); }
            if (until > existing) write(new Ledger(1, ledger.charges(), until), now);
            return null;
        });
    }

    /** Refresh from disk under the same locks used for admission. Never call on the Minecraft tick thread. */
    public Status status() throws IOException {
        return locked((ledger, now) -> view(ledger, now));
    }

    /** Nonblocking UI view; null means no successful ledger operation has happened yet. */
    public Status cachedStatus() { return cachedStatus; }

    private <T> T locked(Operation<T> operation) throws IOException {
        synchronized (LOCKS.computeIfAbsent(path, ignored -> new Object())) {
            Files.createDirectories(path.getParent());
            // Lock a stable sibling: atomic replacement changes the ledger inode.
            try (FileChannel channel = FileChannel.open(path.resolveSibling(path.getFileName() + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var fileLock = channel.lock()) {
                long now = clock.millis();
                if (now < 0 || now > Long.MAX_VALUE - PENDING_WINDOW_MILLIS) throw new IOException("Invalid budget clock");
                Ledger ledger = read();
                ledger.charges().removeIf(charge -> charge.expiresAtMillis() <= now || charge.tokens() == 0);
                cachedStatus = view(ledger, now);
                return operation.apply(ledger, now);
            }
        }
    }

    private Ledger read() throws IOException {
        if (Files.notExists(path)) return new Ledger(1, new ArrayList<>(), null);
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_FILE_BYTES) throw new IOException("Invalid token ledger file");
        Ledger ledger;
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes((int) MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("Token ledger exceeds size bound");
        }
        try (JsonReader reader = new JsonReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
            ledger = GSON.getAdapter(Ledger.class).read(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing token ledger data");
        } catch (RuntimeException exception) {
            throw new IOException("Malformed token ledger", exception);
        }
        if (ledger == null || ledger.version() != 1 || ledger.charges() == null
                || ledger.charges().size() > MAX_ENTRIES
                || (ledger.cooldownUntilMillis() != null && ledger.cooldownUntilMillis() < 0)) {
            throw new IOException("Invalid token ledger state");
        }
        var ids = new HashSet<String>();
        for (Charge charge : ledger.charges()) {
            if (charge == null || charge.id() == null || !charge.id().matches("[0-9a-fA-F-]{36}")
                    || !ids.add(charge.id()) || charge.startedAtMillis() < 0
                    || charge.startedAtMillis() > Long.MAX_VALUE - PENDING_WINDOW_MILLIS
                    || charge.reservedTokens() < 1 || (charge.actualTokens() != null && charge.actualTokens() < 0)
                    || ((charge.actualTokens() == null) != (charge.completedAtMillis() == null))
                    || (charge.completedAtMillis() != null && (charge.completedAtMillis() < charge.startedAtMillis()
                        || charge.completedAtMillis() > Long.MAX_VALUE - WINDOW_MILLIS))) {
                throw new IOException("Invalid token ledger charge");
            }
        }
        return new Ledger(ledger.version(), new ArrayList<>(ledger.charges()), ledger.cooldownUntilMillis());
    }

    private void write(Ledger ledger, long now) throws IOException {
        String json = GSON.toJson(ledger);
        if (json.length() > MAX_FILE_BYTES) throw new IOException("Token ledger exceeds size bound");
        AtomicSnapshotFile.write(path, json);
        cachedStatus = view(ledger, now);
    }

    private Status view(Ledger ledger, long now) {
        long used = ledger.charges().stream().mapToLong(Charge::tokens).sum();
        return new Status(limit, used, Math.max(0, limit - used), nextEligible(ledger, now, 1),
                ledger.cooldownUntilMillis() == null ? Instant.EPOCH : Instant.ofEpochMilli(ledger.cooldownUntilMillis()));
    }

    private Instant nextEligible(Ledger ledger, long now, int requested) {
        long eligible = Math.max(now, ledger.cooldownUntilMillis() == null ? now : ledger.cooldownUntilMillis());
        long used = ledger.charges().stream().mapToLong(Charge::tokens).sum();
        if (used + requested > limit) {
            List<Charge> oldest = ledger.charges().stream().sorted(Comparator.comparingLong(Charge::expiresAtMillis)).toList();
            for (Charge charge : oldest) {
                used -= charge.tokens();
                eligible = Math.max(eligible, charge.expiresAtMillis());
                if (used + requested <= limit) break;
            }
        }
        return Instant.ofEpochMilli(eligible);
    }
}
