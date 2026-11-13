package com.mintack.minecraft.votifier;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.server.level.ServerPlayer;

/**
 * Platform bridge for permission checks across Fabric and Forge/NeoForge.
 */
public final class Permissions {
    private Permissions() {
    }

    /**
     * Checks whether an online player holds the given mod permission node.
     *
     * @param serverPlayer online player being checked
     * @param permission   node suffix from {@code permissions.json}, or {@code null} for op-level check
     * @return {@code true} if the player is permitted
     */
    @ExpectPlatform
    public static boolean checkPermission(ServerPlayer serverPlayer, String permission) {
        throw new AssertionError();
    }
}
