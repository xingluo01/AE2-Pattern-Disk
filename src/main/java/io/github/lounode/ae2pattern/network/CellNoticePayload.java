package io.github.lounode.ae2pattern.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import appeng.core.network.ClientboundPacket;

import io.github.lounode.ae2pattern.config.AEPDConfig;

/**
 * 服务端给元件管理终端玩家的一句话反馈，显示在聊天栏上。
 *
 * <p><b>为什么需要它</b>：元件格的取放是两段路——客户端发意图、服务端拿当下的栈执行。表格是服务端按节拍
 * 推的快照，失败时（格子空了、驱动器被拆了、对方不收这张元件）表格可能一点都不变，玩家看到的就是
 * 「点了没反应」，无法区分是手势没送达还是服务端拒绝了。每个拒绝原因都如实回一句，这条路才算可观测。</p>
 *
 * <p>传语言键而不是成品文案：文案在客户端本地化，服务端不必知道玩家用的什么语言；带参数的那几条把参数
 * 拼成一个串放在 {@code arg}（本终端的反馈最多一个参数，为空表示无参）。</p>
 *
 * <p><b>为什么进聊天栏而不是动作栏</b>：本终端的这些反馈是「刚才那一下为什么没成」——玩家要能回头看见。
 * 动作栏三秒就没了，而失败往往需要跟上一句提示对照（例如「被存储主机锁住」与「这一格没有元件」）。</p>
 */
public record CellNoticePayload(String key, String arg) implements ClientboundPacket {

    public static final Type<CellNoticePayload> TYPE = new Type<>(
            ResourceLocation.parse("ae2_pattern_disk:cell_notice"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CellNoticePayload> STREAM_CODEC = StreamCodec
            .ofMember(CellNoticePayload::write, CellNoticePayload::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void write(RegistryFriendlyByteBuf data) {
        ByteBufCodecs.STRING_UTF8.encode(data, key);
        ByteBufCodecs.STRING_UTF8.encode(data, arg);
    }

    public static CellNoticePayload decode(RegistryFriendlyByteBuf data) {
        return new CellNoticePayload(ByteBufCodecs.STRING_UTF8.decode(data),
                ByteBufCodecs.STRING_UTF8.decode(data));
    }

    /** 客户端：聊天栏一行（不再走动作栏，理由见类注释）。 */
    @Override
    public void handleOnClient(Player player) {
        // 显不显示定在客户端的配置里：服务端只发「键 + 参数」，本地那句话归玩家自己管。
        if (!AEPDConfig.isMessageShown(key)) {
            return;
        }
        Component message = arg.isEmpty() ? Component.translatable(key) : Component.translatable(key, arg);
        player.displayClientMessage(message, false);
    }
}
