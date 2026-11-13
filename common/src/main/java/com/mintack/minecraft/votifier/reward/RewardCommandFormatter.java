package com.mintack.minecraft.votifier.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Renders configured console command templates with player and claim placeholders.
 */
public final class RewardCommandFormatter {
    private RewardCommandFormatter() {
    }

    /**
     * Formats each non-blank template in {@code templates} for execution by the server console.
     * Supported placeholders are {@code {player_name}}, {@code {player}}, {@code {uuid}}, and {@code {claim}}.
     * Leading {@code /} characters on templates are stripped so both {@code eco ...} and {@code /eco ...} work cleanly.
     *
     * @param templates   raw command templates from {@code votifier.json}
     * @param playerName  Minecraft player username
     * @param playerUuid  Minecraft player UUID
     * @param claimNumber claim identifier returned by {@code votifier-python}
     * @return normalized commands ready for {@code performPrefixedCommand}
     */
    public static List<String> formatAll(List<String> templates, String playerName, UUID playerUuid, long claimNumber) {
        if (templates == null || templates.isEmpty()) {
            return List.of();
        }
        String claimText = Long.toString(claimNumber);
        String uuidText = playerUuid.toString();
        List<String> formatted = new ArrayList<>(templates.size());
        for (String template : templates) {
            String rendered = formatOne(template, playerName, uuidText, claimText);
            if (rendered != null) {
                formatted.add(rendered);
            }
        }
        return formatted;
    }

    private static String formatOne(String template, String playerName, String uuidText, String claimText) {
        if (template == null) {
            return null;
        }
        String command = template.trim();
        while (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        if (command.isEmpty()) {
            return null;
        }
        return command
                .replace("{player_name}", playerName)
                .replace("{player}", playerName)
                .replace("{uuid}", uuidText)
                .replace("{claim}", claimText);
    }
}
