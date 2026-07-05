package com.palkess.restocker.network;

import com.palkess.restocker.Restocker;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: request a refresh of the material list, or submit all craftable deficits.
 */
public record RestockerActionPayload(BlockPos pos, int action) implements CustomPacketPayload {
    public static final int ACTION_REFRESH = 0;
    public static final int ACTION_CRAFT_ALL = 1;

    public static final Type<RestockerActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Restocker.MODID, "action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RestockerActionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RestockerActionPayload::pos,
                    ByteBufCodecs.VAR_INT, RestockerActionPayload::action,
                    RestockerActionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
