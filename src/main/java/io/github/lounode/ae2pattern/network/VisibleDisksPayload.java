package io.github.lounode.ae2pattern.network;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import io.github.lounode.ae2pattern.common.menu.PatternDiskManagementTermMenu;

/**
 * Serverbound packet telling the menu which disks the client's table currently has on screen.
 *
 * <p>The server cannot know the client's scroll position, and sending every disk's contents would be
 * wasteful for a table that shows a handful of rows. So the client drives: it reports the serials it is
 * about to draw (the disk rows plus a small margin), and the server answers with
 * {@link DiskContentPayload} - skipping disks it already pushed unchanged.</p>
 */
public record VisibleDisksPayload(List<Long> serials) implements CustomPacketPayload {

    /** Upper bound the server enforces on a single request; a screen never needs more than a few rows. */
    public static final int MAX_SERIALS = 128;

    public static final Type<VisibleDisksPayload> TYPE = new Type<>(
            ResourceLocation.parse("ae2_pattern_disk:visible_disks"));

    public static final StreamCodec<RegistryFriendlyByteBuf, VisibleDisksPayload> STREAM_CODEC = StreamCodec
            .ofMember(VisibleDisksPayload::write, VisibleDisksPayload::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static VisibleDisksPayload decode(RegistryFriendlyByteBuf data) {
        return new VisibleDisksPayload(ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()).decode(data));
    }

    public void write(RegistryFriendlyByteBuf data) {
        ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()).encode(data, serials);
    }

    /**
     * Server-side: hand the request to the open management menu. Anything else on the menu is ignored - the
     * payload names disks by serial, and only this menu hands out serials.
     */
    public void handleOnServer(PatternDiskManagementTermMenu menu) {
        menu.receiveVisibleDisks(serials);
    }
}
