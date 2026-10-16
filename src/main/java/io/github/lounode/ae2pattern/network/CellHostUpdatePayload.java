package io.github.lounode.ae2pattern.network;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import appeng.core.network.ClientboundPacket;

import io.github.lounode.ae2pattern.common.menu.CellManagementTermMenu;

/** 布局不变时只同步改变的格位；菜单编号阻止上一会话的差量污染新开的终端。 */
public record CellHostUpdatePayload(int menuId, List<CellHostListPayload.CellSlot> cells) implements ClientboundPacket {
    public static final Type<CellHostUpdatePayload> TYPE = new Type<>(ResourceLocation.parse("ae2_pattern_disk:cell_host_update"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CellHostUpdatePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, CellHostUpdatePayload::menuId,
            CellHostListPayload.CellSlot.STREAM_CODEC.apply(ByteBufCodecs.list()), CellHostUpdatePayload::cells,
            CellHostUpdatePayload::new);

    @Override public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() { return TYPE; }

    @Override public void handleOnClient(Player player) {
        if (player.containerMenu instanceof CellManagementTermMenu menu && menu.containerId == menuId) {
            menu.updateDriveList(cells);
        }
    }
}
