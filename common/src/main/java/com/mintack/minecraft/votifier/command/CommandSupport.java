package com.mintack.minecraft.votifier.command;

import com.mintack.minecraft.votifier.Permissions;
import com.mintack.minecraft.votifier.config.CommandsConfig;
import com.mintack.minecraft.votifier.config.MessagesConfig;
import com.mintack.minecraft.votifier.config.PermissionsConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.function.Function;

/**
 * Shared command registration, permission checking, and formatted message helpers.
 */
final class CommandSupport {
    private CommandSupport() {
    }

    static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            String commandKey,
            Function<String, LiteralArgumentBuilder<CommandSourceStack>> builder
    ) {
        CommandsConfig.Command command = CommandsConfig.getInstance().getCommand(commandKey);
        if (command == null || !command.isEnabled()) {
            return;
        }
        dispatcher.register(builder.apply(command.getPrimaryCommand()));
        for (String alias : command.getAliases()) {
            if (alias != null && !alias.isBlank()) {
                dispatcher.register(builder.apply(alias.trim()));
            }
        }
    }

    static boolean hasPermission(ServerPlayer player, String key) {
        return Permissions.checkPermission(player, PermissionsConfig.getInstance().getPermissionNode(key));
    }

    static boolean sourceHasAdminPermission(CommandSourceStack source, String key) {
        if (source.isPlayer()) {
            return hasPermission(source.getPlayer(), key);
        }
        return source.hasPermission(2);
    }

    @SafeVarargs
    static Component message(String key, Map.Entry<String, String>... replacements) {
        return Component.literal(MessagesConfig.getChatMessage(key, replacements));
    }

    @SafeVarargs
    static int fail(CommandSourceStack source, String key, Map.Entry<String, String>... replacements) {
        source.sendFailure(message(key, replacements));
        return 0;
    }
}
