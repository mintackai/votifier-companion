package com.mintack.minecraft.votifier.neoforge;

import com.mintack.minecraft.votifier.VotifierMod;
import com.mintack.minecraft.votifier.config.PermissionsConfig;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

import java.util.concurrent.ConcurrentHashMap;

/**
 * NeoForge implementation of {@link com.mintack.minecraft.votifier.Permissions}.
 */
@EventBusSubscriber(modid = VotifierMod.MOD_ID)
public final class PermissionsImpl {
    private static final ConcurrentHashMap<String, PermissionNode<Boolean>> NODES = new ConcurrentHashMap<>();

    private PermissionsImpl() {
    }

    /**
     * Registers a NeoForge boolean permission node if {@code nodeString} is non-null.
     * Any runtime error registering a node from {@code permissions.json} is logged
     * and skipped, leaving that permission on the default rules instead of failing mod loading.
     */
    public static void registerNode(String nodeString) {
        if (nodeString == null) {
            return;
        }
        try {
            NODES.computeIfAbsent(nodeString, nodeName -> new PermissionNode<>(
                    VotifierMod.MOD_ID,
                    nodeName,
                    PermissionTypes.BOOLEAN,
                    (player, uuid, context) -> PermissionsConfig.isAllowedByDefault(nodeName)
                            || (player != null && player.hasPermissions(2))
            ));
        } catch (RuntimeException e) {
            VotifierMod.LOGGER.error("Invalid permission node '{}' in permissions.json; using default rules for it", nodeString, e);
        }
    }

    /**
     * Submits all registered nodes when NeoForge gathers permission nodes.
     */
    @SubscribeEvent
    public static void registerPermissions(PermissionGatherEvent.Nodes event) {
        event.addNodes(NODES.values().toArray(PermissionNode[]::new));
    }

    /**
     * Evaluates a NeoForge permission node for {@code player}, falling back to default rules if unregistered.
     */
    public static boolean checkPermission(ServerPlayer player, String permission) {
        PermissionNode<Boolean> permissionNode = permission == null ? null : NODES.get(permission);
        if (permissionNode == null) {
            return PermissionsConfig.isAllowedByDefault(permission) || player.hasPermissions(2);
        }
        return PermissionAPI.getPermission(player, permissionNode);
    }
}
