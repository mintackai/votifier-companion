package com.mintack.minecraft.votifier.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Connection settings and console reward commands stored in
 * {@code config/votifier_python_companion/votifier.json}.
 */
public class VotifierConfig {
    private static final String DEFAULT_API_URL = "http://127.0.0.1:8193";
    private static final int DEFAULT_TIMEOUT_SECONDS = 5;
    private static final int MIN_TIMEOUT_SECONDS = 1;
    private static final int MAX_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_CLAIM_COOLDOWN_SECONDS = 3;
    private static final int MIN_CLAIM_COOLDOWN_SECONDS = 0;
    private static final int MAX_CLAIM_COOLDOWN_SECONDS = 300;
    private static final Pattern LOOPBACK_IPV4 = Pattern.compile("^127(\\.\\d{1,3}){3}$");

    private static VotifierConfig instance;

    private String apiUrl = DEFAULT_API_URL;
    private String apiKey = "";
    private int requestTimeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    private int claimCooldownSeconds = DEFAULT_CLAIM_COOLDOWN_SECONDS;
    private boolean announceVotes = true;
    private List<String> commands = defaultCommands();

    public static VotifierConfig getInstance() {
        if (instance == null) {
            instance = new VotifierConfig();
        }
        return instance;
    }

    static void setInstance(VotifierConfig config) {
        instance = config;
    }

    private VotifierConfig() {
    }

    private static List<String> defaultCommands() {
        return new ArrayList<>(List.of(
                "eco add {player_name} 50",
                "give {player_name} steak 16"
        ));
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public int getClaimCooldownSeconds() {
        return claimCooldownSeconds;
    }

    /**
     * @return {@code true} if each vote should be announced to everyone online as it arrives
     */
    public boolean isAnnounceVotes() {
        return announceVotes;
    }

    /**
     * @return a copy of the configured console command templates executed per claimed reward
     */
    public List<String> getCommands() {
        return commands == null ? List.of() : List.copyOf(commands);
    }

    /**
     * Checks whether both a non-blank API key and a valid HTTP/HTTPS API URL are configured.
     *
     * @return {@code true} when API calls can be attempted
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && parseBaseUri() != null;
    }

    /**
     * Checks whether the API key would travel unencrypted over the network, which is the case
     * for a plain {@code http} URL whose host is not the local machine.
     *
     * @return {@code true} if the API URL is valid, uses {@code http}, and does not point at loopback
     */
    public boolean sendsApiKeyUnencrypted() {
        URI parsed = parseBaseUri();
        if (parsed == null || !"http".equalsIgnoreCase(parsed.getScheme())) {
            return false;
        }
        String host = parsed.getHost().toLowerCase(Locale.ROOT);
        return !host.equals("localhost") && !host.equals("[::1]") && !LOOPBACK_IPV4.matcher(host).matches();
    }

    /**
     * Parses {@link #apiUrl} into an HTTP or HTTPS base URI when valid.
     *
     * @return parsed base {@link URI}, or {@code null} if {@link #apiUrl} is invalid
     */
    public URI parseBaseUri() {
        if (apiUrl == null || apiUrl.isBlank()) {
            return null;
        }
        try {
            URI parsed = URI.create(apiUrl.trim());
            String scheme = parsed.getScheme();
            if (scheme == null) {
                return null;
            }
            String lower = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(lower) && !"https".equals(lower)) {
                return null;
            }
            if (parsed.getHost() == null || parsed.getHost().isBlank()) {
                return null;
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Clamps out-of-range settings and removes null or blank command lines loaded from disk.
     */
    void sanitize() {
        if (apiUrl == null || apiUrl.isBlank()) {
            apiUrl = DEFAULT_API_URL;
        } else {
            apiUrl = stripTrailingSlashes(apiUrl.trim());
            if (apiUrl.isEmpty()) {
                apiUrl = DEFAULT_API_URL;
            }
        }

        apiKey = apiKey == null ? "" : apiKey.trim();

        if (requestTimeoutSeconds < MIN_TIMEOUT_SECONDS || requestTimeoutSeconds > MAX_TIMEOUT_SECONDS) {
            requestTimeoutSeconds = Math.max(MIN_TIMEOUT_SECONDS, Math.min(MAX_TIMEOUT_SECONDS, requestTimeoutSeconds));
        }

        if (claimCooldownSeconds < MIN_CLAIM_COOLDOWN_SECONDS || claimCooldownSeconds > MAX_CLAIM_COOLDOWN_SECONDS) {
            claimCooldownSeconds = Math.max(MIN_CLAIM_COOLDOWN_SECONDS, Math.min(MAX_CLAIM_COOLDOWN_SECONDS, claimCooldownSeconds));
        }

        if (commands == null) {
            commands = defaultCommands();
        } else {
            List<String> cleaned = new ArrayList<>();
            for (String command : commands) {
                if (command != null) {
                    String trimmed = command.trim();
                    if (!trimmed.isEmpty()) {
                        cleaned.add(trimmed);
                    }
                }
            }
            commands = cleaned;
        }
    }

    private static String stripTrailingSlashes(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }
}
