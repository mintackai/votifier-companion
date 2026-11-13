package com.mintack.minecraft.votifier.reward;

import com.google.gson.JsonParser;
import com.mintack.minecraft.votifier.client.VotifierApiClient;
import com.mintack.minecraft.votifier.client.VotifierApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ClaimRetryCoordinatorTest {
    private HttpServer server;
    private VotifierApiClient client;

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new VotifierApiClient(baseUrl, "test-api-key", Duration.ofSeconds(3));
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void retriesOnceWithSameRequestIdOnTransportFailure() throws Exception {
        List<String> seenRequestIds = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();

        server.createContext("/v1/players/Steve/claim", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seenRequestIds.add(JsonParser.parseString(body).getAsJsonObject().get("request_id").getAsString());
            if (attempts.incrementAndGet() == 1) {
                exchange.close();
                return;
            }
            respondJson(exchange, 200, "{\"remaining\":1,\"claim\":77}");
        });

        ClaimRetryCoordinator coordinator = new ClaimRetryCoordinator();
        UUID playerUuid = UUID.randomUUID();
        RewardService.ClaimOutcome outcome = coordinator.executeClaim(client, "Steve", playerUuid, false);

        assertEquals(2, attempts.get());
        assertEquals(2, seenRequestIds.size());
        assertEquals(seenRequestIds.get(0), seenRequestIds.get(1));
        assertTrue(outcome.claimedAny());
        assertEquals(77L, outcome.claimId());
        assertEquals(1, outcome.used());
        assertEquals(1, outcome.remaining());
        assertNull(coordinator.peekRequestId(playerUuid, "Steve", false));
    }

    @Test
    public void doesNotRetryOnVotifierApiException() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/v1/players/Steve/claim", exchange -> {
            attempts.incrementAndGet();
            respondJson(exchange, 409, "{\"error\":\"request_id_conflict\",\"message\":\"conflict\"}");
        });

        ClaimRetryCoordinator coordinator = new ClaimRetryCoordinator();
        try {
            coordinator.executeClaim(client, "Steve", UUID.randomUUID(), false);
            fail("Expected VotifierApiException");
        } catch (VotifierApiException expected) {
            assertEquals(409, expected.getStatusCode());
            assertEquals(1, attempts.get());
        } catch (Exception e) {
            fail("Unexpected exception: " + e);
        }
    }

    @Test
    public void preservesRequestIdAcrossAttemptsWhenImmediateRetryAlsoFails() throws Exception {
        List<String> claimOneRequestIds = new ArrayList<>();
        List<String> claimAllRequestIds = new ArrayList<>();
        AtomicInteger claimOneAttempts = new AtomicInteger();

        server.createContext("/v1/players/Steve/claim", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            claimOneRequestIds.add(JsonParser.parseString(body).getAsJsonObject().get("request_id").getAsString());
            if (claimOneAttempts.incrementAndGet() <= 2) {
                exchange.close();
                return;
            }
            respondJson(exchange, 200, "{\"remaining\":0,\"claim\":88}");
        });
        server.createContext("/v1/players/Steve/claim-all", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            claimAllRequestIds.add(JsonParser.parseString(body).getAsJsonObject().get("request_id").getAsString());
            respondJson(exchange, 200, "{\"claimed\":2,\"claim\":89}");
        });

        ClaimRetryCoordinator coordinator = new ClaimRetryCoordinator();
        UUID playerUuid = UUID.randomUUID();

        try {
            coordinator.executeClaim(client, "Steve", playerUuid, false);
            fail("Expected IOException when both attempts drop");
        } catch (IOException expected) {
            // expected
        }

        String savedId = coordinator.peekRequestId(playerUuid, "Steve", false);
        assertNotNull(savedId);

        // Switching to /reward all must use its own request_id rather than conflicting on kind.
        RewardService.ClaimOutcome allOutcome = coordinator.executeClaim(client, "Steve", playerUuid, true);
        assertEquals(89L, allOutcome.claimId());
        assertEquals(1, claimAllRequestIds.size());
        assertNotEquals(savedId, claimAllRequestIds.get(0));

        // Retrying /reward later reuses the preserved request_id and clears it on success.
        RewardService.ClaimOutcome recoveredOutcome = coordinator.executeClaim(client, "Steve", playerUuid, false);
        assertEquals(88L, recoveredOutcome.claimId());
        assertEquals(3, claimOneRequestIds.size());
        assertEquals(savedId, claimOneRequestIds.get(0));
        assertEquals(savedId, claimOneRequestIds.get(1));
        assertEquals(savedId, claimOneRequestIds.get(2));
        assertNull(coordinator.peekRequestId(playerUuid, "Steve", false));
    }

    private static void respondJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
