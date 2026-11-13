package com.mintack.minecraft.votifier.forge;

import com.mintack.minecraft.votifier.VotifierMod;
import com.mintack.minecraft.votifier.config.PermissionsConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Forge implementation of {@link com.mintack.minecraft.votifier.Permissions}.
 */
@Mod.EventBusSubscriber(modid = VotifierMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PermissionsImpl {
    private static final ConcurrentHashMap<String, PermissionNode<Boolean>> NODES = new ConcurrentHashMap<>();

    private PermissionsImpl() {
    }

    /**
     * Registers a Forge boolean permission node if {@code nodeString} is non-null.
     * A name Forge rejects (e.g. upper-case letters or spaces from {@code permissions.json}) is logged
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
     * Submits all registered nodes when Forge gathers permission nodes.
     */
    @SubscribeEvent
    public static void registerPermissions(PermissionGatherEvent.Nodes event) {
        event.addNodes(NODES.values().toArray(PermissionNode[]::new));
    }

    /**
     * Evaluates a Forge permission node for {@code player}, falling back to default rules if unregistered.
     */
    public static boolean checkPermission(ServerPlayer player, String permission) {
        PermissionNode<Boolean> permissionNode = permission == null ? null : NODES.get(permission);
        if (permissionNode == null) {
            return PermissionsConfig.isAllowedByDefault(permission) || player.hasPermissions(2);
        }
        return PermissionAPI.getPermission(player, permissionNode);
    }
}
