package com.mintack.minecraft.votifier.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VotifierApiClientTest {
    private static final String API_KEY = "secret-test-key";

    private HttpServer server;
    private VotifierApiClient client;

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        client = new VotifierApiClient(baseUrl, API_KEY, Duration.ofSeconds(3));
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void claimOneSendsAuthAndParsesClaim() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        AtomicReference<String> authHeader = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        server.createContext("/v1/players/Steve/claim", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respondJson(exchange, 200, "{\"remaining\":2,\"claim\":101}");
        });

        VotifierApiClient.ClaimOneResult result = client.claimOne("Steve", playerUuid, "req-123");
        assertEquals("Bearer " + API_KEY, authHeader.get());
        JsonObject parsedBody = JsonParser.parseString(requestBody.get()).getAsJsonObject();
        assertEquals(playerUuid.toString(), parsedBody.get("uuid").getAsString());
        assertEquals("req-123", parsedBody.get("request_id").getAsString());
        assertTrue(result.hasClaimedReward());
        assertEquals(2, result.remaining());
        assertEquals(Long.valueOf(101L), result.claim());
    }

    @Test
    public void claimOneHandlesEmptyQueue() throws Exception {
        server.createContext("/v1/players/Alex/claim", exchange ->
                respondJson(exchange, 200, "{\"remaining\":-1,\"claim\":null}"));

        VotifierApiClient.ClaimOneResult result = client.claimOne("Alex", UUID.randomUUID(), "req-empty");
        assertFalse(result.hasClaimedReward());
        assertEquals(-1, result.remaining());
        assertNull(result.claim());
    }

    @Test
    public void claimAllParsesBatchResult() throws Exception {
        server.createContext("/v1/players/Steve/claim-all", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            respondJson(exchange, 200, "{\"claimed\":4,\"claim\":202}");
        });

        VotifierApiClient.ClaimAllResult result = client.claimAll("Steve", UUID.randomUUID(), "req-all-1");
        assertTrue(result.hasClaimedRewards());
        assertEquals(4, result.claimed());
        assertEquals(Long.valueOf(202L), result.claim());
    }

    @Test
    public void confirmClaimAndVoteCountWork() throws Exception {
        server.createContext("/v1/claims/202/confirm", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            respondJson(exchange, 200, "{\"claim\":202,\"confirmed\":true}");
        });
        server.createContext("/v1/players/Steve/votes/count", exchange -> {
            assertEquals("GET", exchange.getRequestMethod());
            respondJson(exchange, 200, "{\"unclaimed\":5}");
        });

        assertTrue(client.confirmClaim(202L));
        assertEquals(5, client.getUnclaimedCount("Steve"));
    }

    @Test
    public void nonSuccessResponseThrowsStructuredException() {
        server.createContext("/v1/players/Steve/claim", exchange ->
                respondJson(exchange, 409, "{\"error\":\"request_id_conflict\",\"message\":\"duplicate request_id\"}"));

        try {
            client.claimOne("Steve", UUID.randomUUID(), "dup-id");
            fail("Expected VotifierApiException");
        } catch (VotifierApiException e) {
            assertEquals(409, e.getStatusCode());
            assertEquals("request_id_conflict", e.getErrorCode());
            assertTrue(e.getMessage().contains("duplicate request_id"));
        } catch (Exception e) {
            fail("Unexpected exception type: " + e);
        }
    }

    @Test
    public void rejectsInvalidPlayerNameAndRequestIdBeforeNetworkCall() {
        assertFalse(VotifierApiClient.isValidPlayerName("../admin"));
        assertFalse(VotifierApiClient.isValidPlayerName("PlayerNameTooLong17"));
        assertTrue(VotifierApiClient.isValidPlayerName("Player_123"));

        try {
            client.claimOne("../admin", UUID.randomUUID(), "req-1");
            fail("Expected IllegalArgumentException for bad player name");
        } catch (IllegalArgumentException expected) {
            // expected
        } catch (Exception e) {
            fail("Unexpected exception: " + e);
        }

        try {
            client.claimOne("Steve", UUID.randomUUID(), "bad id with spaces");
            fail("Expected IllegalArgumentException for bad request_id");
        } catch (IllegalArgumentException expected) {
            // expected
        } catch (Exception e) {
            fail("Unexpected exception: " + e);
        }
    }

    @Test
    public void eventStreamSendsAuthAndStaysReadableLineByLine() throws Exception {
        AtomicReference<String> authHeader = new AtomicReference<>();
        AtomicReference<String> acceptHeader = new AtomicReference<>();
        server.createContext("/v1/events", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            acceptHeader.set(exchange.getRequestHeaders().getFirst("Accept"));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(": registered\n\nevent: vote\ndata: {\"player\":\"Steve\",\"site\":\"TopG\"}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
            }
        });

        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(client.openEventStream(), StandardCharsets.UTF_8))) {
            assertEquals(": registered", reader.readLine());
            assertEquals("", reader.readLine());
            assertEquals("event: vote", reader.readLine());
            assertEquals("data: {\"player\":\"Steve\",\"site\":\"TopG\"}", reader.readLine());
        }
        assertEquals("Bearer " + API_KEY, authHeader.get());
        assertEquals("text/event-stream", acceptHeader.get());
    }

    @Test
    public void eventStreamReportsApiErrors() {
        server.createContext("/v1/events", exchange ->
                respondJson(exchange, 429, "{\"error\":\"too_many_listeners\",\"message\":\"Too many clients\"}"));
        try {
            client.openEventStream().close();
            fail("Expected VotifierApiException");
        } catch (VotifierApiException e) {
            assertEquals(429, e.getStatusCode());
            assertEquals("too_many_listeners", e.getErrorCode());
        } catch (Exception e) {
            fail("Unexpected exception type: " + e);
        }
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
