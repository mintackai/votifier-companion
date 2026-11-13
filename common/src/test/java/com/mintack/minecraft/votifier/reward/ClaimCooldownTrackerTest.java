package com.mintack.minecraft.votifier.reward;

import org.junit.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClaimCooldownTrackerTest {
    @Test
    public void rejectsRepeatedAttemptsInsideCooldownAndAllowsAfterExpiry() {
        AtomicLong nowNanos = new AtomicLong(1_000_000_000L);
        ClaimCooldownTracker tracker = new ClaimCooldownTracker(nowNanos::get);
        UUID player = UUID.randomUUID();

        assertEquals(0L, tracker.tryAcquire(player, 3));

        nowNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(500));
        long waitSeconds = tracker.tryAcquire(player, 3);
        assertEquals(3L, waitSeconds);

        // Rejected attempt must not extend the cooldown window.
        nowNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(2600));
        assertEquals(0L, tracker.tryAcquire(player, 3));
    }

    @Test
    public void zeroCooldownDisablesRateLimitAndClearsState() {
        AtomicLong nowNanos = new AtomicLong(0L);
        ClaimCooldownTracker tracker = new ClaimCooldownTracker(nowNanos::get);
        UUID player = UUID.randomUUID();

        assertEquals(0L, tracker.tryAcquire(player, 5));
        assertEquals(1, tracker.size());

        assertEquals(0L, tracker.tryAcquire(player, 0));
        assertEquals(0L, tracker.tryAcquire(player, 0));
        assertEquals(0, tracker.size());
    }

    @Test
    public void prunesExpiredEntriesSoMapDoesNotGrowWithoutBound() {
        AtomicLong nowNanos = new AtomicLong(10_000_000_000L);
        ClaimCooldownTracker tracker = new ClaimCooldownTracker(nowNanos::get);

        for (int i = 0; i < 10; i++) {
            assertEquals(0L, tracker.tryAcquire(UUID.randomUUID(), 3));
        }
        assertEquals(10, tracker.size());

        nowNanos.addAndGet(TimeUnit.SECONDS.toNanos(3));
        tracker.pruneExpired(3);
        assertEquals(0, tracker.size());

        UUID active = UUID.randomUUID();
        assertEquals(0L, tracker.tryAcquire(active, 3));
        assertTrue(tracker.size() == 1);
        tracker.clear();
        assertEquals(0, tracker.size());
    }
}
