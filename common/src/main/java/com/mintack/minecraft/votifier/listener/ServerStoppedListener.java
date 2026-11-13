package com.mintack.minecraft.votifier.listener;

import com.mintack.minecraft.votifier.event.VoteEventListener;
import com.mintack.minecraft.votifier.reward.RewardService;
import dev.architectury.event.events.common.LifecycleEvent;
import net.minecraft.server.MinecraftServer;

/**
 * Detaches the stopping {@link MinecraftServer} and cleans up HTTP workers.
 */
public class ServerStoppedListener implements LifecycleEvent.ServerState {
    @Override
    public void stateChanged(MinecraftServer server) {
        VoteEventListener.getInstance().onServerStopped();
        RewardService.getInstance().onServerStopped();
    }
}
