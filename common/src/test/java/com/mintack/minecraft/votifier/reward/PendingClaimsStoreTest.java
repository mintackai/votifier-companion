package com.mintack.minecraft.votifier.reward;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PendingClaimsStoreTest {
    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();

    private final PendingClaimsStore store = new PendingClaimsStore();

    @After
    public void tearDown() {
        store.shutdownAndAwait();
    }

    @Test
    public void persistsAndReloadsUndeliveredClaimsAcrossRestarts() {
        Path file = tempDir.getRoot().toPath().resolve("pending_claims.json");
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();

        store.loadFrom(file);
        assertTrue(store.add(new PendingClaimsStore.PendingClaim(10L, playerA, "Steve", 1, 2, false)));
        assertTrue(store.add(new PendingClaimsStore.PendingClaim(11L, playerB, "Alex", 3, 0, true)));
        // Duplicate claim id must not be queued twice.
        assertFalse(store.add(new PendingClaimsStore.PendingClaim(10L, playerA, "Steve", 1, 2, false)));
        store.shutdownAndAwait();

        assertTrue(Files.exists(file));

        PendingClaimsStore reloaded = new PendingClaimsStore();
        try {
            reloaded.loadFrom(file);
            assertTrue(reloaded.hasClaimsFor(playerA));
            assertTrue(reloaded.hasClaimsFor(playerB));

            List<PendingClaimsStore.PendingClaim> drainedA = reloaded.drainForPlayer(playerA);
            assertEquals(1, drainedA.size());
            assertEquals(10L, drainedA.get(0).claimId());
            assertEquals(1, drainedA.get(0).rewardCount());
            assertEquals(2, drainedA.get(0).remaining());
            assertFalse(drainedA.get(0).claimAll());

            assertFalse(reloaded.hasClaimsFor(playerA));
            assertTrue(reloaded.hasClaimsFor(playerB));
            assertTrue(reloaded.drainForPlayer(playerA).isEmpty());
        } finally {
            reloaded.shutdownAndAwait();
        }

        PendingClaimsStore afterDrain = new PendingClaimsStore();
        try {
            afterDrain.loadFrom(file);
            assertFalse(afterDrain.hasClaimsFor(playerA));
            assertTrue(afterDrain.hasClaimsFor(playerB));
        } finally {
            afterDrain.shutdownAndAwait();
        }
    }

    @Test
    public void neverMarksOrQueuesSameClaimIdTwiceInSession() {
        Path file = tempDir.getRoot().toPath().resolve("pending_claims.json");
        UUID player = UUID.randomUUID();

        store.loadFrom(file);
        assertTrue(store.tryMarkDelivered(55L));
        assertFalse(store.tryMarkDelivered(55L));
        assertFalse(store.add(new PendingClaimsStore.PendingClaim(55L, player, "Steve", 1, 0, false)));
        assertFalse(store.hasClaimsFor(player));
    }

    @Test
    public void ignoresInvalidEntriesAndCorruptFile() throws Exception {
        Path file = tempDir.getRoot().toPath().resolve("pending_claims.json");
        Files.writeString(file, "{not-valid-json");

        store.loadFrom(file);
        UUID player = UUID.randomUUID();
        assertFalse(store.hasClaimsFor(player));
        assertFalse(store.add(new PendingClaimsStore.PendingClaim(0L, player, "Steve", 1, 0, false)));
        assertFalse(store.add(new PendingClaimsStore.PendingClaim(5L, player, "Steve", 0, 0, false)));
    }
}
