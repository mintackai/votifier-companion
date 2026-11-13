package com.mintack.minecraft.votifier.config;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/**
 * Permission node names stored in {@code config/votifier_python_companion/permissions.json}.
 */
public class PermissionsConfig {
    private static PermissionsConfig instance;
    private static final List<String> DEFAULT_PLAYER_KEYS = List.of("reward", "reward.all");

    private HashMap<String, String> permissionNodes = new HashMap<>();

    public static PermissionsConfig getInstance() {
        if (instance == null) {
            instance = new PermissionsConfig();
        }
        return instance;
    }

    static void setInstance(PermissionsConfig instance) {
        PermissionsConfig.instance = instance;
    }

    private PermissionsConfig() {
        addDefaults(permissionNodes);
    }

    private static void addDefaults(HashMap<String, String> target) {
        target.put("reward", "commands.reward");
        target.put("reward.all", "commands.reward.all");
        target.put("reward.reload", "admin.commands.reward.reload");
    }

    /**
     * Returns whether a permission node suffix defaults to allowed for non-op players.
     *
     * @param permissionNode permission suffix (e.g. {@code "commands.reward"})
     * @return {@code true} if normal players may use it when no permission mod overrides it
     */
    public static boolean isAllowedByDefault(String permissionNode) {
        if (permissionNode == null) {
            return false;
        }
        PermissionsConfig config = getInstance();
        for (String key : DEFAULT_PLAYER_KEYS) {
            if (permissionNode.equals(config.getPermissionNode(key))) {
                return true;
            }
        }
        return false;
    }

    public HashMap<String, String> getPermissionNodes() {
        return permissionNodes;
    }

    public String getPermissionNode(String key) {
        return permissionNodes.get(key);
    }

    /**
     * Removes null or blank permission mappings loaded from disk.
     */
    void removeNullEntries() {
        if (permissionNodes == null) {
            permissionNodes = new HashMap<>();
        }
        permissionNodes.values().removeIf(node -> Objects.isNull(node) || node.isBlank());
    }

    /**
     * Checks whether the config lacks any built-in permission keys.
     *
     * @return {@code true} if defaults need to be added
     */
    boolean hasMissingPermissions() {
        HashMap<String, String> defaults = new HashMap<>();
        addDefaults(defaults);
        return !permissionNodes.keySet().containsAll(defaults.keySet());
    }

    /**
     * Adds missing built-in permission nodes without overwriting customized values.
     */
    void addMissingPermissions() {
        HashMap<String, String> defaults = new HashMap<>();
        addDefaults(defaults);
        defaults.forEach(permissionNodes::putIfAbsent);
    }
}
