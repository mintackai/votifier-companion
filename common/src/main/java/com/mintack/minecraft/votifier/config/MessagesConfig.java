package com.mintack.minecraft.votifier.config;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Chat messages shown to players, stored in {@code config/votifier_python_companion/messages.json}.
 * <p>
 * Messages support {@code {placeholder}} tokens and {@code &} colour/format codes.
 */
public class MessagesConfig {
    private static MessagesConfig instance;
    private HashMap<String, String> messages = new HashMap<>();

    public static MessagesConfig getInstance() {
        if (instance == null) {
            instance = new MessagesConfig();
        }
        return instance;
    }

    static void setInstance(MessagesConfig messagesConfig) {
        instance = messagesConfig;
    }

    private MessagesConfig() {
        addDefaults(messages);
    }

    private static void addDefaults(Map<String, String> target) {
        target.put("prefix", "&5&l◆ &dRewards &5&l◆ &r");
        target.put("no_permission", "&cYou don't have permission to do that.");
        target.put("player_only", "&cOnly players can use this command.");
        target.put("reward.claimed_one", "&aUsed &d{used}&a reward! Remaining not used: &d{remaining}&a.");
        target.put("reward.claimed_all", "&aUsed all &d{used}&a rewards! Remaining not used: &d{remaining}&a.");
        target.put("reward.none", "&7You have no unclaimed rewards.");
        target.put("reward.in_progress", "&cYour reward claim is already in progress. Please wait.");
        target.put("reward.cooldown", "&cPlease wait {seconds}s before claiming rewards again.");
        target.put("reward.not_configured", "&cVote rewards are not configured yet. Ask an administrator to set the API key.");
        target.put("reward.error", "&cCould not claim rewards right now. Please try again later.");
        target.put("vote.broadcast", "&d{player} &avoted on &d{site}&a! Vote too, then use &d/reward&a.");
        target.put("reward.reload", "&aReloaded Votifier Python Companion configs.");
    }

    /**
     * Resolves a configured message template with the mod prefix, placeholder substitutions, and section-sign colours.
     *
     * @param key          message key in {@code messages.json}
     * @param replacements placeholder pairs without braces (e.g. {@code Map.entry("used", "1")})
     * @return formatted message string ready for {@code Component.literal}
     */
    @SafeVarargs
    public static String getChatMessage(String key, Map.Entry<String, String>... replacements) {
        MessagesConfig config = getInstance();
        String prefix = config.messages.getOrDefault("prefix", "");
        String template = config.messages.get(key);
        if (template == null) {
            template = key;
        }
        String formatted = applyPlaceholders(prefix + template, replacements);
        return colours(formatted);
    }

    private static String applyPlaceholders(String template, Map.Entry<String, String>[] replacements) {
        if (replacements == null || replacements.length == 0) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : replacements) {
            if (entry != null && entry.getKey() != null && entry.getValue() != null) {
                result = result.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return result;
    }

    /**
     * Converts Minecraft {@code &} colour and formatting codes to {@code §}.
     *
     * @param message raw template string
     * @return string with valid {@code &x} codes translated
     */
    public static String colours(String message) {
        StringBuilder result = new StringBuilder(message.length());
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c == '&' && i + 1 < message.length()
                    && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(message.charAt(i + 1)) >= 0) {
                result.append('§');
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    /**
     * Drops null entries so built-in defaults fill them back in.
     */
    void removeNullEntries() {
        if (messages == null) {
            messages = new HashMap<>();
        }
        messages.values().removeIf(Objects::isNull);
    }

    /**
     * Checks whether the config lacks any built-in message keys.
     *
     * @return {@code true} if defaults need to be added
     */
    boolean hasMissingMessages() {
        Map<String, String> defaults = new LinkedHashMap<>();
        addDefaults(defaults);
        return !messages.keySet().containsAll(defaults.keySet());
    }

    /**
     * Adds missing built-in message templates while keeping existing customized texts.
     */
    void addMissingMessages() {
        Map<String, String> defaults = new LinkedHashMap<>();
        addDefaults(defaults);
        defaults.forEach(messages::putIfAbsent);
    }
}
