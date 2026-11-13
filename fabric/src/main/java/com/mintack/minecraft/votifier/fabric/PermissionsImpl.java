package com.mintack.minecraft.votifier.fabric;

import com.mintack.minecraft.votifier.VotifierMod;
import com.mintack.minecraft.votifier.config.PermissionsConfig;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric implementation of {@link com.mintack.minecraft.votifier.Permissions}.
 */
public final class PermissionsImpl {
    private PermissionsImpl() {
    }

    /**
     * Checks a player's permission via Lucko Fabric Permissions API, defaulting player nodes to allowed
     * and admin nodes to op level 2.
     */
    public static boolean checkPermission(ServerPlayer serverPlayer, String permission) {
        if (permission == null) {
            return serverPlayer.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
        }
        String node = VotifierMod.MOD_ID + "." + permission;
        return PermissionsConfig.isAllowedByDefault(permission)
                ? Permissions.check(serverPlayer, node, true)
                : Permissions.check(serverPlayer, node, 2);
    }
}
