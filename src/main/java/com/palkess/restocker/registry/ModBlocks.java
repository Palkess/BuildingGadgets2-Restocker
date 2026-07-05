package com.palkess.restocker.registry;

import com.palkess.restocker.Restocker;
import com.palkess.restocker.block.RestockerBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Restocker.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Restocker.MODID);

    public static final DeferredBlock<RestockerBlock> RESTOCKER = BLOCKS.register("restocker",
            () -> new RestockerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(2.5f)
                    .sound(SoundType.METAL)));

    public static final DeferredItem<BlockItem> RESTOCKER_ITEM = ITEMS.registerSimpleBlockItem(RESTOCKER);

    private ModBlocks() {
    }
}
