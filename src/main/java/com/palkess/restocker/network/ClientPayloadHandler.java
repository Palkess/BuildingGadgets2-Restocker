package com.palkess.restocker.network;

import com.palkess.restocker.screen.RestockerScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ClientPayloadHandler {

    public static void handleSync(RestockerSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (Minecraft.getInstance().screen instanceof RestockerScreen screen
                    && screen.getMenu().getBlockEntity().getBlockPos().equals(payload.pos())) {
                screen.setData(payload.entries(), payload.message(), payload.exportMode());
            }
        });
    }

    private ClientPayloadHandler() {
    }
}
