package com.palkess.restocker.network;

import com.palkess.restocker.block.RestockerBlockEntity;
import com.palkess.restocker.menu.RestockerMenu;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ServerPayloadHandler {

    public static void handleAction(RestockerActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.containerMenu instanceof RestockerMenu menu)) {
                return;
            }
            RestockerBlockEntity be = menu.getBlockEntity();
            if (!be.getBlockPos().equals(payload.pos())) {
                return;
            }
            switch (payload.action()) {
                case RestockerActionPayload.ACTION_REFRESH -> be.requestRefresh(player);
                case RestockerActionPayload.ACTION_CRAFT_ALL -> be.craftAll(player);
                case RestockerActionPayload.ACTION_TOGGLE_MODE -> be.toggleExportMode(player);
                default -> {
                }
            }
        });
    }

    private ServerPayloadHandler() {
    }
}
