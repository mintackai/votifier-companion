package com.mintack.minecraft.votifier.reward;

import com.mintack.minecraft.votifier.client.VotifierApiClient;
import com.mintack.minecraft.votifier.client.VotifierApiException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Coordinates idempotent claim retries and {@code request_id} reuse across transport failures.
 * <p>
 * In {@code votifier-python}, repeating an unconfirmed claim request with the same {@code request_id},
 * player name, UUID, and claim mode returns the original claim result without deducting extra votes,
 * whereas API errors ({@link VotifierApiException}) are not retried.
 */
final class ClaimRetryCoordinator {
    private static final int MAX_TRACKED_REQUEST_IDS = 4096;

    private final Object lock = new Object();
    private final Map<ClaimKey, String> pendingRequestIds = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ClaimKey, String> eldest) {
            return size() > MAX_TRACKED_REQUEST_IDS;
        }
    };

    /**
     * Executes a claim against {@code client}, retrying once with the same {@code request_id}
     * on transport {@link IOException}s and preserving that {@code request_id} for the player's
     * next attempt if both transport attempts fail.
     *
     * @param client     API client
     * @param playerName Minecraft player name
     * @param playerUuid Minecraft player UUID
     * @param claimAll   {@code true} for {@code /claim-all}, {@code false} for {@code /claim}
     * @return claim outcome returned by the API
     * @throws IOException          if the claim fails after retry or receives an API error
     * @throws InterruptedException if interrupted while waiting for HTTP I/O
     */
    RewardService.ClaimOutcome executeClaim(
            VotifierApiClient client,
            String playerName,
            UUID playerUuid,
            boolean claimAll
    ) throws IOException, InterruptedException {
        ClaimKey key = new ClaimKey(playerUuid, playerName.toLowerCase(Locale.ROOT), claimAll);
        String requestId = getOrCreateRequestId(key);

        try {
            RewardService.ClaimOutcome outcome = invokeClaim(client, playerName, playerUuid, claimAll, requestId);
            clearRequestId(key, requestId);
            return outcome;
        } catch (VotifierApiException e) {
            clearRequestId(key, requestId);
            throw e;
        } catch (IOException transportError) {
            if (Thread.currentThread().isInterrupted()) {
                rememberRequestId(key, requestId);
                throw transportError;
            }
            try {
                RewardService.ClaimOutcome retryOutcome = invokeClaim(client, playerName, playerUuid, claimAll, requestId);
                clearRequestId(key, requestId);
                return retryOutcome;
            } catch (VotifierApiException retryApiError) {
                clearRequestId(key, requestId);
                throw retryApiError;
            } catch (IOException retryTransportError) {
                rememberRequestId(key, requestId);
                throw retryTransportError;
            }
        }
    }

    void clear() {
        synchronized (lock) {
            pendingRequestIds.clear();
        }
    }

    String peekRequestId(UUID playerUuid, String playerName, boolean claimAll) {
        ClaimKey key = new ClaimKey(playerUuid, playerName.toLowerCase(Locale.ROOT), claimAll);
        synchronized (lock) {
            return pendingRequestIds.get(key);
        }
    }

    private static RewardService.ClaimOutcome invokeClaim(
            VotifierApiClient client,
            String playerName,
            UUID playerUuid,
            boolean claimAll,
            String requestId
    ) throws IOException, InterruptedException {
        if (claimAll) {
            VotifierApiClient.ClaimAllResult result = client.claimAll(playerName, playerUuid, requestId);
            return result.hasClaimedRewards()
                    ? RewardService.ClaimOutcome.claimed(result.claimed(), 0, result.claim(), true)
                    : RewardService.ClaimOutcome.none();
        }
        VotifierApiClient.ClaimOneResult result = client.claimOne(playerName, playerUuid, requestId);
        return result.hasClaimedReward()
                ? RewardService.ClaimOutcome.claimed(1, result.remaining(), result.claim(), false)
                : RewardService.ClaimOutcome.none();
    }

    private String getOrCreateRequestId(ClaimKey key) {
        synchronized (lock) {
            String existing = pendingRequestIds.get(key);
            return existing != null ? existing : UUID.randomUUID().toString();
        }
    }

    private void rememberRequestId(ClaimKey key, String requestId) {
        synchronized (lock) {
            pendingRequestIds.put(key, requestId);
        }
    }

    private void clearRequestId(ClaimKey key, String expectedRequestId) {
        synchronized (lock) {
            pendingRequestIds.remove(key, expectedRequestId);
        }
    }

    private record ClaimKey(UUID playerUuid, String playerNameLower, boolean claimAll) {
    }
}
