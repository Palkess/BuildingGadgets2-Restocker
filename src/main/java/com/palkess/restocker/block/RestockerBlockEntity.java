package com.palkess.restocker.block;

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
import com.palkess.restocker.Restocker;
import com.palkess.restocker.compat.BG2Compat;
import com.palkess.restocker.menu.RestockerMenu;
import com.palkess.restocker.network.RestockerSyncPayload;
import com.palkess.restocker.registry.ModBlockEntities;
import com.palkess.restocker.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
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
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
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
        mainNode.saveToNBT(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("inventory")) {
            inventory.deserializeNBT(registries, tag.getCompound("inventory"));
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
            return new RestockerSyncPayload(worldPosition, entries, "bg2restocker.msg.no_network");
        }

        KeyCounter stored = grid.getStorageService().getCachedInventory();
        ICraftingService crafting = grid.getCraftingService();
        for (Map.Entry<Item, Long> e : materials.entrySet()) {
            AEItemKey key = AEItemKey.of(e.getKey());
            long have = Math.max(0, stored.get(key));
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
        return new RestockerSyncPayload(worldPosition, entries, "");
    }

    private RestockerSyncPayload message(String translationKey) {
        return new RestockerSyncPayload(worldPosition, List.of(), translationKey);
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
     * Polls pending crafting-plan calculations (they complete on an AE2 background
     * thread) and submits finished plans on the server thread. Once the last plan is
     * handled, pushes a refreshed list to the requesting player.
     */
    public void serverTick() {
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
