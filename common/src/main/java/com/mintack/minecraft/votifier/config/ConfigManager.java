package com.mintack.minecraft.votifier.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mintack.minecraft.votifier.VotifierMod;
import dev.architectury.platform.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Creates, loads, sanitizes, and upgrades JSON config files under
 * {@code config/votifier_python_companion/}.
 */
public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private ConfigManager() {
    }

    private static Path defaultConfigFolder() {
        return Platform.getConfigFolder().resolve(VotifierMod.MOD_ID);
    }

    /**
     * Writes default JSON config files for any entries that do not exist yet.
     */
    public static void createDefaultConfigsIfAbsent() {
        createDefaultConfigsIn(defaultConfigFolder());
    }

    /**
     * Reloads existing JSON configs from disk and fills in missing defaults or sanitizes invalid values.
     */
    public static void loadConfigsIfExists() {
        loadConfigsFrom(defaultConfigFolder());
    }

    static void createDefaultConfigsIn(Path folder) {
        writeIfAbsent(folder.resolve("votifier.json"), VotifierConfig.getInstance());
        writeIfAbsent(folder.resolve("commands.json"), CommandsConfig.getInstance());
        writeIfAbsent(folder.resolve("permissions.json"), PermissionsConfig.getInstance());
        writeIfAbsent(folder.resolve("messages.json"), MessagesConfig.getInstance());
    }

    static void loadConfigsFrom(Path folder) {
        loadVotifierConfig(folder.resolve("votifier.json"));
        loadCommandsConfig(folder.resolve("commands.json"));
        loadPermissionsConfig(folder.resolve("permissions.json"));
        loadMessagesConfig(folder.resolve("messages.json"));
    }

    private static void writeIfAbsent(Path path, Object config) {
        if (Files.exists(path)) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(config));
        } catch (IOException e) {
            VotifierMod.LOGGER.error("Failed to create {}", path, e);
        }
    }

    private static void loadVotifierConfig(Path path) {
        if (Files.notExists(path)) {
            return;
        }
        try {
            JsonElement read = JsonParser.parseString(Files.readString(path));
            VotifierConfig loaded = GSON.fromJson(read, VotifierConfig.class);
            if (loaded == null) {
                VotifierMod.LOGGER.error("{} is empty, using built-in defaults", path);
                return;
            }
            loaded.sanitize();
            VotifierConfig.setInstance(loaded);
            if (!GSON.toJsonTree(loaded).equals(read)) {
                backupAndRewrite(path, GSON.toJson(loaded));
            }
        } catch (IOException | JsonParseException | IllegalStateException e) {
            VotifierMod.LOGGER.error("Failed to read {}, using built-in defaults", path, e);
        }
    }

    private static void loadCommandsConfig(Path path) {
        if (Files.notExists(path)) {
            return;
        }
        try {
            CommandsConfig loaded = GSON.fromJson(Files.readString(path), CommandsConfig.class);
            if (loaded == null) {
                VotifierMod.LOGGER.error("{} is empty, using built-in defaults", path);
                return;
            }
            loaded.removeNullEntries();
            CommandsConfig.setInstance(loaded);
            if (loaded.hasMissingCommands()) {
                loaded.addMissingCommands();
                backupAndRewrite(path, GSON.toJson(loaded));
            }
        } catch (IOException | JsonParseException e) {
            VotifierMod.LOGGER.error("Failed to read {}, using built-in defaults", path, e);
        }
    }

    private static void loadPermissionsConfig(Path path) {
        if (Files.notExists(path)) {
            return;
        }
        try {
            PermissionsConfig loaded = GSON.fromJson(Files.readString(path), PermissionsConfig.class);
            if (loaded == null) {
                VotifierMod.LOGGER.error("{} is empty, using built-in defaults", path);
                return;
            }
            loaded.removeNullEntries();
            PermissionsConfig.setInstance(loaded);
            if (loaded.hasMissingPermissions()) {
                loaded.addMissingPermissions();
                backupAndRewrite(path, GSON.toJson(loaded));
            }
        } catch (IOException | JsonParseException e) {
            VotifierMod.LOGGER.error("Failed to read {}, using built-in defaults", path, e);
        }
    }

    private static void loadMessagesConfig(Path path) {
        if (Files.notExists(path)) {
            return;
        }
        try {
            MessagesConfig loaded = GSON.fromJson(Files.readString(path), MessagesConfig.class);
            if (loaded == null) {
                VotifierMod.LOGGER.error("{} is empty, using built-in defaults", path);
                return;
            }
            loaded.removeNullEntries();
            MessagesConfig.setInstance(loaded);
            if (loaded.hasMissingMessages()) {
                loaded.addMissingMessages();
                backupAndRewrite(path, GSON.toJson(loaded));
            }
        } catch (IOException | JsonParseException e) {
            VotifierMod.LOGGER.error("Failed to read {}, using built-in defaults", path, e);
        }
    }

    private static void backupAndRewrite(Path path, String newContent) {
        Path backup = path.resolveSibling(path.getFileName() + "." + LocalDateTime.now().format(BACKUP_TIME));
        try {
            Files.copy(path, backup);
            Files.writeString(path, newContent);
            VotifierMod.LOGGER.info("Updated defaults in {}, previous file saved as {}", path, backup);
        } catch (IOException e) {
            VotifierMod.LOGGER.error("Failed to back up and update {}", path, e);
        }
    }
}
