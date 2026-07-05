package com.palkess.restocker.registry;

import com.palkess.restocker.Restocker;
import com.palkess.restocker.menu.RestockerMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class ModMenuTypes {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, Restocker.MODID);

    public static final Supplier<MenuType<RestockerMenu>> RESTOCKER =
            MENUS.register("restocker", () -> IMenuTypeExtension.create(RestockerMenu::new));

    private ModMenuTypes() {
    }
}
