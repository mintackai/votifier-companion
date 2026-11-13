package com.mintack.minecraft.votifier.event;

import com.mintack.minecraft.votifier.VotifierMod;
import com.mintack.minecraft.votifier.client.VotifierApiClient;
import com.mintack.minecraft.votifier.client.VotifierApiException;
import com.mintack.minecraft.votifier.config.MessagesConfig;
import com.mintack.minecraft.votifier.config.VotifierConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * Registers with {@code votifier-python} for live vote events and broadcasts each vote to
 * everyone online.
 * <p>
 * A background thread keeps one event stream open and reconnects when it drops. Announcements
 * are best effort: a vote received while the stream is down is not announced later. Rewards
 * never depend on this stream.
 */
public final class VoteEventListener {
    private static final VoteEventListener INSTANCE = new VoteEventListener();

    /** The server sends a keep-alive every 15 seconds; a longer silence means a dead connection. */
    private static final long SILENCE_LIMIT_MILLIS = 45_000L;
    private static final long CHECK_INTERVAL_MILLIS = 5_000L;
    private static final long FIRST_RETRY_MILLIS = 2_000L;
    private static final long MAX_RETRY_MILLIS = 15_000L;
    private static final int MAX_LINE_LENGTH = 8192;

    private final Object lock = new Object();
    // Guarded by lock.
    private Worker worker;
    // Guarded by lock. Remembered so a reload can restart a listener that was off.
    private MinecraftServer lastServer;

    private VoteEventListener() {
    }

    public static VoteEventListener getInstance() {
        return INSTANCE;
    }

    /**
     * Starts listening with the current configuration, replacing any earlier listener.
     * Does nothing but stop the earlier listener when announcements are turned off or the
     * API is not configured. Call on the server thread, at server start and after a config reload.
     *
     * @param server server whose players receive the announcements
     */
    public void start(MinecraftServer server) {
        VotifierConfig config = VotifierConfig.getInstance();
        Worker next = null;
        if (config.isAnnounceVotes() && config.isConfigured()) {
            next = new Worker(server, config.getApiUrl(), config.getApiKey(), config.getRequestTimeoutSeconds());
        }
        synchronized (lock) {
            if (worker != null) {
                worker.shutdown();
            }
            worker = next;
            if (next != null) {
                next.thread.start();
            }
        }
    }

    /**
     * Stops listening and closes the event stream.
     */
    public void stop() {
        synchronized (lock) {
            if (worker != null) {
                worker.shutdown();
                worker = null;
            }
        }
    }

    /**
     * Restarts the listener after a config reload, if a server is running.
     */
    public void restart() {
        MinecraftServer server;
        synchronized (lock) {
            server = lastServer;
        }
        if (server != null && !server.isStopped()) {
            start(server);
        }
    }

    /**
     * Remembers the running server and starts listening. Call when the server starts.
     *
     * @param server starting Minecraft server
     */
    public void onServerStart(MinecraftServer server) {
        synchronized (lock) {
            lastServer = server;
        }
        start(server);
    }

    /**
     * Forgets the server and stops listening. Call when the server has stopped.
     */
    public void onServerStopped() {
        synchronized (lock) {
            lastServer = null;
        }
        stop();
    }

    private static void broadcast(MinecraftServer server, VoteEvent event) {
        try {
            server.execute(() -> server.getPlayerList().broadcastSystemMessage(
                    Component.literal(MessagesConfig.getChatMessage(
                            "vote.broadcast",
                            Map.entry("player", event.player()),
                            Map.entry("site", event.site())
                    )),
                    false
            ));
        } catch (RejectedExecutionException ignored) {
            // The server is shutting down.
        }
    }

    /**
     * One listening session: a thread, its connection settings, and the stream it reads.
     */
    private static final class Worker {
        private final MinecraftServer server;
        private final VotifierApiClient client;
        private final Thread thread;
        private volatile boolean running = true;
        private volatile InputStream stream;
        private volatile long lastActivityNanos;

        Worker(MinecraftServer server, String apiUrl, String apiKey, int timeoutSeconds) {
            this.server = server;
            this.client = new VotifierApiClient(apiUrl, apiKey, Duration.ofSeconds(timeoutSeconds));
            this.thread = new Thread(this::run, "votifier-python-events");
            this.thread.setDaemon(true);
        }

        void shutdown() {
            running = false;
            closeStream();
            thread.interrupt();
        }

        private void run() {
            Thread watchdog = new Thread(this::watch, "votifier-python-events-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            long retryMillis = FIRST_RETRY_MILLIS;
            boolean failureLogged = false;
            try {
                while (running && !server.isStopped()) {
                    boolean registered = false;
                    try {
                        registered = listenOnce();
                        if (running && !failureLogged) {
                            VotifierMod.LOGGER.warn("Vote event stream closed; reconnecting");
                            failureLogged = true;
                        }
                    } catch (InterruptedException e) {
                        return;
                    } catch (IOException | RuntimeException e) {
                        if (!running) {
                            return;
                        }
                        registered = lastActivityNanos != 0L;
                        if (!failureLogged) {
                            VotifierMod.LOGGER.warn("Vote announcements unavailable ({}); retrying in the background", describe(e));
                            failureLogged = true;
                        } else {
                            VotifierMod.LOGGER.debug("Vote event stream failed again", e);
                        }
                    }
                    if (registered) {
                        // It worked for a while, so the next failure is news again.
                        retryMillis = FIRST_RETRY_MILLIS;
                        failureLogged = false;
                    }
                    try {
                        Thread.sleep(retryMillis);
                    } catch (InterruptedException e) {
                        return;
                    }
                    retryMillis = Math.min(MAX_RETRY_MILLIS, retryMillis * 2);
                }
            } finally {
                running = false;
                watchdog.interrupt();
                closeStream();
            }
        }

        /**
         * Opens the stream and reads it until it ends.
         *
         * @return {@code true} if the server confirmed the registration before the stream ended
         */
        private boolean listenOnce() throws IOException, InterruptedException {
            lastActivityNanos = 0L;
            boolean registered = false;
            try (InputStream in = client.openEventStream()) {
                stream = in;
                lastActivityNanos = System.nanoTime();
                if (!running) {
                    return false;
                }
                Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
                VoteEventParser parser = new VoteEventParser();
                String line;
                while ((line = readLine(reader)) != null) {
                    lastActivityNanos = System.nanoTime();
                    if (!registered) {
                        registered = true;
                        VotifierMod.LOGGER.info("Registered with votifier-python for vote announcements");
                    }
                    VoteEvent event = parser.feed(line);
                    if (event != null) {
                        broadcast(server, event);
                    }
                }
            } finally {
                stream = null;
            }
            return registered;
        }

        /** Closes a stream that has been silent for too long, which makes the reader reconnect. */
        private void watch() {
            while (running) {
                try {
                    Thread.sleep(CHECK_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    return;
                }
                long last = lastActivityNanos;
                if (stream != null && last != 0L && System.nanoTime() - last > SILENCE_LIMIT_MILLIS * 1_000_000L) {
                    closeStream();
                }
            }
        }

        private void closeStream() {
            InputStream current = stream;
            if (current != null) {
                try {
                    current.close();
                } catch (IOException ignored) {
                    // Closing is best effort.
                }
            }
        }

        private static String readLine(Reader reader) throws IOException {
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = reader.read()) != -1) {
                if (c == '\n') {
                    return line.toString();
                }
                if (c != '\r') {
                    if (line.length() >= MAX_LINE_LENGTH) {
                        throw new IOException("Event stream line is too long");
                    }
                    line.append((char) c);
                }
            }
            return null;
        }

        private static String describe(Exception e) {
            if (e instanceof VotifierApiException api && api.getStatusCode() == 404) {
                return "this votifier-python has no /v1/events endpoint; update it";
            }
            String message = e.getMessage();
            return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
        }
    }
}
