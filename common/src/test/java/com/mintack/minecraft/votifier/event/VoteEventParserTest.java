package com.mintack.minecraft.votifier.event;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class VoteEventParserTest {
    private static List<VoteEvent> feedAll(String... lines) {
        VoteEventParser parser = new VoteEventParser();
        List<VoteEvent> events = new ArrayList<>();
        for (String line : lines) {
            VoteEvent event = parser.feed(line);
            if (event != null) {
                events.add(event);
            }
        }
        return events;
    }

    @Test
    public void parsesVotesAndIgnoresCommentsAndKeepAlives() {
        List<VoteEvent> events = feedAll(
                ": registered",
                "",
                "event: vote",
                "data: {\"player\":\"Notch\",\"site\":\"PlanetMinecraft\"}",
                "",
                ": keep-alive",
                "",
                "event:vote",
                "data:{\"player\":\"Steve\",\"site\":\"TopG\"}",
                ""
        );
        assertEquals(List.of(new VoteEvent("Notch", "PlanetMinecraft"), new VoteEvent("Steve", "TopG")), events);
    }

    @Test
    public void ignoresOtherEventTypesAndMalformedOrUnsafeData() {
        assertEquals(List.of(), feedAll("event: other", "data: {\"player\":\"Notch\",\"site\":\"A\"}", ""));
        assertEquals(List.of(), feedAll("data: {\"player\":\"Notch\",\"site\":\"A\"}", ""));
        assertEquals(List.of(), feedAll("event: vote", "data: not json", ""));
        assertEquals(List.of(), feedAll("event: vote", "data: [1,2]", ""));
        assertEquals(List.of(), feedAll("event: vote", "data: {\"site\":\"A\"}", ""));
        assertEquals(List.of(), feedAll("event: vote", "data: {\"player\":\"Notch\"}", ""));
        // A name that could inject a command or selector is never announced.
        assertEquals(List.of(), feedAll("event: vote", "data: {\"player\":\"@a\",\"site\":\"A\"}", ""));
        assertEquals(List.of(), feedAll("event: vote", "data: {\"player\":\"a b\",\"site\":\"A\"}", ""));
    }

    @Test
    public void eventStateResetsAfterEachBlankLine() {
        List<VoteEvent> events = feedAll(
                "event: vote",
                "",
                "data: {\"player\":\"Notch\",\"site\":\"A\"}",
                ""
        );
        assertEquals(List.of(), events);
    }

    @Test
    public void cleansSiteNamesForChat() {
        assertEquals("cEvil kSite", VoteEventParser.cleanSite(" §cEvil\n &kSite\u0007 "));
        assertEquals(64, VoteEventParser.cleanSite("x".repeat(500)).length());
        assertEquals("", VoteEventParser.cleanSite(null));
        VoteEventParser parser = new VoteEventParser();
        parser.feed("event: vote");
        parser.feed("data: {\"player\":\"Notch\",\"site\":\"§&\\n\"}");
        assertNull(parser.feed(""));
    }
}
