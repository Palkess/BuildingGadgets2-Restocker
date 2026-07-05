package com.palkess.restocker.screen;

import com.palkess.restocker.block.RestockerBlockEntity;
import com.palkess.restocker.menu.RestockerMenu;
import com.palkess.restocker.network.RestockerActionPayload;
import com.palkess.restocker.network.RestockerSyncPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class RestockerScreen extends AbstractContainerScreen<RestockerMenu> {
    private static final int LIST_X = 8;
    private static final int LIST_Y = 17;
    private static final int LIST_W = 160;
    private static final int ROW_H = 16;
    private static final int VISIBLE_ROWS = 5;
    private static final int LIST_H = ROW_H * VISIBLE_ROWS;

    private static final int COLOR_AVAILABLE = 0xFF6FE26F;
    private static final int COLOR_CRAFTABLE = 0xFFF7D64A;
    private static final int COLOR_MISSING = 0xFFFF6A5E;
    private static final int COLOR_UNKNOWN = 0xFFB0B0B0;

    private final List<Row> rows = new ArrayList<>();
    private String message = "";
    private boolean hasCraftable = false;
    private boolean craftPending = false;
    private boolean exportMode = false;
    private int scroll = 0;
    private Button craftButton;
    private Button modeButton;

    public RestockerScreen(RestockerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 222;
        this.inventoryLabelY = 129;
    }

    @Override
    protected void init() {
        super.init();
        craftButton = addRenderableWidget(Button.builder(
                        Component.translatable("bg2restocker.gui.craft_all"), b -> sendCraftAll())
                .bounds(leftPos + 48, topPos + 102, 76, 20)
                .build());
        craftButton.active = hasCraftable && !craftPending;
        modeButton = addRenderableWidget(Button.builder(
                        Component.empty(), b -> send(RestockerActionPayload.ACTION_TOGGLE_MODE))
                .bounds(leftPos + 128, topPos + 102, 40, 20)
                .build());
        updateModeButton();
        send(RestockerActionPayload.ACTION_REFRESH);
    }

    private void updateModeButton() {
        String key = exportMode ? "bg2restocker.gui.mode.export" : "bg2restocker.gui.mode.network";
        modeButton.setMessage(Component.translatable(key));
        modeButton.setTooltip(Tooltip.create(Component.translatable(key + ".tooltip")));
    }

    private void sendCraftAll() {
        craftPending = true;
        craftButton.active = false;
        send(RestockerActionPayload.ACTION_CRAFT_ALL);
    }

    private void send(int action) {
        PacketDistributor.sendToServer(
                new RestockerActionPayload(menu.getBlockEntity().getBlockPos(), action));
    }

    public void setData(List<RestockerSyncPayload.Entry> entries, String message, boolean exportMode) {
        this.message = message;
        this.craftPending = false;
        this.hasCraftable = false;
        this.exportMode = exportMode;
        if (modeButton != null) {
            updateModeButton();
        }
        this.rows.clear();
        addSection(entries, RestockerBlockEntity.STATUS_MISSING, "bg2restocker.gui.missing", COLOR_MISSING);
        addSection(entries, RestockerBlockEntity.STATUS_CRAFTABLE, "bg2restocker.gui.craftable", COLOR_CRAFTABLE);
        addSection(entries, RestockerBlockEntity.STATUS_AVAILABLE, "bg2restocker.gui.available", COLOR_AVAILABLE);
        addSection(entries, RestockerBlockEntity.STATUS_UNKNOWN, "bg2restocker.gui.unknown", COLOR_UNKNOWN);
        scroll = Mth.clamp(scroll, 0, Math.max(0, rows.size() - VISIBLE_ROWS));
        if (craftButton != null) {
            craftButton.active = hasCraftable;
        }
    }

    private void addSection(List<RestockerSyncPayload.Entry> entries, byte status, String headerKey, int color) {
        List<RestockerSyncPayload.Entry> matching = entries.stream()
                .filter(e -> e.status() == status)
                .toList();
        if (matching.isEmpty()) {
            return;
        }
        if (status == RestockerBlockEntity.STATUS_CRAFTABLE) {
            hasCraftable = true;
        }
        rows.add(Row.header(Component.translatable(headerKey, matching.size()), color));
        for (RestockerSyncPayload.Entry entry : matching) {
            rows.add(Row.item(entry, color));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        Row hovered = rowAt(mouseX, mouseY);
        if (hovered != null && hovered.entry() != null) {
            graphics.renderTooltip(font, hovered.entry().stack(), mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int l = leftPos;
        int t = topPos;
        graphics.fill(l, t, l + imageWidth, t + imageHeight, 0xFF1E1E22);
        graphics.fill(l + 1, t + 1, l + imageWidth - 1, t + imageHeight - 1, 0xFFC6C6C6);
        graphics.fill(l + LIST_X - 1, t + LIST_Y - 1, l + LIST_X + LIST_W + 1, t + LIST_Y + LIST_H + 1, 0xFF17171A);
        for (Slot slot : menu.slots) {
            graphics.fill(l + slot.x - 1, t + slot.y - 1, l + slot.x + 17, t + slot.y + 17, 0xFF37373E);
            graphics.fill(l + slot.x, t + slot.y, l + slot.x + 16, t + slot.y + 16, 0xFF8B8B8B);
        }
        renderList(graphics);
    }

    private void renderList(GuiGraphics graphics) {
        int l = leftPos;
        int t = topPos;
        if (rows.isEmpty()) {
            String key = message.isEmpty() ? "bg2restocker.msg.no_gadget" : message;
            List<FormattedCharSequence> lines = font.split(Component.translatable(key), LIST_W - 12);
            int y = t + LIST_Y + (LIST_H - lines.size() * 10) / 2;
            for (FormattedCharSequence line : lines) {
                int w = font.width(line);
                graphics.drawString(font, line, l + LIST_X + (LIST_W - w) / 2, y, 0xFFA0A0A0, false);
                y += 10;
            }
            return;
        }

        int y = t + LIST_Y;
        int end = Math.min(rows.size(), scroll + VISIBLE_ROWS);
        for (int i = scroll; i < end; i++) {
            Row row = rows.get(i);
            if (row.entry() == null) {
                graphics.drawString(font, row.text(), l + LIST_X + 2, y + 4, row.color(), false);
            } else {
                RestockerSyncPayload.Entry entry = row.entry();
                graphics.renderItem(entry.stack(), l + LIST_X + 1, y);
                String counts = entry.have() + " / " + entry.needed();
                int countsWidth = font.width(counts);
                int countsX = l + LIST_X + LIST_W - 4 - countsWidth;
                graphics.drawString(font, counts, countsX, y + 4, row.color(), false);
                int nameMaxWidth = countsX - (l + LIST_X + 20) - 4;
                FormattedText clipped = font.substrByWidth(entry.stack().getHoverName(), nameMaxWidth);
                graphics.drawString(font, Language.getInstance().getVisualOrder(clipped),
                        l + LIST_X + 20, y + 4, 0xFFE6E6E6, false);
            }
            y += ROW_H;
        }

        if (rows.size() > VISIBLE_ROWS) {
            int maxScroll = rows.size() - VISIBLE_ROWS;
            int barHeight = Math.max(8, LIST_H * VISIBLE_ROWS / rows.size());
            int barY = t + LIST_Y + (LIST_H - barHeight) * scroll / maxScroll;
            int barX = l + LIST_X + LIST_W - 2;
            graphics.fill(barX, t + LIST_Y, barX + 2, t + LIST_Y + LIST_H, 0xFF3A3A40);
            graphics.fill(barX, barY, barX + 2, barY + barHeight, 0xFF9A9AA5);
        }
    }

    @Nullable
    private Row rowAt(double mouseX, double mouseY) {
        int iconLeft = leftPos + LIST_X;
        int top = topPos + LIST_Y;
        if (mouseX < iconLeft || mouseX >= iconLeft + 18 || mouseY < top || mouseY >= top + LIST_H) {
            return null;
        }
        int index = scroll + (int) ((mouseY - top) / ROW_H);
        return index >= 0 && index < rows.size() ? rows.get(index) : null;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (rows.size() > VISIBLE_ROWS
                && mouseX >= leftPos + LIST_X && mouseX < leftPos + LIST_X + LIST_W
                && mouseY >= topPos + LIST_Y && mouseY < topPos + LIST_Y + LIST_H) {
            scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, rows.size() - VISIBLE_ROWS);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private record Row(Component text, int color, @Nullable RestockerSyncPayload.Entry entry) {
        static Row header(Component text, int color) {
            return new Row(text, color, null);
        }

        static Row item(RestockerSyncPayload.Entry entry, int color) {
            return new Row(Component.empty(), color, entry);
        }
    }
}
