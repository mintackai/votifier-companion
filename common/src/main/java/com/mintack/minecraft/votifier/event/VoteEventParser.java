package com.mintack.minecraft.votifier.event;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.mintack.minecraft.votifier.client.VotifierApiClient;

/**
 * Incremental parser for the Server-Sent Events stream of {@code GET /v1/events}.
 * <p>
 * Not thread-safe: feed it the lines of one stream from one thread.
 */
public final class VoteEventParser {
    private static final Gson GSON = new Gson();
    private static final int MAX_SITE_LENGTH = 64;
    private static final int MAX_DATA_LENGTH = 4096;

    private String eventName = "";
    private final StringBuilder data = new StringBuilder();

    /**
     * Consumes one line of the stream, without its line terminator.
     *
     * @param line line as received; an empty line ends the current event
     * @return the vote completed by this line, or {@code null} if the line did not complete
     *         a valid vote event (comments, keep-alives, other event types, malformed data)
     */
    public VoteEvent feed(String line) {
        if (line.isEmpty()) {
            VoteEvent event = "vote".equals(eventName) && data.length() > 0 ? parseVote(data.toString()) : null;
            eventName = "";
            data.setLength(0);
            return event;
        }
        if (line.startsWith(":")) {
            return null;
        }
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        if ("event".equals(field)) {
            eventName = value;
        } else if ("data".equals(field) && data.length() + value.length() < MAX_DATA_LENGTH) {
            if (data.length() > 0) {
                data.append('\n');
            }
            data.append(value);
        }
        return null;
    }

    private static VoteEvent parseVote(String json) {
        Payload payload;
        try {
            payload = GSON.fromJson(json, Payload.class);
        } catch (JsonParseException e) {
            return null;
        }
        if (payload == null || !VotifierApiClient.isValidPlayerName(payload.player)) {
            return null;
        }
        String site = cleanSite(payload.site);
        return site.isEmpty() ? null : new VoteEvent(payload.player, site);
    }

    /**
     * Makes a site name safe to show in chat: drops control characters and the characters that
     * start a colour code ({@code §} and {@code &}), and limits the length.
     */
    static String cleanSite(String site) {
        if (site == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < site.length() && cleaned.length() < MAX_SITE_LENGTH; i++) {
            char c = site.charAt(i);
            if (c != '§' && c != '&' && !Character.isISOControl(c)) {
                cleaned.append(c);
            }
        }
        return cleaned.toString().trim();
    }

    private static final class Payload {
        String player;
        String site;
    }
}
