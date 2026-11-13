package com.mintack.minecraft.votifier.neoforge;

import com.mintack.minecraft.votifier.VotifierMod;
import com.mintack.minecraft.votifier.config.PermissionsConfig;
import net.neoforged.fml.common.Mod;

/**
 * NeoForge mod entrypoint.
 */
@Mod(VotifierMod.MOD_ID)
public final class VotifierModNeoForge {
    public VotifierModNeoForge() {
        VotifierMod.init();
        PermissionsConfig.getInstance().getPermissionNodes().values().forEach(PermissionsImpl::registerNode);
    }
}
