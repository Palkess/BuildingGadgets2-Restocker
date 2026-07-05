package com.palkess.restocker.client;

import com.palkess.restocker.Restocker;
import com.palkess.restocker.registry.ModMenuTypes;
import com.palkess.restocker.screen.RestockerScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = Restocker.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class RestockerClient {

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.RESTOCKER.get(), RestockerScreen::new);
    }

    private RestockerClient() {
    }
}
