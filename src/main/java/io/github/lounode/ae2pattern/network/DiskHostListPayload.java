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
 * Clientbound packet carrying the pattern disks grouped by the machine that holds them.
 *
 * <p>The plain {@link DiskListPayload} says "these disks exist on the grid"; this one adds what the
 * management terminal's table needs to put them under headers: a stable key per host (its position and
 * identity salt), a display name and an icon. Everything else the screen shows is derived from the disk
 * stacks themselves, which travel in {@link Entry} exactly as they do in the flat list.</p>
 *
 * <p>Serials match the ones in {@link DiskListPayload}: both are assigned by
 * {@code PatternDiskEncodingTermMenu.syncDiskList} from the same mapping, so a serial means the same disk
 * in either payload - that is what lets the content request name a disk at all.</p>
 */
public record DiskHostListPayload(List<HostGroup> hosts) implements ClientboundPacket {

    public static final Type<DiskHostListPayload> TYPE = new Type<>(
            ResourceLocation.parse("ae2_pattern_disk:disk_host_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DiskHostListPayload> STREAM_CODEC = StreamCodec
            .ofMember(DiskHostListPayload::write, DiskHostListPayload::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static DiskHostListPayload decode(RegistryFriendlyByteBuf data) {
        return new DiskHostListPayload(HostGroup.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(data));
    }

    public void write(RegistryFriendlyByteBuf data) {
        HostGroup.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(data, hosts);
    }

    /**
     * Client-side: hand the groups to the open management menu, which the screen reads every frame.
     */
    @Override
    public void handleOnClient(Player player) {
        if (player.containerMenu instanceof PatternDiskManagementTermMenu menu) {
            menu.receiveHostList(hosts);
        }
    }

    /**
     * One machine's row of the table: its key (for the show/hide toggle), a display name and icon, and the
     * disks it holds in the order the server enumerated them.
     */
    public record HostGroup(String key, String name, ItemStack icon, List<Entry> disks) {

        public static final StreamCodec<RegistryFriendlyByteBuf, HostGroup> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, HostGroup::key,
                ByteBufCodecs.STRING_UTF8, HostGroup::name,
                ItemStack.OPTIONAL_STREAM_CODEC, HostGroup::icon,
                Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), HostGroup::disks,
                HostGroup::new);
    }

    /** A single disk in a group. Same shape as {@link DiskListPayload.DiskEntry}, deliberately. */
    public record Entry(long serial, ItemStack stack) {

        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec
                .ofMember(Entry::write, Entry::decode);

        public static Entry decode(RegistryFriendlyByteBuf data) {
            return new Entry(data.readVarLong(), ItemStack.OPTIONAL_STREAM_CODEC.decode(data));
        }

        public void write(RegistryFriendlyByteBuf data) {
            data.writeVarLong(serial);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(data, stack);
        }
    }
}
