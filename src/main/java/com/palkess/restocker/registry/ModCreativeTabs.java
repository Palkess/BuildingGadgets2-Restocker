package com.palkess.restocker.registry;

import com.palkess.restocker.Restocker;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Restocker.MODID);

    public static final Supplier<CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.bg2restocker"))
                    .icon(() -> new ItemStack(ModBlocks.RESTOCKER_ITEM.get()))
                    .displayItems((parameters, output) -> output.accept(ModBlocks.RESTOCKER_ITEM.get()))
                    .build());

    private ModCreativeTabs() {
    }
}
