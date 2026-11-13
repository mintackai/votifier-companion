package com.mintack.minecraft.votifier.config;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConfigManagerTest {
    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();

    @Before
    @After
    public void resetSingletons() {
        VotifierConfig.setInstance(null);
        CommandsConfig.setInstance(null);
        PermissionsConfig.setInstance(null);
        MessagesConfig.setInstance(null);
    }

    @Test
    public void createsAndLoadsDefaultConfigs() {
        Path folder = tempDir.getRoot().toPath().resolve("votifier_python_companion");
        ConfigManager.createDefaultConfigsIn(folder);

        assertTrue(Files.exists(folder.resolve("votifier.json")));
        assertTrue(Files.exists(folder.resolve("commands.json")));
        assertTrue(Files.exists(folder.resolve("permissions.json")));
        assertTrue(Files.exists(folder.resolve("messages.json")));

        VotifierConfig config = VotifierConfig.getInstance();
        assertEquals("http://127.0.0.1:8193", config.getApiUrl());
        assertEquals("", config.getApiKey());
        assertEquals(3, config.getClaimCooldownSeconds());
        assertTrue(config.isAnnounceVotes());
        assertFalse(config.isConfigured());
        assertEquals(List.of("eco add {player_name} 50", "give {player_name} steak 16"), config.getCommands());
        assertEquals("reward", CommandsConfig.getInstance().getCommand("reward").getPrimaryCommand());
        assertTrue(PermissionsConfig.isAllowedByDefault("commands.reward"));
        assertTrue(PermissionsConfig.isAllowedByDefault("commands.reward.all"));
        assertFalse(PermissionsConfig.isAllowedByDefault("admin.commands.reward.reload"));
    }

    @Test
    public void sanitizesInvalidValuesAndPreservesCustomCommands() throws Exception {
        Path folder = tempDir.getRoot().toPath().resolve("cfg");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("votifier.json"), """
                {
                  "apiUrl": " https://votes.example.com/api/ ",
                  "apiKey": " my-key ",
                  "requestTimeoutSeconds": 999,
                  "commands": [
                    "eco add {player_name} 100",
                    "",
                    null,
                    "give {player_name} diamond 1"
                  ]
                }
                """);

        ConfigManager.loadConfigsFrom(folder);

        VotifierConfig loaded = VotifierConfig.getInstance();
        assertEquals("https://votes.example.com/api", loaded.getApiUrl());
        assertEquals("my-key", loaded.getApiKey());
        assertEquals(60, loaded.getRequestTimeoutSeconds());
        assertTrue(loaded.isConfigured());
        assertEquals(List.of("eco add {player_name} 100", "give {player_name} diamond 1"), loaded.getCommands());
    }

    @Test
    public void flagsPlainHttpOnlyForRemoteHosts() throws Exception {
        assertFalse(unencryptedFor("http://127.0.0.1:8193"));
        assertFalse(unencryptedFor("http://localhost:8193"));
        assertFalse(unencryptedFor("http://[::1]:8193"));
        assertFalse(unencryptedFor("https://votes.example.com"));
        assertFalse(unencryptedFor("not a url"));
        assertTrue(unencryptedFor("http://votes.example.com:8193"));
        assertTrue(unencryptedFor("http://127.evil.example.com"));
    }

    private boolean unencryptedFor(String apiUrl) throws Exception {
        Path folder = tempDir.newFolder().toPath();
        Files.writeString(folder.resolve("votifier.json"), "{\"apiUrl\": \"" + apiUrl + "\", \"apiKey\": \"k\"}");
        ConfigManager.loadConfigsFrom(folder);
        return VotifierConfig.getInstance().sendsApiKeyUnencrypted();
    }

    @Test
    public void clampsClaimCooldownSecondsToSupportedRange() throws Exception {
        assertEquals(0, cooldownFor("{\"claimCooldownSeconds\": -5}"));
        assertEquals(0, cooldownFor("{\"claimCooldownSeconds\": 0}"));
        assertEquals(15, cooldownFor("{\"claimCooldownSeconds\": 15}"));
        assertEquals(300, cooldownFor("{\"claimCooldownSeconds\": 999}"));
        assertEquals(3, cooldownFor("{}"));
    }

    private int cooldownFor(String json) throws Exception {
        Path folder = tempDir.newFolder().toPath();
        Files.writeString(folder.resolve("votifier.json"), json);
        ConfigManager.loadConfigsFrom(folder);
        return VotifierConfig.getInstance().getClaimCooldownSeconds();
    }

    @Test
    public void voteAnnouncementsCanBeTurnedOffAndHaveAMessage() throws Exception {
        Path folder = tempDir.newFolder().toPath();
        Files.writeString(folder.resolve("votifier.json"), "{\"announceVotes\": false}");
        ConfigManager.loadConfigsFrom(folder);
        assertFalse(VotifierConfig.getInstance().isAnnounceVotes());

        String rendered = MessagesConfig.getChatMessage(
                "vote.broadcast",
                Map.entry("player", "Notch"),
                Map.entry("site", "PlanetMinecraft")
        );
        assertTrue(rendered.contains("Notch"));
        assertTrue(rendered.contains("PlanetMinecraft"));
        assertFalse(rendered.contains("{player}"));
        assertFalse(rendered.contains("{site}"));
    }

    @Test
    public void messagesFormatPlaceholdersAndColourCodes() {
        String rendered = MessagesConfig.getChatMessage(
                "reward.claimed_one",
                Map.entry("used", "1"),
                Map.entry("remaining", "4")
        );
        assertTrue(rendered.contains("§a"));
        assertTrue(rendered.contains("1"));
        assertTrue(rendered.contains("4"));
        assertFalse(rendered.contains("{used}"));
        assertFalse(rendered.contains("{remaining}"));

        String cooldown = MessagesConfig.getChatMessage(
                "reward.cooldown",
                Map.entry("seconds", "3")
        );
        assertTrue(cooldown.contains("3"));
        assertFalse(cooldown.contains("{seconds}"));
        assertFalse(cooldown.endsWith("reward.cooldown"));
    }

    @Test
    public void upgradesExistingMessagesConfigWithCooldownKey() throws Exception {
        Path folder = tempDir.newFolder().toPath();
        Files.writeString(folder.resolve("messages.json"), "{\"messages\":{\"reward.none\":\"none\"}}");
        ConfigManager.loadConfigsFrom(folder);
        String rendered = MessagesConfig.getChatMessage("reward.cooldown", Map.entry("seconds", "2"));
        assertTrue(rendered.contains("2"));
        assertFalse(rendered.contains("{seconds}"));
    }
}
