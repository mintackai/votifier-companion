package com.mintack.minecraft.votifier.forge;

import com.mintack.minecraft.votifier.VotifierMod;
import com.mintack.minecraft.votifier.config.PermissionsConfig;
import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Forge mod entrypoint.
 */
@Mod(VotifierMod.MOD_ID)
public final class VotifierModForge {
    public VotifierModForge() {
        EventBuses.registerModEventBus(VotifierMod.MOD_ID, FMLJavaModLoadingContext.get().getModEventBus());
        VotifierMod.init();
        PermissionsConfig.getInstance().getPermissionNodes().values().forEach(PermissionsImpl::registerNode);
    }
}
