package com.mintack.minecraft.votifier.reward;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

public class RewardCommandFormatterTest {
    @Test
    public void formatsPlaceholdersAndStripsLeadingSlashes() {
        UUID uuid = UUID.fromString("12345678-1234-1234-1234-1234567890ab");
        List<String> rendered = RewardCommandFormatter.formatAll(
                Arrays.asList(
                        "eco add {player_name} 50",
                        "/give {player_name} steak 16",
                        "tellraw {player} {\"text\":\"Claim #{claim} for {uuid}\"}",
                        "   ",
                        null
                ),
                "Steve",
                uuid,
                42L
        );

        assertEquals(List.of(
                "eco add Steve 50",
                "give Steve steak 16",
                "tellraw Steve {\"text\":\"Claim #42 for 12345678-1234-1234-1234-1234567890ab\"}"
        ), rendered);
    }
}
