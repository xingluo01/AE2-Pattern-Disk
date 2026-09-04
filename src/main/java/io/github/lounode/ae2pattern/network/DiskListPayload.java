package io.github.lounode.ae2pattern.network;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;
import appeng.core.network.ClientboundPacket;

/**
 * Clientbound packet carrying the full list of pattern disks found in all PatternDiskProviders on the grid.
 * Sent by the server-side menu in {@code broadcastChanges()} whenever the fingerprint changes.
 */
public record DiskListPayload(List<DiskEntry> disks) implements ClientboundPacket {

    public static final Type<DiskListPayload> TYPE = new Type<>(
            ResourceLocation.parse("ae2_pattern_disk:disk_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DiskListPayload> STREAM_CODEC = StreamCodec
            .ofMember(DiskListPayload::write, DiskListPayload::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static DiskListPayload decode(RegistryFriendlyByteBuf data) {
        var entries = DiskEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(data);
        return new DiskListPayload(entries);
    }

    public void write(RegistryFriendlyByteBuf data) {
        DiskEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(data, disks);
    }

    /**
     * Client-side: route the new list into the open menu, which the screen reads every frame.
     */
    public void handleOnClient(Player player) {
        if (player.containerMenu instanceof PatternDiskEncodingTermMenu menu) {
            menu.receiveDiskList(disks);
        }
    }

    /**
     * A single disk entry in the list. Only {@code serial} and {@code stack} are transmitted;
     * display-name, used/capacity, and count are derived client-side from the stack.
     */
    public record DiskEntry(long serial, ItemStack stack) {

        public static final StreamCodec<RegistryFriendlyByteBuf, DiskEntry> STREAM_CODEC = StreamCodec
                .ofMember(DiskEntry::write, DiskEntry::decode);

        public static DiskEntry decode(RegistryFriendlyByteBuf data) {
            return new DiskEntry(data.readVarLong(), ItemStack.OPTIONAL_STREAM_CODEC.decode(data));
        }

        public void write(RegistryFriendlyByteBuf data) {
            data.writeVarLong(serial);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(data, stack);
        }
    }
}