package com.mintack.minecraft.votifier.reward;

import com.mintack.minecraft.votifier.VotifierMod;
import com.mintack.minecraft.votifier.client.VotifierApiClient;
import com.mintack.minecraft.votifier.config.MessagesConfig;
import com.mintack.minecraft.votifier.config.VotifierConfig;
import dev.architectury.platform.Platform;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coordinates asynchronous claim calls against {@code votifier-python}, console reward command
 * execution on the main server thread, player chat notifications, and claim confirmation.
 */
public final class RewardService {
    private static final RewardService INSTANCE = new RewardService();
    /**
     * Upper bound on reward rounds run for a single claim, so a bogus {@code claimed} count
     * from the API cannot stall the server thread.
     */
    private static final int MAX_REWARDS_PER_CLAIM = 1000;

    private final AtomicReference<MinecraftServer> serverRef = new AtomicReference<>();
    private final Set<UUID> inFlightClaims = ConcurrentHashMap.newKeySet();
    private final PendingClaimsStore pendingClaims = new PendingClaimsStore();
    private final ClaimRetryCoordinator retryCoordinator = new ClaimRetryCoordinator();
    private final ClaimCooldownTracker cooldownTracker = new ClaimCooldownTracker();
    private final AtomicBoolean loggedClaimFailureStack = new AtomicBoolean(false);
    private final Object executorLock = new Object();
    private ExecutorService httpExecutor;
    // Only touched on the server thread.
    private VotifierApiClient cachedClient;
    private ClientSettings cachedClientSettings;

    private RewardService() {
    }

    public static RewardService getInstance() {
        return INSTANCE;
    }

    /**
     * Binds the active server instance before server startup finishes.
     *
     * @param server starting Minecraft server
     */
    public void onServerBeforeStart(MinecraftServer server) {
        serverRef.set(server);
        inFlightClaims.clear();
        retryCoordinator.clear();
        pendingClaims.loadFrom(Platform.getConfigFolder().resolve(VotifierMod.MOD_ID).resolve("pending_claims.json"));
        cooldownTracker.clear();
        loggedClaimFailureStack.set(false);
        ensureExecutor();
    }

    /**
     * Releases the server reference, clears pending player locks, flushes undelivered claims,
     * and shuts down the HTTP worker pool.
     */
    public void onServerStopped() {
        serverRef.set(null);
        inFlightClaims.clear();
        retryCoordinator.clear();
        cooldownTracker.clear();
        loggedClaimFailureStack.set(false);
        cachedClient = null;
        cachedClientSettings = null;
        synchronized (executorLock) {
            if (httpExecutor != null) {
                httpExecutor.shutdownNow();
                httpExecutor = null;
            }
        }
        pendingClaims.shutdownAndAwait();
    }

    /**
     * Delivers and confirms any rewards that were claimed for {@code player} before they
     * disconnected mid-claim.
     *
     * @param player joining player
     */
    public void onPlayerJoin(ServerPlayer player) {
        MinecraftServer server = serverRef.get();
        if (server == null || server.isStopped()) {
            return;
        }
        UUID playerUuid = player.getUUID();
        if (!pendingClaims.hasClaimsFor(playerUuid)) {
            return;
        }
        VotifierConfig config = VotifierConfig.getInstance();
        List<String> commandTemplates = config.getCommands();
        String playerName = player.getGameProfile().name();
        if (commandTemplates.isEmpty()) {
            VotifierMod.LOGGER.warn("Cannot deliver queued vote rewards for {} ({}): no reward commands are configured in votifier.json",
                    playerName, playerUuid);
            return;
        }
        List<PendingClaimsStore.PendingClaim> claims = pendingClaims.drainForPlayer(playerUuid);
        if (claims.isEmpty()) {
            return;
        }
        ExecutorService executor = ensureExecutor();
        VotifierApiClient client = config.isConfigured() ? clientFor(config) : null;
        for (PendingClaimsStore.PendingClaim claim : claims) {
            deliverClaim(
                    server,
                    executor,
                    client,
                    player,
                    commandTemplates,
                    claim.claimId(),
                    claim.rewardCount(),
                    claim.remaining(),
                    claim.claimAll()
            );
        }
    }

    /**
     * Prunes expired per-player cooldown entries when a player disconnects so inactive entries
     * do not accumulate indefinitely.
     */
    public void onPlayerQuit() {
        cooldownTracker.pruneExpired(VotifierConfig.getInstance().getClaimCooldownSeconds());
    }

    /**
     * Initiates an asynchronous reward claim for {@code player}.
     *
     * @param player   player who invoked {@code /reward} or {@code /reward all}
     * @param claimAll {@code true} to claim all unclaimed votes, {@code false} to claim one
     * @return {@code true} if an async claim request was started, {@code false} if rejected immediately
     */
    public boolean startClaim(ServerPlayer player, boolean claimAll) {
        VotifierConfig config = VotifierConfig.getInstance();
        if (!config.isConfigured()) {
            player.sendSystemMessage(Component.literal(MessagesConfig.getChatMessage("reward.not_configured")));
            return false;
        }

        MinecraftServer server = serverRef.get();
        if (server == null || server.isStopped()) {
            return false;
        }

        String playerName = player.getGameProfile().name();
        if (!VotifierApiClient.isValidPlayerName(playerName)) {
            VotifierMod.LOGGER.warn("Refusing vote claim for invalid player name '{}'", playerName);
            player.sendSystemMessage(Component.literal(MessagesConfig.getChatMessage("reward.error")));
            return false;
        }

        List<String> commandTemplates = config.getCommands();
        if (commandTemplates.isEmpty()) {
            // Claiming would deduct the player's votes without giving anything back.
            VotifierMod.LOGGER.warn("Refusing vote claim for {}: no reward commands are configured in votifier.json", playerName);
            player.sendSystemMessage(Component.literal(MessagesConfig.getChatMessage("reward.not_configured")));
            return false;
        }

        UUID playerUuid = player.getUUID();
        if (!inFlightClaims.add(playerUuid)) {
            player.sendSystemMessage(Component.literal(MessagesConfig.getChatMessage("reward.in_progress")));
            return false;
        }

        long waitSeconds = cooldownTracker.tryAcquire(playerUuid, config.getClaimCooldownSeconds());
        if (waitSeconds > 0) {
            inFlightClaims.remove(playerUuid);
            player.sendSystemMessage(Component.literal(MessagesConfig.getChatMessage(
                    "reward.cooldown",
                    Map.entry("seconds", Long.toString(waitSeconds))
            )));
            return false;
        }

        try {
            submitClaim(server, config, playerName, playerUuid, commandTemplates, claimAll);
        } catch (RuntimeException e) {
            // Nothing was submitted, so no completion callback will release the player's lock.
            inFlightClaims.remove(playerUuid);
            VotifierMod.LOGGER.error("Failed to start reward claim for {} ({})", playerName, playerUuid, e);
            player.sendSystemMessage(Component.literal(MessagesConfig.getChatMessage("reward.error")));
            return false;
        }
        return true;
    }

    private void submitClaim(
            MinecraftServer server,
            VotifierConfig config,
            String playerName,
            UUID playerUuid,
            List<String> commandTemplates,
            boolean claimAll
    ) {
        VotifierApiClient client = clientFor(config);

        ExecutorService executor = ensureExecutor();
        CompletableFuture.supplyAsync(() -> {
            try {
                return retryCoordinator.executeClaim(client, playerName, playerUuid, claimAll);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, executor).whenComplete((outcome, error) -> scheduleCompletion(server, executor, client, playerUuid, playerName, commandTemplates, outcome, error));
    }

    private void scheduleCompletion(
            MinecraftServer server,
            ExecutorService executor,
            VotifierApiClient client,
            UUID playerUuid,
            String fallbackName,
            List<String> commandTemplates,
            ClaimOutcome outcome,
            Throwable error
    ) {
        if (server.isStopped() || serverRef.get() != server) {
            if (error == null && outcome != null && outcome.claimedAny) {
                queuePendingClaim(playerUuid, fallbackName, outcome);
            }
            inFlightClaims.remove(playerUuid);
            return;
        }
        try {
            server.execute(() -> {
                try {
                    finishClaimOnServerThread(server, executor, client, playerUuid, fallbackName, commandTemplates, outcome, error);
                } finally {
                    inFlightClaims.remove(playerUuid);
                }
            });
        } catch (RejectedExecutionException e) {
            if (error == null && outcome != null && outcome.claimedAny) {
                queuePendingClaim(playerUuid, fallbackName, outcome);
            }
            inFlightClaims.remove(playerUuid);
        }
    }

    private void finishClaimOnServerThread(
            MinecraftServer server,
            ExecutorService executor,
            VotifierApiClient client,
            UUID playerUuid,
            String fallbackName,
            List<String> commandTemplates,
            ClaimOutcome outcome,
            Throwable error
    ) {
        ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(playerUuid);

        if (error != null) {
            Throwable root = error.getCause() != null ? error.getCause() : error;
            String summary = describeError(root);
            if (loggedClaimFailureStack.compareAndSet(false, true)) {
                VotifierMod.LOGGER.error("Failed claiming rewards for {} ({}): {}", fallbackName, playerUuid, summary, root);
            } else {
                VotifierMod.LOGGER.warn("Failed claiming rewards for {} ({}): {}", fallbackName, playerUuid, summary);
                VotifierMod.LOGGER.debug("Claim failure stack trace for {} ({})", fallbackName, playerUuid, root);
            }
            if (onlinePlayer != null) {
                onlinePlayer.sendSystemMessage(Component.literal(MessagesConfig.getChatMessage("reward.error")));
            }
            return;
        }
        loggedClaimFailureStack.set(false);

        if (outcome == null || !outcome.claimedAny) {
            if (onlinePlayer != null) {
                onlinePlayer.sendSystemMessage(Component.literal(MessagesConfig.getChatMessage("reward.none")));
            }
            return;
        }

        if (onlinePlayer == null) {
            // Player disconnected during HTTP roundtrip: persist the undelivered claim and skip
            // /confirm until the player reconnects and receives the rewards.
            queuePendingClaim(playerUuid, fallbackName, outcome);
            return;
        }

        deliverClaim(
                server,
                executor,
                client,
                onlinePlayer,
                commandTemplates,
                outcome.claimId,
                outcome.used,
                outcome.remaining,
                outcome.claimAll
        );
    }

    private void queuePendingClaim(UUID playerUuid, String playerName, ClaimOutcome outcome) {
        PendingClaimsStore.PendingClaim pending = new PendingClaimsStore.PendingClaim(
                outcome.claimId,
                playerUuid,
                playerName,
                outcome.used,
                outcome.remaining,
                outcome.claimAll
        );
        if (pendingClaims.add(pending)) {
            VotifierMod.LOGGER.info("Player {} ({}) disconnected before claimed reward #{} could be delivered; queued for next join",
                    playerName, playerUuid, outcome.claimId);
        }
    }

    private void deliverClaim(
            MinecraftServer server,
            ExecutorService executor,
            VotifierApiClient client,
            ServerPlayer onlinePlayer,
            List<String> commandTemplates,
            long claimId,
            int used,
            int remaining,
            boolean claimAll
    ) {
        String currentName = onlinePlayer.getGameProfile().name();
        if (!pendingClaims.tryMarkDelivered(claimId)) {
            VotifierMod.LOGGER.warn("Skipping duplicate delivery for claim #{} ({})", claimId, currentName);
            return;
        }
        int rounds = used;
        if (rounds > MAX_REWARDS_PER_CLAIM) {
            VotifierMod.LOGGER.error("Claim #{} for {} reports {} rewards; delivering only the first {}, the rest must be settled manually",
                    claimId, currentName, rounds, MAX_REWARDS_PER_CLAIM);
            rounds = MAX_REWARDS_PER_CLAIM;
        }
        executeConsoleCommands(server, commandTemplates, currentName, onlinePlayer.getUUID(), claimId, rounds);

        String messageKey = claimAll ? "reward.claimed_all" : "reward.claimed_one";
        onlinePlayer.sendSystemMessage(Component.literal(MessagesConfig.getChatMessage(
                messageKey,
                Map.entry("used", Integer.toString(used)),
                Map.entry("remaining", Integer.toString(remaining))
        )));

        if (client != null) {
            confirmAsync(executor, client, claimId, currentName);
        } else {
            VotifierMod.LOGGER.warn("Delivered queued claim #{} for {}, but API settings are not configured to confirm it",
                    claimId, currentName);
        }
    }

    private static void executeConsoleCommands(
            MinecraftServer server,
            List<String> commandTemplates,
            String playerName,
            UUID playerUuid,
            long claimId,
            int repetitions
    ) {
        List<String> commands = RewardCommandFormatter.formatAll(commandTemplates, playerName, playerUuid, claimId);
        if (commands.isEmpty()) {
            VotifierMod.LOGGER.warn("No console commands configured in votifier.json for claim #{} ({})", claimId, playerName);
            return;
        }
        CommandSourceStack console = server.createCommandSourceStack();
        for (int i = 0; i < repetitions; i++) {
            for (String command : commands) {
                try {
                    server.getCommands().performPrefixedCommand(console, command);
                } catch (Exception e) {
                    VotifierMod.LOGGER.error("Error running console reward command '{}' for {} (claim #{})",
                            command, playerName, claimId, e);
                }
            }
        }
    }

    private static void confirmAsync(ExecutorService executor, VotifierApiClient client, long claimId, String playerName) {
        if (executor.isShutdown()) {
            return;
        }
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    boolean confirmed = client.confirmClaim(claimId);
                    if (!confirmed) {
                        VotifierMod.LOGGER.warn("votifier-python returned confirmed=false for claim #{} ({})", claimId, playerName);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    VotifierMod.LOGGER.warn("Interrupted while confirming claim #{} ({})", claimId, playerName, e);
                } catch (Exception e) {
                    VotifierMod.LOGGER.warn("Failed to confirm claim #{} ({}) after reward execution", claimId, playerName, e);
                }
            }, executor);
        } catch (RejectedExecutionException e) {
            VotifierMod.LOGGER.warn("Skipped async confirm for claim #{} because executor is shutting down", claimId);
        }
    }

    /**
     * Returns the shared API client, rebuilding it only when the connection settings changed,
     * so each claim does not spin up its own HTTP client and worker threads.
     */
    private VotifierApiClient clientFor(VotifierConfig config) {
        ClientSettings settings = new ClientSettings(config.getApiUrl(), config.getApiKey(), config.getRequestTimeoutSeconds());
        if (cachedClient == null || !settings.equals(cachedClientSettings)) {
            cachedClient = new VotifierApiClient(
                    settings.apiUrl,
                    settings.apiKey,
                    Duration.ofSeconds(settings.timeoutSeconds)
            );
            cachedClientSettings = settings;
        }
        return cachedClient;
    }

    private record ClientSettings(String apiUrl, String apiKey, int timeoutSeconds) {
    }

    private ExecutorService ensureExecutor() {
        synchronized (executorLock) {
            if (httpExecutor == null || httpExecutor.isShutdown()) {
                httpExecutor = Executors.newCachedThreadPool(runnable -> {
                    Thread thread = new Thread(runnable, "votifier-python-http");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return httpExecutor;
        }
    }

    private static String describeError(Throwable root) {
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            return root.getClass().getName();
        }
        return root.getClass().getSimpleName() + ": " + message;
    }

    record ClaimOutcome(boolean claimedAny, int used, int remaining, long claimId, boolean claimAll) {
        static ClaimOutcome none() {
            return new ClaimOutcome(false, 0, 0, -1L, false);
        }

        static ClaimOutcome claimed(int used, int remaining, long claimId, boolean claimAll) {
            return new ClaimOutcome(true, used, remaining, claimId, claimAll);
        }
    }
}
