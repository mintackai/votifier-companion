package com.mintack.minecraft.votifier.fabric;

import com.mintack.minecraft.votifier.VotifierMod;
import net.fabricmc.api.ModInitializer;

/**
 * Fabric mod entrypoint.
 */
public final class VotifierModFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        VotifierMod.init();
    }
}
