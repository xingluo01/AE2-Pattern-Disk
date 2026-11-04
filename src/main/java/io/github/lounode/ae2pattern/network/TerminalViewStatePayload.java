package io.github.lounode.ae2pattern.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import io.github.lounode.ae2pattern.common.menu.pattern.PatternDiskManagementTermMenu;

/**
 * Serverbound：管理终端里「跟着屏幕走」的显示状态变更——是否隐藏空槽位、当前选中的磁盘。
 *
 * <p>状态本体存在服务端的 {@code DiskEncodingLogic} 里（面板版落部件 NBT、无线版落物品自己的数据组件），
 * 屏幕上的按钮只改客户端那份副本。不同步回服务端的话，关屏重开时服务端仍是旧值，表现就是
 * 「每一个按钮都被重置」——这正是这条包存在的理由。</p>
 */
public record TerminalViewStatePayload(boolean hideEmptySlots, long selectedSerial,
        io.github.lounode.ae2pattern.common.menu.pattern.DiskEncodingLogic.SearchScope searchScope, boolean naturalSort)
        implements CustomPacketPayload {

    public static final Type<TerminalViewStatePayload> TYPE = new Type<>(
            ResourceLocation.parse("ae2_pattern_disk:terminal_view_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalViewStatePayload> STREAM_CODEC = StreamCodec
            .ofMember(TerminalViewStatePayload::write, TerminalViewStatePayload::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void write(RegistryFriendlyByteBuf data) {
        data.writeBoolean(this.hideEmptySlots);
        data.writeLong(this.selectedSerial);
        data.writeUtf(this.searchScope.name());
        data.writeBoolean(this.naturalSort);
    }

    public static TerminalViewStatePayload decode(RegistryFriendlyByteBuf data) {
        var hideEmptySlots = data.readBoolean();
        var selectedSerial = data.readLong();
        var searchScope = io.github.lounode.ae2pattern.common.menu.pattern.DiskEncodingLogic.SearchScope.BOTH;
        try {
            searchScope = io.github.lounode.ae2pattern.common.menu.pattern.DiskEncodingLogic.SearchScope.valueOf(data.readUtf());
        } catch (IllegalArgumentException ignored) {
            // 协议错配时回落默认档，不让一个坏包把连接带崩。
        }
        return new TerminalViewStatePayload(hideEmptySlots, selectedSerial, searchScope, data.readBoolean());
    }

    public void handleOnServer(Player player) {
        if (player.containerMenu instanceof PatternDiskManagementTermMenu menu) {
            menu.applyViewState(this.hideEmptySlots, this.selectedSerial, this.searchScope, this.naturalSort);
        }
    }
}
