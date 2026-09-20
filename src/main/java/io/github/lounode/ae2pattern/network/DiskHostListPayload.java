package io.github.lounode.ae2pattern.network;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.ShowPatternProviders;
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
 *
 * <p>{@code shownProviders} 一起下发：它决定服务端按哪种“显示模式”筛的这份清单，客户端据此回显按钮图标。
 * 放在这个包里而不用 {@code @GuiSync}，是因为两者总是同时变化。</p>
 */
public record DiskHostListPayload(List<HostGroup> hosts, ShowPatternProviders shownProviders)
        implements ClientboundPacket {

    public static final Type<DiskHostListPayload> TYPE = new Type<>(
            ResourceLocation.parse("ae2_pattern_disk:disk_host_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DiskHostListPayload> STREAM_CODEC = StreamCodec
            .ofMember(DiskHostListPayload::write, DiskHostListPayload::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static DiskHostListPayload decode(RegistryFriendlyByteBuf data) {
        var hosts = HostGroup.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(data);
        return new DiskHostListPayload(hosts, readShownProviders(data));
    }

    /** 枚举名读不回来（协议错配）时回退到默认档，不让一个坏包把客户端带崩。 */
    private static ShowPatternProviders readShownProviders(RegistryFriendlyByteBuf data) {
        var name = ByteBufCodecs.STRING_UTF8.decode(data);
        try {
            return ShowPatternProviders.valueOf(name);
        } catch (IllegalArgumentException e) {
            return ShowPatternProviders.VISIBLE;
        }
    }

    public void write(RegistryFriendlyByteBuf data) {
        HostGroup.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(data, hosts);
        // 按名而非序号编码：枚举顺序变化不会读错档位。
        ByteBufCodecs.STRING_UTF8.encode(data, shownProviders.name());
    }

    /**
     * Client-side: hand the groups to the open management menu, which the screen reads every frame.
     */
    @Override
    public void handleOnClient(Player player) {
        if (player.containerMenu instanceof PatternDiskManagementTermMenu menu) {
            menu.receiveHostList(hosts, shownProviders);
        }
    }

    /**
     * One machine's row of the table: its key (for the show/hide toggle), a display name and icon, the
     * disks it holds in the order the server enumerated them, and how many of its slots are still empty -
     * the terminal's “hide empty slots” toggle folds those into a single cell.
     */
    public record HostGroup(String key, String name, ItemStack icon, List<Entry> disks, int emptySlots) {

        public static final StreamCodec<RegistryFriendlyByteBuf, HostGroup> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, HostGroup::key,
                ByteBufCodecs.STRING_UTF8, HostGroup::name,
                ItemStack.OPTIONAL_STREAM_CODEC, HostGroup::icon,
                Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), HostGroup::disks,
                ByteBufCodecs.VAR_INT, HostGroup::emptySlots,
                HostGroup::new);
    }

    /**
     * A single disk in a group. Same shape as {@link DiskListPayload.DiskEntry}, plus how many patterns the
     * disk holds: the table needs that to know how many continuation rows a disk takes, and the contents
     * themselves only travel for the disks currently on screen.
     */
    public record Entry(long serial, ItemStack stack, int patternCount) {

        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec
                .ofMember(Entry::write, Entry::decode);

        public static Entry decode(RegistryFriendlyByteBuf data) {
            return new Entry(data.readVarLong(), ItemStack.OPTIONAL_STREAM_CODEC.decode(data), data.readVarInt());
        }

        public void write(RegistryFriendlyByteBuf data) {
            data.writeVarLong(serial);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(data, stack);
            data.writeVarInt(patternCount);
        }
    }
}
