package io.github.lounode.ae2pattern.network;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import appeng.core.network.ClientboundPacket;

import io.github.lounode.ae2pattern.common.menu.PatternDiskManagementTermMenu;

/**
 * Clientbound packet carrying the contents of the disks the client currently shows, keyed by the serials
 * from {@link DiskHostListPayload}.
 *
 * <p>Contents are sent on request rather than with the list, because a disk can hold up to 1024 patterns and
 * the table shows many disks at once: the client asks only for what is on screen
 * ({@link VisibleDisksPayload}), and the server skips disks whose contents have not changed since the last
 * push for this menu.</p>
 */
public record DiskContentPayload(List<Entry> disks) implements ClientboundPacket {

    public static final Type<DiskContentPayload> TYPE = new Type<>(
            ResourceLocation.parse("ae2_pattern_disk:disk_content"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DiskContentPayload> STREAM_CODEC = StreamCodec
            .ofMember(DiskContentPayload::write, DiskContentPayload::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static DiskContentPayload decode(RegistryFriendlyByteBuf data) {
        return new DiskContentPayload(Entry.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(data));
    }

    public void write(RegistryFriendlyByteBuf data) {
        Entry.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(data, disks);
    }

    @Override
    public void handleOnClient(Player player) {
        if (player.containerMenu instanceof PatternDiskManagementTermMenu menu) {
            menu.receiveDiskContents(disks);
        }
    }

    /**
     * The stored patterns of one disk, in slot order. Empty stacks are kept out - the screen lays them out
     * row by row and does not need the holes.
     */
    public record Entry(long serial, List<ItemStack> patterns) {

        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Entry::serial,
                ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()), Entry::patterns,
                Entry::new);
    }
}
