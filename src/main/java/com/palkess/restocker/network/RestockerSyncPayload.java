package com.palkess.restocker.network;

import com.palkess.restocker.Restocker;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: the categorised material list for the Restocker at {@code pos}.
 * {@code message} is a translation key shown when the list is empty (or blank when it isn't).
 */
public record RestockerSyncPayload(BlockPos pos, List<Entry> entries, String message)
        implements CustomPacketPayload {

    public record Entry(ItemStack stack, long needed, long have, byte status) {
    }

    public static final Type<RestockerSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Restocker.MODID, "sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RestockerSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos());
                buf.writeVarInt(payload.entries().size());
                for (Entry entry : payload.entries()) {
                    ItemStack.STREAM_CODEC.encode(buf, entry.stack());
                    buf.writeVarLong(entry.needed());
                    buf.writeVarLong(entry.have());
                    buf.writeByte(entry.status());
                }
                buf.writeUtf(payload.message());
            },
            buf -> {
                BlockPos pos = buf.readBlockPos();
                int size = buf.readVarInt();
                List<Entry> entries = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    entries.add(new Entry(
                            ItemStack.STREAM_CODEC.decode(buf),
                            buf.readVarLong(),
                            buf.readVarLong(),
                            buf.readByte()));
                }
                return new RestockerSyncPayload(pos, entries, buf.readUtf());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
