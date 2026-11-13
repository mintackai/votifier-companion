package com.mintack.minecraft.votifier.event;

/**
 * A vote announced by {@code votifier-python} over its event stream.
 *
 * @param player name of the player who voted
 * @param site   name of the voting site
 */
public record VoteEvent(String player, String site) {
}
