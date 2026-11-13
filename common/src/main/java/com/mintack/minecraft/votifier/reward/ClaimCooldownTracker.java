package com.mintack.minecraft.votifier.reward;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Tracks per-player claim attempt timestamps using a monotonic clock to rate-limit {@code /reward}.
 */
final class ClaimCooldownTracker {
    private final Map<UUID, Long> lastAttemptNanos = new ConcurrentHashMap<>();
    private final LongSupplier nanoClock;

    ClaimCooldownTracker() {
        this(System::nanoTime);
    }

    ClaimCooldownTracker(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    /**
     * Checks whether {@code playerUuid} may attempt a claim under {@code cooldownSeconds} and,
     * if allowed, records the attempt timestamp while pruning expired entries.
     *
     * @param playerUuid      player attempting to claim
     * @param cooldownSeconds configured cooldown in seconds ({@code <= 0} disables rate limiting)
     * @return remaining whole seconds to wait (at least {@code 1}), or {@code 0} when allowed
     */
    long tryAcquire(UUID playerUuid, int cooldownSeconds) {
        if (cooldownSeconds <= 0) {
            lastAttemptNanos.clear();
            return 0L;
        }
        long now = nanoClock.getAsLong();
        long cooldownNanos = TimeUnit.SECONDS.toNanos(cooldownSeconds);
        lastAttemptNanos.entrySet().removeIf(entry -> now - entry.getValue() >= cooldownNanos);

        Long previous = lastAttemptNanos.get(playerUuid);
        if (previous != null) {
            long elapsed = now - previous;
            if (elapsed < cooldownNanos) {
                long remainingNanos = cooldownNanos - elapsed;
                return Math.max(1L, (remainingNanos + 999_999_999L) / 1_000_000_000L);
            }
        }
        lastAttemptNanos.put(playerUuid, now);
        return 0L;
    }

    /**
     * Removes entries whose cooldown has already elapsed.
     *
     * @param cooldownSeconds configured cooldown in seconds
     */
    void pruneExpired(int cooldownSeconds) {
        if (cooldownSeconds <= 0) {
            lastAttemptNanos.clear();
            return;
        }
        long now = nanoClock.getAsLong();
        long cooldownNanos = TimeUnit.SECONDS.toNanos(cooldownSeconds);
        lastAttemptNanos.entrySet().removeIf(entry -> now - entry.getValue() >= cooldownNanos);
    }

    void clear() {
        lastAttemptNanos.clear();
    }

    int size() {
        return lastAttemptNanos.size();
    }
}
