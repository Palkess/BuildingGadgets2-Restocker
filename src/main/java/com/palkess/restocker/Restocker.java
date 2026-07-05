package com.palkess.restocker;

import appeng.api.AECapabilities;
import com.mojang.logging.LogUtils;
import com.palkess.restocker.network.ClientPayloadHandler;
import com.palkess.restocker.network.RestockerActionPayload;
import com.palkess.restocker.network.RestockerSyncPayload;
import com.palkess.restocker.network.ServerPayloadHandler;
import com.palkess.restocker.registry.ModBlockEntities;
import com.palkess.restocker.registry.ModBlocks;
import com.palkess.restocker.registry.ModCreativeTabs;
import com.palkess.restocker.registry.ModMenuTypes;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

@Mod(Restocker.MODID)
public class Restocker {
    public static final String MODID = "bg2restocker";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Restocker(IEventBus modEventBus) {
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlocks.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModMenuTypes.MENUS.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);

        modEventBus.addListener(this::registerCapabilities);
        modEventBus.addListener(this::registerPayloads);
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        // Exposes the block entity as an in-world grid node host so AE2 cables connect to it.
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST,
                ModBlockEntities.RESTOCKER.get(),
                (be, context) -> be);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(RestockerSyncPayload.TYPE, RestockerSyncPayload.STREAM_CODEC,
                ClientPayloadHandler::handleSync);
        registrar.playToServer(RestockerActionPayload.TYPE, RestockerActionPayload.STREAM_CODEC,
                ServerPayloadHandler::handleAction);
    }
}
