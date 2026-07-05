package com.palkess.restocker.compat;

import com.palkess.restocker.Restocker;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Reflection bridge to Building Gadgets 2. BG2 publishes no API jar, so all access
 * goes through reflection against the classes documented in its source:
 * templates live server-side in BG2Data (SavedData on the overworld), keyed by the
 * gadget's UUID; each entry is a StatePos with a public BlockState field.
 */
public final class BG2Compat {
    public static final String BG2_MODID = "buildinggadgets2";

    private static volatile Handles handles;
    private static volatile boolean broken = false;

    public static boolean isLoaded() {
        return ModList.get().isLoaded(BG2_MODID);
    }

    public static boolean isCopyPasteGadget(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return BG2_MODID.equals(id.getNamespace()) && id.getPath().contains("copy_paste");
    }

    /**
     * Reads the material list of the template currently loaded in the given gadget.
     *
     * @return items and counts sorted by registry id; an empty map if the gadget has
     *         no copied template; {@code null} if BG2 is absent or reflection failed.
     */
    @Nullable
    public static Map<Item, Long> getMaterialList(ServerLevel level, ItemStack gadget) {
        if (!isLoaded() || broken) {
            return null;
        }
        try {
            Handles h = handles();

            UUID uuid = (UUID) h.getUuid.invoke(null, gadget);
            if (uuid == null) {
                return Map.of();
            }

            Object bg2Data = h.getData.invoke(null, level.getServer().overworld());
            List<?> statePosList = (List<?>) h.getCopyPasteList.invoke(bg2Data, uuid, false);
            if (statePosList == null || statePosList.isEmpty()) {
                return Map.of();
            }

            Map<Item, Long> counts = new TreeMap<>(Comparator.comparing(BuiltInRegistries.ITEM::getKey));
            for (Object statePos : statePosList) {
                BlockState state = (BlockState) h.stateField.get(statePos);
                if (state == null || state.isAir()) {
                    continue;
                }
                Item item = state.getBlock().asItem();
                if (item == Items.AIR) {
                    continue; // block with no item form (fluids etc.)
                }
                counts.merge(item, 1L, Long::sum);
            }
            return counts;
        } catch (Throwable t) {
            broken = true;
            Restocker.LOGGER.error("Failed to read Building Gadgets 2 template via reflection. "
                    + "This Restocker build may not be compatible with the installed BG2 version.", t);
            return null;
        }
    }

    private static Handles handles() throws Exception {
        Handles h = handles;
        if (h == null) {
            h = new Handles();
            handles = h;
        }
        return h;
    }

    private static final class Handles {
        final Method getUuid;
        final Method getData;
        final Method getCopyPasteList;
        final Field stateField;

        Handles() throws Exception {
            Class<?> gadgetNbt = Class.forName("com.direwolf20.buildinggadgets2.util.GadgetNBT");
            getUuid = gadgetNbt.getMethod("getUUID", ItemStack.class);

            Class<?> bg2Data = Class.forName("com.direwolf20.buildinggadgets2.common.worlddata.BG2Data");
            getData = Arrays.stream(bg2Data.getMethods())
                    .filter(m -> Modifier.isStatic(m.getModifiers())
                            && m.getName().equals("get")
                            && m.getParameterCount() == 1
                            && m.getParameterTypes()[0].isAssignableFrom(ServerLevel.class))
                    .findFirst()
                    .orElseThrow(() -> new NoSuchMethodException("BG2Data.get(ServerLevel)"));
            getCopyPasteList = Arrays.stream(bg2Data.getMethods())
                    .filter(m -> m.getName().equals("getCopyPasteList") && m.getParameterCount() == 2)
                    .findFirst()
                    .orElseThrow(() -> new NoSuchMethodException("BG2Data.getCopyPasteList(UUID, boolean)"));

            Class<?> statePos = Class.forName("com.direwolf20.buildinggadgets2.util.datatypes.StatePos");
            stateField = statePos.getField("state");
        }
    }

    private BG2Compat() {
    }
}
