package io.github.lounode.ae2pattern.config;

import java.util.LinkedHashMap;
import java.util.Map;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 聊天栏消息的总闸表：一条消息一个开关，格子里记的就是它的语言键。
 *
 * <p><b>为什么按语言键登记</b>：发消息的地方手上只有语言键（服务端拼键、客户端本地化），把配置键写成语言键的
 * 一部分，两边就永远对得上——不需要再维护一份「编号 → 消息」的中间表，也就没有对错号的可能。配置文件里省掉
 * 公共前缀 {@link #MESSAGE_KEY_PREFIX} 只为了短一点，登记时补回来，所以查表仍然是整键精确命中。</p>
 *
 * <p><b>为什么分两份</b>：本模组的消息有两种发法——客户端自己显示（预检提示，以及服务端发「键 + 参数」的包、
 * 由客户端决定显不显示的那些），和服务端直接往聊天栏发。前者归客户端配置（每个玩家各管各的，任何服务器上都
 * 能自己关），后者归服务端配置（得由服务端那边说了算）。同一条消息两处都发的（例如「网络里没有空白样板」既被
 * 客户端预检、也被服务端拒绝），两边各有一个开关，是这套分法的必然结果，配置文件里都有说明。</p>
 *
 * <p><b>没登记的键一律照常显示</b>：新加一条消息而忘了配开关时，结果是「和以前一样看得见」，而不是静默消失。</p>
 */
public final class ChatMessageSwitches {

    /** 本模组所有消息语言键的公共前缀；配置文件里写的是去掉它之后的短名。 */
    public static final String MESSAGE_KEY_PREFIX = "gui.ae2_pattern_disk.";

    /** 配置文件里这些开关所在的表名。 */
    private static final String TABLE = "chat_messages.";

    private final Map<String, ModConfigSpec.BooleanValue> byMessageKey = new LinkedHashMap<>();

    /**
     * 登记一条消息的开关。
     *
     * @param shortKey 去掉 {@link #MESSAGE_KEY_PREFIX} 之后的语言键，例如
     *                 {@code cell_management_terminal.notice.put}
     * @param when     这条消息什么时候出现（写进 TOML 的注释）
     */
    public ModConfigSpec.BooleanValue define(ModConfigSpec.Builder builder, String shortKey, String when) {
        builder.comment(when);
        var value = builder.define(TABLE + shortKey, true);
        this.byMessageKey.put(MESSAGE_KEY_PREFIX + shortKey, value);
        return value;
    }

    /** 这条消息现在该不该显示。 */
    public boolean isShown(String messageKey) {
        var value = this.byMessageKey.get(messageKey);
        return value == null || value.get();
    }
}
