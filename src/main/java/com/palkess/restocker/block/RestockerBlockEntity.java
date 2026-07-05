package com.palkess.restocker.block;

import appeng.api.config.Actionable;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import com.palkess.restocker.Restocker;
import com.palkess.restocker.compat.BG2Compat;
import com.palkess.restocker.menu.RestockerMenu;
import com.palkess.restocker.network.RestockerSyncPayload;
import com.palkess.restocker.registry.ModBlockEntities;
import com.palkess.restocker.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;

public class RestockerBlockEntity extends BlockEntity implements MenuProvider, IInWorldGridNodeHost, IActionHost {

    public static final byte STATUS_AVAILABLE = 0;
    public static final byte STATUS_CRAFTABLE = 1;
    public static final byte STATUS_MISSING = 2;
    public static final byte STATUS_UNKNOWN = 3;

    private final ItemStackHandler inventory = new ItemStackHandler(1) {
        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return BG2Compat.isCopyPasteGadget(stack);
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            syncToViewers();
        }
    };

    private final IManagedGridNode mainNode = GridHelper.createManagedNode(this, NodeListener.INSTANCE)
            .setInWorldNode(true)
            .setTagName("main")
            .setIdlePowerUsage(1.0)
            .setExposedOnSides(EnumSet.allOf(Direction.class))
            // Shown in the Network Tool's status screen machine list.
            .setVisualRepresentation(new ItemStack(ModBlocks.RESTOCKER_ITEM.get()));

    private final IActionSource actionSource = IActionSource.ofMachine(this);

    private final ICraftingSimulationRequester simRequester = new ICraftingSimulationRequester() {
        @Override
        public IActionSource getActionSource() {
            return actionSource;
        }
    };

    private final List<PendingCraft> pendingCrafts = new ArrayList<>();

    /**
     * When true, requested materials are moved into an adjacent inventory instead of
     * staying in the ME network, reserving them from other crafting jobs.
     */
    private boolean exportMode = false;

    /** Items still owed to the adjacent inventory (drained by {@link #processExportQueue()}). */
    private final Map<Item, Long> exportQueue = new LinkedHashMap<>();

    public RestockerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RESTOCKER.get(), pos, state);
    }

    public ItemStackHandler getInventory() {
        return inventory;
    }

    // --- Lifecycle ---

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            GridHelper.onFirstTick(this, be -> be.mainNode.create(be.level, be.worldPosition));
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        mainNode.destroy();
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        mainNode.destroy();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("inventory", inventory.serializeNBT(registries));
        tag.putBoolean("exportMode", exportMode);
        ListTag queueTag = new ListTag();
        for (Map.Entry<Item, Long> e : exportQueue.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("id", BuiltInRegistries.ITEM.getKey(e.getKey()).toString());
            entryTag.putLong("count", e.getValue());
            queueTag.add(entryTag);
        }
        tag.put("exportQueue", queueTag);
        mainNode.saveToNBT(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("inventory")) {
            inventory.deserializeNBT(registries, tag.getCompound("inventory"));
        }
        exportMode = tag.getBoolean("exportMode");
        exportQueue.clear();
        for (int i = 0; i < tag.getList("exportQueue", ListTag.TAG_COMPOUND).size(); i++) {
            CompoundTag entryTag = tag.getList("exportQueue", ListTag.TAG_COMPOUND).getCompound(i);
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(entryTag.getString("id")));
            long count = entryTag.getLong("count");
            if (item != net.minecraft.world.item.Items.AIR && count > 0) {
                exportQueue.put(item, count);
            }
        }
        mainNode.loadFromNBT(tag);
    }

    public void dropContents() {
        if (level == null) {
            return;
        }
        ItemStack stack = inventory.getStackInSlot(0);
        if (!stack.isEmpty()) {
            inventory.setStackInSlot(0, ItemStack.EMPTY);
            Containers.dropItemStack(level,
                    worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, stack);
        }
    }

    // --- AE2 node ---

    @Nullable
    @Override
    public IGridNode getGridNode(Direction dir) {
        return mainNode.getNode();
    }

    @Nullable
    @Override
    public IGridNode getActionableNode() {
        return mainNode.getNode();
    }

    // --- Menu ---

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.bg2restocker.restocker");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new RestockerMenu(containerId, playerInventory, this);
    }

    // --- Material list / status ---

    public void requestRefresh(ServerPlayer player) {
        sendSync(player);
    }

    public void toggleExportMode(ServerPlayer player) {
        exportMode = !exportMode;
        exportQueue.clear();
        setChanged();
        sendSync(player);
    }

    public RestockerSyncPayload buildSyncPayload() {
        ItemStack gadget = inventory.getStackInSlot(0);
        if (gadget.isEmpty()) {
            return message("bg2restocker.msg.no_gadget");
        }
        if (!BG2Compat.isLoaded()) {
            return message("bg2restocker.msg.bg2_missing");
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return message("bg2restocker.msg.read_failed");
        }

        Map<Item, Long> materials = BG2Compat.getMaterialList(serverLevel, gadget);
        if (materials == null) {
            return message("bg2restocker.msg.read_failed");
        }
        if (materials.isEmpty()) {
            return message("bg2restocker.msg.no_template");
        }

        IGrid grid = mainNode.getGrid();
        List<RestockerSyncPayload.Entry> entries = new ArrayList<>(materials.size());
        if (grid == null || !mainNode.isActive()) {
            for (Map.Entry<Item, Long> e : materials.entrySet()) {
                entries.add(new RestockerSyncPayload.Entry(new ItemStack(e.getKey()), e.getValue(), 0, STATUS_UNKNOWN));
            }
            return new RestockerSyncPayload(worldPosition, entries, "bg2restocker.msg.no_network", exportMode);
        }

        // In export mode, materials already moved to the adjacent inventory count as available.
        Map<Item, Long> adjacent = exportMode ? countAdjacentItems() : Map.of();
        KeyCounter stored = grid.getStorageService().getCachedInventory();
        ICraftingService crafting = grid.getCraftingService();
        for (Map.Entry<Item, Long> e : materials.entrySet()) {
            AEItemKey key = AEItemKey.of(e.getKey());
            long have = Math.max(0, stored.get(key)) + adjacent.getOrDefault(e.getKey(), 0L);
            byte status;
            if (have >= e.getValue()) {
                status = STATUS_AVAILABLE;
            } else if (crafting.isCraftable(key)) {
                status = STATUS_CRAFTABLE;
            } else {
                status = STATUS_MISSING;
            }
            entries.add(new RestockerSyncPayload.Entry(new ItemStack(e.getKey()), e.getValue(), have, status));
        }
        return new RestockerSyncPayload(worldPosition, entries, "", exportMode);
    }

    private RestockerSyncPayload message(String translationKey) {
        return new RestockerSyncPayload(worldPosition, List.of(), translationKey, exportMode);
    }

    // --- Adjacent inventory access (export mode) ---

    /**
     * Item handlers of neighbouring blocks, skipping anything that is itself an AE2
     * network block (an adjacent ME Interface would loop exported items straight
     * back into the network).
     */
    private List<IItemHandler> adjacentInventories() {
        List<IItemHandler> handlers = new ArrayList<>();
        if (level == null) {
            return handlers;
        }
        for (Direction dir : Direction.values()) {
            BlockPos neighbour = worldPosition.relative(dir);
            if (level.getBlockEntity(neighbour) instanceof IInWorldGridNodeHost) {
                continue;
            }
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, neighbour, dir.getOpposite());
            if (handler != null) {
                handlers.add(handler);
            }
        }
        return handlers;
    }

    private Map<Item, Long> countAdjacentItems() {
        Map<Item, Long> counts = new HashMap<>();
        for (IItemHandler handler : adjacentInventories()) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty()) {
                    counts.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
                }
            }
        }
        return counts;
    }

    private static ItemStack insertInto(List<IItemHandler> targets, ItemStack stack, boolean simulate) {
        for (IItemHandler handler : targets) {
            stack = ItemHandlerHelper.insertItemStacked(handler, stack, simulate);
            if (stack.isEmpty()) {
                break;
            }
        }
        return stack;
    }

    private void sendSync(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, buildSyncPayload());
    }

    private void syncToViewers() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        for (ServerPlayer player : serverLevel.players()) {
            if (player.containerMenu instanceof RestockerMenu menu && menu.getBlockEntity() == this) {
                sendSync(player);
            }
        }
    }

    // --- Crafting ---

    public void craftAll(ServerPlayer player) {
        IGrid grid = mainNode.getGrid();
        if (grid == null || !mainNode.isActive()) {
            sendSync(player);
            return;
        }
        if (exportMode) {
            craftAndExport(player, grid);
            return;
        }
        ICraftingService crafting = grid.getCraftingService();
        for (RestockerSyncPayload.Entry entry : buildSyncPayload().entries()) {
            if (entry.status() != STATUS_CRAFTABLE) {
                continue;
            }
            long deficit = entry.needed() - entry.have();
            if (deficit <= 0) {
                continue;
            }
            AEItemKey key = AEItemKey.of(entry.stack());
            Future<ICraftingPlan> future = crafting.beginCraftingCalculation(
                    level, simRequester, key, deficit, CalculationStrategy.CRAFT_LESS);
            pendingCrafts.add(new PendingCraft(future, player.getUUID()));
        }
        if (pendingCrafts.isEmpty()) {
            sendSync(player);
        }
    }

    /**
     * Export mode: queues everything still missing from the adjacent inventory for
     * export, and submits crafting jobs for whatever the network cannot cover from
     * stock. The export queue is drained in {@link #processExportQueue()} as items
     * become available (including as crafting jobs finish).
     */
    private void craftAndExport(ServerPlayer player, IGrid grid) {
        ItemStack gadget = inventory.getStackInSlot(0);
        Map<Item, Long> materials = level instanceof ServerLevel serverLevel && !gadget.isEmpty()
                ? BG2Compat.getMaterialList(serverLevel, gadget)
                : null;
        if (materials == null || materials.isEmpty()) {
            sendSync(player);
            return;
        }
        Map<Item, Long> inStorage = countAdjacentItems();
        KeyCounter stored = grid.getStorageService().getCachedInventory();
        ICraftingService crafting = grid.getCraftingService();
        for (Map.Entry<Item, Long> e : materials.entrySet()) {
            long toExport = e.getValue() - inStorage.getOrDefault(e.getKey(), 0L);
            if (toExport <= 0) {
                continue;
            }
            // Replace (not add to) any previous target: it is recomputed from the
            // current storage contents, so clicking twice does not double the order.
            exportQueue.put(e.getKey(), toExport);
            AEItemKey key = AEItemKey.of(e.getKey());
            long toCraft = toExport - Math.max(0, stored.get(key));
            if (toCraft > 0 && crafting.isCraftable(key)) {
                Future<ICraftingPlan> future = crafting.beginCraftingCalculation(
                        level, simRequester, key, toCraft, CalculationStrategy.CRAFT_LESS);
                pendingCrafts.add(new PendingCraft(future, player.getUUID()));
            }
        }
        setChanged();
        sendSync(player);
    }

    /**
     * Moves queued export items from the ME network into the adjacent inventory,
     * one stack per item per tick. Items the network does not have yet (still being
     * crafted) simply stay queued until they appear.
     */
    private void processExportQueue() {
        if (exportQueue.isEmpty()) {
            return;
        }
        IGrid grid = mainNode.getGrid();
        if (grid == null || !mainNode.isActive()) {
            return;
        }
        List<IItemHandler> targets = adjacentInventories();
        if (targets.isEmpty()) {
            return;
        }
        MEStorage storage = grid.getStorageService().getInventory();
        boolean changed = false;
        Iterator<Map.Entry<Item, Long>> it = exportQueue.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Item, Long> entry = it.next();
            Item item = entry.getKey();
            long remaining = entry.getValue();
            AEItemKey key = AEItemKey.of(item);
            int batch = (int) Math.min(remaining, new ItemStack(item).getMaxStackSize());
            long canPull = storage.extract(key, batch, Actionable.SIMULATE, actionSource);
            if (canPull <= 0) {
                continue;
            }
            ItemStack simLeft = insertInto(targets, new ItemStack(item, (int) canPull), true);
            int fits = (int) canPull - simLeft.getCount();
            if (fits <= 0) {
                continue;
            }
            long pulled = storage.extract(key, fits, Actionable.MODULATE, actionSource);
            if (pulled <= 0) {
                continue;
            }
            ItemStack leftover = insertInto(targets, new ItemStack(item, (int) pulled), false);
            long moved = pulled - leftover.getCount();
            if (!leftover.isEmpty()) {
                // Insert raced against another machine; put the overflow back.
                storage.insert(key, leftover.getCount(), Actionable.MODULATE, actionSource);
            }
            if (moved > 0) {
                changed = true;
                remaining -= moved;
                if (remaining <= 0) {
                    it.remove();
                } else {
                    entry.setValue(remaining);
                }
            }
        }
        if (changed) {
            setChanged();
        }
    }

    /**
     * Polls pending crafting-plan calculations (they complete on an AE2 background
     * thread) and submits finished plans on the server thread. Once the last plan is
     * handled, pushes a refreshed list to the requesting player.
     */
    public void serverTick() {
        processExportQueue();
        if (pendingCrafts.isEmpty()) {
            return;
        }
        UUID lastRequester = null;
        Iterator<PendingCraft> it = pendingCrafts.iterator();
        while (it.hasNext()) {
            PendingCraft pending = it.next();
            if (!pending.future().isDone()) {
                continue;
            }
            it.remove();
            lastRequester = pending.playerId();
            try {
                ICraftingPlan plan = pending.future().get();
                IGrid grid = mainNode.getGrid();
                if (plan != null && grid != null && !plan.simulation()) {
                    grid.getCraftingService().submitJob(plan, null, null, false, actionSource);
                }
            } catch (Exception e) {
                Restocker.LOGGER.warn("Crafting job calculation failed", e);
            }
        }
        if (pendingCrafts.isEmpty() && lastRequester != null && level instanceof ServerLevel serverLevel) {
            ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(lastRequester);
            if (player != null && player.containerMenu instanceof RestockerMenu menu && menu.getBlockEntity() == this) {
                sendSync(player);
            }
        }
    }

    private record PendingCraft(Future<ICraftingPlan> future, UUID playerId) {
    }

    private static final class NodeListener implements IGridNodeListener<RestockerBlockEntity> {
        static final NodeListener INSTANCE = new NodeListener();

        @Override
        public void onSaveChanges(RestockerBlockEntity owner, IGridNode node) {
            owner.setChanged();
        }
    }
}
