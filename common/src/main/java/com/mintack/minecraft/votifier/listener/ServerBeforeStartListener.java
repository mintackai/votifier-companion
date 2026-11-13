package com.mintack.minecraft.votifier.listener;

import com.mintack.minecraft.votifier.event.VoteEventListener;
import com.mintack.minecraft.votifier.reward.RewardService;
import dev.architectury.event.events.common.LifecycleEvent;
import net.minecraft.server.MinecraftServer;

/**
 * Binds the starting {@link MinecraftServer} to {@link RewardService} and starts listening for vote events.
 */
public class ServerBeforeStartListener implements LifecycleEvent.ServerState {
    @Override
    public void stateChanged(MinecraftServer server) {
        RewardService.getInstance().onServerBeforeStart(server);
        VoteEventListener.getInstance().onServerStart(server);
    }
}
