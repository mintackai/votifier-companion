package com.mintack.minecraft.votifier;

import com.mintack.minecraft.votifier.command.RewardCommand;
import com.mintack.minecraft.votifier.config.ConfigManager;
import com.mintack.minecraft.votifier.config.VotifierConfig;
import com.mintack.minecraft.votifier.listener.ServerBeforeStartListener;
import com.mintack.minecraft.votifier.listener.ServerStoppedListener;
import com.mintack.minecraft.votifier.reward.RewardService;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint for Votifier Python Companion.
 */
public final class VotifierMod {
    public static final String MOD_ID = "votifier_python_companion";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private VotifierMod() {
    }

    /**
     * Loads configs, creates defaults when missing, and registers server lifecycle and command listeners.
     */
    public static void init() {
        ConfigManager.loadConfigsIfExists();
        ConfigManager.createDefaultConfigsIfAbsent();
        if (!VotifierConfig.getInstance().isConfigured()) {
            LOGGER.warn("apiKey is empty or apiUrl is invalid in votifier.json; /reward will stay disabled until configured.");
        }
        warnIfApiKeyUnencrypted();
        LifecycleEvent.SERVER_BEFORE_START.register(new ServerBeforeStartListener());
        LifecycleEvent.SERVER_STOPPED.register(new ServerStoppedListener());
        PlayerEvent.PLAYER_JOIN.register(player -> RewardService.getInstance().onPlayerJoin(player));
        PlayerEvent.PLAYER_QUIT.register(player -> RewardService.getInstance().onPlayerQuit());
        CommandRegistrationEvent.EVENT.register(new RewardCommand());
    }

    /**
     * Logs a warning when the configured API URL would send the API key over plain HTTP to another host.
     */
    public static void warnIfApiKeyUnencrypted() {
        if (VotifierConfig.getInstance().sendsApiKeyUnencrypted()) {
            LOGGER.warn("apiUrl in votifier.json uses plain http to a remote host; the API key is sent unencrypted. Use https or a private network.");
        }
    }
}
