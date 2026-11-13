package com.mintack.minecraft.votifier.client;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Synchronous HTTP client for the {@code votifier-python} {@code /v1} REST API.
 * <p>
 * Callers on the Minecraft server must invoke these methods off the main server thread.
 */
public final class VotifierApiClient {
    private static final Pattern VALID_PLAYER_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final Pattern VALID_REQUEST_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Gson GSON = new Gson();

    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient httpClient;

    /**
     * Constructs an API client targeting {@code baseUrl} with bearer authentication.
     *
     * @param baseUrl base HTTP(S) URL without trailing slash (e.g. {@code http://127.0.0.1:8193})
     * @param apiKey  bearer API key matching the {@code votifier-python} configuration
     * @param timeout connect and per-request timeout
     */
    public VotifierApiClient(String baseUrl, String apiKey, Duration timeout) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        Objects.requireNonNull(timeout, "timeout must not be null");
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        URI parsed = URI.create(normalized);
        if (parsed.getHost() == null || parsed.getScheme() == null) {
            throw new IllegalArgumentException("Invalid API URL: " + baseUrl);
        }
        this.baseUrl = normalized;
        this.apiKey = apiKey.trim();
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
    }

    /**
     * Claims a single unclaimed reward for {@code playerName}.
     *
     * @param playerName Minecraft player name matching {@code ^[A-Za-z0-9_]{1,16}$}
     * @param playerUuid player UUID
     * @param requestId  idempotency key matching {@code ^[A-Za-z0-9_-]{1,64}$}
     * @return claim result containing {@code claim} id and {@code remaining} count ({@code -1} when none)
     * @throws IOException          if an HTTP, timeout, or serialization error occurs
     * @throws InterruptedException if interrupted while waiting for the HTTP response
     */
    public ClaimOneResult claimOne(String playerName, UUID playerUuid, String requestId)
            throws IOException, InterruptedException {
        validatePlayerName(playerName);
        validateClaimArgs(playerUuid, requestId);
        String body = GSON.toJson(new ClaimBody(playerUuid.toString(), requestId));
        return sendJson("POST", endpoint("/v1/players/" + playerName + "/claim"), body, ClaimOneResult.class);
    }

    /**
     * Claims all unclaimed rewards currently held for {@code playerName}.
     *
     * @param playerName Minecraft player name matching {@code ^[A-Za-z0-9_]{1,16}$}
     * @param playerUuid player UUID
     * @param requestId  idempotency key matching {@code ^[A-Za-z0-9_-]{1,64}$}
     * @return batch claim result containing {@code claimed} count and {@code claim} id
     * @throws IOException          if an HTTP, timeout, or serialization error occurs
     * @throws InterruptedException if interrupted while waiting for the HTTP response
     */
    public ClaimAllResult claimAll(String playerName, UUID playerUuid, String requestId)
            throws IOException, InterruptedException {
        validatePlayerName(playerName);
        validateClaimArgs(playerUuid, requestId);
        String body = GSON.toJson(new ClaimBody(playerUuid.toString(), requestId));
        return sendJson("POST", endpoint("/v1/players/" + playerName + "/claim-all"), body, ClaimAllResult.class);
    }

    /**
     * Marks a previously returned claim number as confirmed after rewards have been delivered.
     *
     * @param claimNumber positive claim identifier returned by {@link #claimOne} or {@link #claimAll}
     * @return {@code true} if the API reports {@code confirmed: true}
     * @throws IOException          if an HTTP, timeout, or serialization error occurs
     * @throws InterruptedException if interrupted while waiting for the HTTP response
     */
    public boolean confirmClaim(long claimNumber) throws IOException, InterruptedException {
        if (claimNumber <= 0) {
            throw new IllegalArgumentException("claimNumber must be positive");
        }
        ConfirmResult result = sendJson("POST", endpoint("/v1/claims/" + claimNumber + "/confirm"), null, ConfirmResult.class);
        return result.confirmed();
    }

    /**
     * Fetches the current number of unclaimed votes for {@code playerName}.
     *
     * @param playerName Minecraft player name matching {@code ^[A-Za-z0-9_]{1,16}$}
     * @return unclaimed vote count
     * @throws IOException          if an HTTP, timeout, or serialization error occurs
     * @throws InterruptedException if interrupted while waiting for the HTTP response
     */
    public int getUnclaimedCount(String playerName) throws IOException, InterruptedException {
        validatePlayerName(playerName);
        CountResult result = sendJson("GET", endpoint("/v1/players/" + playerName + "/votes/count"), null, CountResult.class);
        return result.unclaimed();
    }

    /**
     * Registers for live vote events by opening the Server-Sent Events stream of {@code GET /v1/events}.
     * The stream stays open until the server or the caller closes it; the caller must close it.
     *
     * @return the open response body, positioned at its first line
     * @throws IOException          if the connection fails or the API answers with a non-success status
     * @throws InterruptedException if interrupted while waiting for the response headers
     */
    public InputStream openEventStream() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint("/v1/events"))
                // Covers the wait for the response headers only; the body is read for as long as it stays open.
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String body;
            try (InputStream in = response.body()) {
                body = new String(in.readNBytes(4096), StandardCharsets.UTF_8);
            }
            throw buildApiException(status, body);
        }
        return response.body();
    }

    /**
     * Checks whether a player name matches the API's allowed character set and length.
     *
     * @param playerName candidate player name
     * @return {@code true} if non-null and matching {@code ^[A-Za-z0-9_]{1,16}$}
     */
    public static boolean isValidPlayerName(String playerName) {
        return playerName != null && VALID_PLAYER_NAME.matcher(playerName).matches();
    }

    private URI endpoint(String suffix) {
        return URI.create(baseUrl + suffix);
    }

    private static void validatePlayerName(String playerName) {
        if (!isValidPlayerName(playerName)) {
            throw new IllegalArgumentException("Invalid player name for votifier-python API: " + playerName);
        }
    }

    private static void validateClaimArgs(UUID playerUuid, String requestId) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        if (requestId == null || !VALID_REQUEST_ID.matcher(requestId).matches()) {
            throw new IllegalArgumentException("Invalid request_id: " + requestId);
        }
    }

    private <T> T sendJson(String method, URI uri, String jsonBody, Class<T> responseType)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json");

        if ("GET".equals(method)) {
            builder.GET();
        } else if ("POST".equals(method)) {
            if (jsonBody != null) {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
            } else {
                builder.POST(HttpRequest.BodyPublishers.noBody());
            }
        } else {
            throw new IllegalArgumentException("Unsupported HTTP method: " + method);
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int status = response.statusCode();
        String responseBody = response.body();
        if (status < 200 || status >= 300) {
            throw buildApiException(status, responseBody);
        }
        try {
            T parsed = GSON.fromJson(responseBody, responseType);
            if (parsed == null) {
                throw new VotifierApiException(status, "empty_response", "API returned empty response body");
            }
            return parsed;
        } catch (JsonParseException e) {
            throw new VotifierApiException(status, "invalid_json", "Failed to parse API JSON response: " + e.getMessage());
        }
    }

    private static VotifierApiException buildApiException(int status, String body) {
        String errorCode = "http_" + status;
        String message = "HTTP " + status;
        if (body != null && !body.isBlank()) {
            try {
                ErrorBody parsed = GSON.fromJson(body, ErrorBody.class);
                if (parsed != null) {
                    if (parsed.error != null && !parsed.error.isBlank()) {
                        errorCode = parsed.error;
                    }
                    if (parsed.message != null && !parsed.message.isBlank()) {
                        message = "HTTP " + status + " (" + errorCode + "): " + parsed.message;
                    } else {
                        message = "HTTP " + status + " (" + errorCode + ")";
                    }
                    return new VotifierApiException(status, errorCode, message);
                }
            } catch (JsonParseException ignored) {
                // Fall back to truncated raw response body below for non-JSON proxy errors.
            }
            String snippet = body.length() > 200 ? body.substring(0, 200) + "..." : body;
            message = "HTTP " + status + ": " + snippet;
        }
        return new VotifierApiException(status, errorCode, message);
    }

    private record ClaimBody(String uuid, String request_id) {
    }

    private static final class ErrorBody {
        String error;
        String message;
    }

    /**
     * Response from {@code POST /v1/players/{name}/claim}.
     *
     * @param remaining remaining unclaimed votes ({@code -1} when none could be claimed)
     * @param claim     claim number to confirm, or {@code null} when nothing was claimed
     */
    public record ClaimOneResult(int remaining, Long claim) {
        public boolean hasClaimedReward() {
            return claim != null && claim > 0 && remaining >= 0;
        }
    }

    /**
     * Response from {@code POST /v1/players/{name}/claim-all}.
     *
     * @param claimed number of votes deducted in this batch ({@code 0} when none)
     * @param claim   claim number to confirm, or {@code null} when nothing was claimed
     */
    public record ClaimAllResult(int claimed, Long claim) {
        public boolean hasClaimedRewards() {
            return claim != null && claim > 0 && claimed > 0;
        }
    }

    private record ConfirmResult(long claim, boolean confirmed) {
    }

    private record CountResult(int unclaimed) {
    }
}
