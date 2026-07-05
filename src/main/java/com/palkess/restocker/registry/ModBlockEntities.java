package com.palkess.restocker.registry;

import com.palkess.restocker.Restocker;
import com.palkess.restocker.block.RestockerBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Restocker.MODID);

    public static final Supplier<BlockEntityType<RestockerBlockEntity>> RESTOCKER =
            BLOCK_ENTITIES.register("restocker", () -> BlockEntityType.Builder
                    .of(RestockerBlockEntity::new, ModBlocks.RESTOCKER.get())
                    .build(null));

    private ModBlockEntities() {
    }
}
