package com.mintack.minecraft.votifier.reward;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mintack.minecraft.votifier.VotifierMod;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Persists claimed rewards whose players disconnected before in-game delivery so they can be
 * delivered and confirmed on a later join without losing or duplicating votes.
 */
final class PendingClaimsStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_TRACKED_DELIVERED_IDS = 4096;

    private final Object lock = new Object();
    private final Map<Long, PendingClaim> claimsById = new LinkedHashMap<>();
    private final Map<Long, Boolean> deliveredClaimIds = new LinkedHashMap<>(128, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
            return size() > MAX_TRACKED_DELIVERED_IDS;
        }
    };

    private Path storagePath;
    private ExecutorService ioExecutor;

    /**
     * Binds the persistence file, resets in-memory state, and loads any undelivered claims from disk.
     *
     * @param path path to {@code pending_claims.json}
     */
    void loadFrom(Path path) {
        Objects.requireNonNull(path, "path must not be null");
        synchronized (lock) {
            this.storagePath = path;
            claimsById.clear();
            deliveredClaimIds.clear();
            if (ioExecutor == null || ioExecutor.isShutdown()) {
                ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "votifier-pending-claims-io");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            if (Files.exists(path)) {
                readFileLocked(path);
            }
        }
    }

    /**
     * Records a claim as already delivered in this session.
     *
     * @param claimId positive claim identifier
     * @return {@code true} if this is the first delivery recorded for {@code claimId}, {@code false} if duplicate
     */
    boolean tryMarkDelivered(long claimId) {
        if (claimId <= 0) {
            return false;
        }
        synchronized (lock) {
            if (deliveredClaimIds.containsKey(claimId)) {
                return false;
            }
            deliveredClaimIds.put(claimId, Boolean.TRUE);
            return true;
        }
    }

    /**
     * Queues an undelivered claim for delivery on the player's next join and schedules an async disk save.
     *
     * @param claim undelivered claim details
     * @return {@code true} if newly queued, {@code false} if invalid or already queued/delivered
     */
    boolean add(PendingClaim claim) {
        if (claim == null || claim.claimId() <= 0 || claim.rewardCount() <= 0 || claim.playerUuid() == null) {
            return false;
        }
        synchronized (lock) {
            if (deliveredClaimIds.containsKey(claim.claimId()) || claimsById.containsKey(claim.claimId())) {
                return false;
            }
            claimsById.put(claim.claimId(), claim);
            scheduleWriteLocked();
            return true;
        }
    }

    boolean hasClaimsFor(UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }
        synchronized (lock) {
            for (PendingClaim claim : claimsById.values()) {
                if (playerUuid.equals(claim.playerUuid())) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Removes and returns all pending claims belonging to {@code playerUuid}, scheduling an async disk save.
     *
     * @param playerUuid player UUID
     * @return ordered list of claims to deliver (empty when none)
     */
    List<PendingClaim> drainForPlayer(UUID playerUuid) {
        if (playerUuid == null) {
            return List.of();
        }
        List<PendingClaim> drained = new ArrayList<>();
        synchronized (lock) {
            Iterator<Map.Entry<Long, PendingClaim>> iterator = claimsById.entrySet().iterator();
            while (iterator.hasNext()) {
                PendingClaim claim = iterator.next().getValue();
                if (playerUuid.equals(claim.playerUuid())) {
                    drained.add(claim);
                    iterator.remove();
                }
            }
            if (!drained.isEmpty()) {
                scheduleWriteLocked();
            }
        }
        return drained;
    }

    /**
     * Flushes pending disk writes and shuts down the persistence executor.
     */
    void shutdownAndAwait() {
        ExecutorService executor;
        synchronized (lock) {
            executor = ioExecutor;
            ioExecutor = null;
        }
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                VotifierMod.LOGGER.warn("Timed out waiting for pending claims persistence to finish");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void readFileLocked(Path path) {
        try {
            String json = Files.readString(path);
            if (json.isBlank()) {
                return;
            }
            SerializedClaimsFile parsed = GSON.fromJson(json, SerializedClaimsFile.class);
            if (parsed == null || parsed.claims == null) {
                return;
            }
            for (SerializedClaim entry : parsed.claims) {
                if (entry == null || entry.claimId <= 0 || entry.rewardCount <= 0 || entry.playerUuid == null) {
                    continue;
                }
                try {
                    UUID uuid = UUID.fromString(entry.playerUuid);
                    String name = entry.playerName == null ? "" : entry.playerName;
                    claimsById.putIfAbsent(
                            entry.claimId,
                            new PendingClaim(entry.claimId, uuid, name, entry.rewardCount, entry.remaining, entry.claimAll)
                    );
                } catch (IllegalArgumentException ignored) {
                    VotifierMod.LOGGER.warn("Skipping pending claim #{} with invalid UUID '{}'", entry.claimId, entry.playerUuid);
                }
            }
        } catch (IOException | JsonParseException | IllegalStateException e) {
            VotifierMod.LOGGER.error("Failed to read pending claims from {}", path, e);
        }
    }

    private void scheduleWriteLocked() {
        if (storagePath == null) {
            return;
        }
        ExecutorService executor = ioExecutor;
        if (executor == null || executor.isShutdown()) {
            writeSnapshot(storagePath, snapshotLocked());
            return;
        }
        try {
            executor.execute(() -> writeSnapshot(storagePath, snapshotCopy()));
        } catch (RejectedExecutionException e) {
            writeSnapshot(storagePath, snapshotLocked());
        }
    }

    private List<PendingClaim> snapshotCopy() {
        synchronized (lock) {
            return snapshotLocked();
        }
    }

    private List<PendingClaim> snapshotLocked() {
        return new ArrayList<>(claimsById.values());
    }

    private static void writeSnapshot(Path path, List<PendingClaim> snapshot) {
        if (path == null) {
            return;
        }
        SerializedClaimsFile file = new SerializedClaimsFile();
        file.claims = new ArrayList<>(snapshot.size());
        for (PendingClaim claim : snapshot) {
            SerializedClaim entry = new SerializedClaim();
            entry.claimId = claim.claimId();
            entry.playerUuid = claim.playerUuid().toString();
            entry.playerName = claim.playerName();
            entry.rewardCount = claim.rewardCount();
            entry.remaining = claim.remaining();
            entry.claimAll = claim.claimAll();
            file.claims.add(entry);
        }
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(file));
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            VotifierMod.LOGGER.error("Failed to persist pending claims to {}", path, e);
        }
    }

    record PendingClaim(
            long claimId,
            UUID playerUuid,
            String playerName,
            int rewardCount,
            int remaining,
            boolean claimAll
    ) {
    }

    private static final class SerializedClaimsFile {
        List<SerializedClaim> claims = new ArrayList<>();
    }

    private static final class SerializedClaim {
        long claimId;
        String playerUuid;
        String playerName;
        int rewardCount;
        int remaining;
        boolean claimAll;
    }
}
