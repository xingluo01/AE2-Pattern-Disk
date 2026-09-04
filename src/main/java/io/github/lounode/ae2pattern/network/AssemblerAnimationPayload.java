package io.github.lounode.ae2pattern.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import appeng.api.stacks.AEKey;
import io.github.lounode.ae2pattern.common.block.entity.PatternDiskAssemblerBlockEntity;

/**
 * Clientbound packet signalling that a pattern disk assembler unit finished a craft. The
 * payload carries the block position, the (byte) crafting speed used and the produced item so
 * the renderer can play the short assembly animation. Mirrors AE2's
 * {@code AssemblerAnimationPacket}.
 */
public record AssemblerAnimationPayload(BlockPos pos, byte rate, AEKey what) implements CustomPacketPayload {

    public static final Type<AssemblerAnimationPayload> TYPE = new Type<>(
            ResourceLocation.parse("ae2_pattern_disk:assembler_animation"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AssemblerAnimationPayload> STREAM_CODEC = StreamCodec
            .ofMember(
                    AssemblerAnimationPayload::write,
                    AssemblerAnimationPayload::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static AssemblerAnimationPayload decode(RegistryFriendlyByteBuf data) {
        var pos = data.readBlockPos();
        var rate = data.readByte();
        var what = AEKey.readKey(data);
        return new AssemblerAnimationPayload(pos, rate, what);
    }

    public void write(RegistryFriendlyByteBuf data) {
        data.writeBlockPos(pos);
        data.writeByte(rate);
        AEKey.writeKey(data, what);
    }

    @OnlyIn(Dist.CLIENT)
    public void handleOnClient(Player player) {
        BlockEntity te = player.getCommandSenderWorld().getBlockEntity(pos);
        if (te instanceof PatternDiskAssemblerBlockEntity assembler) {
            assembler.setAnimationStatus(new AssemblerAnimationStatus(rate, what.wrapForDisplayOrFilter()));
        }
    }
}
