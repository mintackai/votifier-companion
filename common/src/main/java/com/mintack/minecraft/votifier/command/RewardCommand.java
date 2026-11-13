package com.mintack.minecraft.votifier.command;

import com.mintack.minecraft.votifier.VotifierMod;
import com.mintack.minecraft.votifier.config.ConfigManager;
import com.mintack.minecraft.votifier.config.VotifierConfig;
import com.mintack.minecraft.votifier.event.VoteEventListener;
import com.mintack.minecraft.votifier.reward.RewardService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/**
 * Registers {@code /reward}, {@code /reward all}, and {@code /reward reload}.
 */
public class RewardCommand implements CommandRegistrationEvent {
    @Override
    public void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registry, Commands.CommandSelection selection) {
        CommandSupport.register(dispatcher, "reward", this::createRewardCommand);
    }

    private LiteralArgumentBuilder<CommandSourceStack> createRewardCommand(String literal) {
        return Commands.literal(literal)
                .requires(RewardCommand::canSeeRootCommand)
                .executes(context -> executeClaim(context, false))
                .then(Commands.literal("all")
                        .requires(source -> !source.isPlayer() || CommandSupport.hasPermission(source.getPlayer(), "reward.all"))
                        .executes(context -> executeClaim(context, true)))
                .then(Commands.literal("reload")
                        .requires(source -> CommandSupport.sourceHasAdminPermission(source, "reward.reload"))
                        .executes(RewardCommand::executeReload));
    }

    private static boolean canSeeRootCommand(CommandSourceStack source) {
        if (!source.isPlayer()) {
            return CommandSupport.sourceHasAdminPermission(source, "reward.reload");
        }
        ServerPlayer player = source.getPlayer();
        return CommandSupport.hasPermission(player, "reward")
                || CommandSupport.hasPermission(player, "reward.all")
                || CommandSupport.hasPermission(player, "reward.reload");
    }

    private static int executeClaim(CommandContext<CommandSourceStack> context, boolean claimAll) {
        CommandSourceStack source = context.getSource();
        if (!source.isPlayer()) {
            return CommandSupport.fail(source, "player_only");
        }
        ServerPlayer player = source.getPlayer();
        String permKey = claimAll ? "reward.all" : "reward";
        if (!CommandSupport.hasPermission(player, permKey)) {
            return CommandSupport.fail(source, "no_permission");
        }
        return RewardService.getInstance().startClaim(player, claimAll) ? 1 : 0;
    }

    private static int executeReload(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!CommandSupport.sourceHasAdminPermission(source, "reward.reload")) {
            return CommandSupport.fail(source, "no_permission");
        }
        ConfigManager.loadConfigsIfExists();
        if (!VotifierConfig.getInstance().isConfigured()) {
            VotifierMod.LOGGER.warn("apiKey is still empty or apiUrl is invalid in votifier.json after reload.");
        }
        VotifierMod.warnIfApiKeyUnencrypted();
        VoteEventListener.getInstance().restart();
        source.sendSuccess(() -> CommandSupport.message("reward.reload"), true);
        return 1;
    }
}
