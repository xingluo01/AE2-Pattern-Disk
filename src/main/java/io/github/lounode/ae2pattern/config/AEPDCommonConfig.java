package io.github.lounode.ae2pattern.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 服务端直发的聊天栏消息开关。
 *
 * <p>这些消息由服务端当场发出去（编码终端 / 管理终端 / 部件扳手），客户端配置拦不住它们——聊天栏里那行已经
 * 由服务端写进去了，所以开关只能在这边。单人游戏里这份文件就是你自己的；多人服务器上它归服主，玩家改不了
 * （要玩家自己能关的，看 {@link AEPDConfig} 那份客户端配置）。</p>
 *
 * <p>文件落在 {@code config/ae2_pattern_disk-common.toml}，改完不需要重启服务端，下次发消息时就按新值走。</p>
 *
 * <p>关掉一条消息只会让它不再说话，动作本身照做——这些全是回执与拒绝原因的说明，不是流程的一部分。</p>
 */
public final class AEPDCommonConfig {

    public static final ModConfigSpec COMMON_SPEC;

    private static final ChatMessageSwitches MESSAGES = new ChatMessageSwitches();

    static {
        var builder = new ModConfigSpec.Builder();
        // 本份配置用到的表：逐表显式登记翻译键，理由见 ChatMessageSwitches.section。
        ChatMessageSwitches.section(builder, "chat_messages");
        ChatMessageSwitches.section(builder, "chat_messages.cell_management_terminal");
        ChatMessageSwitches.section(builder, "chat_messages.cell_management_terminal.notice");
        ChatMessageSwitches.section(builder, "chat_messages.encoding_terminal");
        ChatMessageSwitches.section(builder, "chat_messages.encoding_terminal.disk_refused");
        ChatMessageSwitches.section(builder, "chat_messages.encoding_terminal.no_room");
        ChatMessageSwitches.section(builder, "chat_messages.management_terminal");
        ChatMessageSwitches.section(builder, "chat_messages.management_terminal.disk_store");
        ChatMessageSwitches.section(builder, "chat_messages.pattern_disk_provider");

        builder.comment(
                "Chat messages this mod sends to the player from the server side.",
                "",
                "Each entry is one message and they are all independent: turning one off only silences",
                "that one line. Messages are grouped by the screen or part that sends them, and every",
                "key in here is the message's translation key with the leading 'gui.ae2_pattern_disk.'",
                "dropped - so it can be looked up in the language file directly.",
                "",
                "Suppressing a message never changes what the action does; these are all receipts and",
                "refusal reasons, not part of the flow.",
                "",
                "NOTE: messages this mod shows on the client instead (the client-side pre-checks) live",
                "in ae2_pattern_disk-client.toml, not here. A couple of conditions report themselves on",
                "both sides - 'no_blank_pattern' is one - and each side has its own switch, so to stop",
                "seeing them everywhere turn off both.",
                "",
                "Changes take effect on the next message; no restart needed.");

        // ---- 编码终端：编码/写盘/取样板/打标记时的回执与拒绝原因（PatternDiskEncodingTermMenu 的 tell） ----
        builder.comment("Pattern encoding terminal - why an encode / write / extract did not happen, and receipts.");
        MESSAGES.define(builder, "encoding_terminal.chiseling_needs_target",
                "Shown when encoding in the chiseling tier and no target (or no candidate) was picked.");
        MESSAGES.define(builder, "encoding_terminal.overloaded_needs_input",
                "Shown when encoding in the overloaded tier while the editing area has no input.");
        MESSAGES.define(builder, "encoding_terminal.no_blank_pattern",
                "Shown when the ME network has no blank patterns to encode into (the server-side refusal; the client also pre-checks this condition with its own switch in the client config).");
        MESSAGES.define(builder, "encoding_terminal.disk_refused.full",
                "Shown when the target disk has no room for another pattern.");
        MESSAGES.define(builder, "encoding_terminal.disk_refused.type_locked",
                "Shown when the target disk is locked to another pattern type.");
        MESSAGES.define(builder, "encoding_terminal.disk_refused.duplicate_output",
                "Shown when the target disk already holds a pattern with the same primary output.");
        MESSAGES.define(builder, "encoding_terminal.disk_refused.unresolvable",
                "Shown when the pattern's type could not be resolved, so no disk can take it.");
        MESSAGES.define(builder, "encoding_terminal.disk_refused.unknown",
                "Shown when a disk refused the pattern for a reason without its own message.");
        MESSAGES.define(builder, "encoding_terminal.disk_refused.stale_target",
                "Shown when the disk list on your client was older than the server's, so the click had no target (the list refreshes itself).");
        MESSAGES.define(builder, "encoding_terminal.no_room.cursor",
                "Shown when extracting a pattern while your cursor is already holding something.");
        MESSAGES.define(builder, "encoding_terminal.no_room.inventory",
                "Shown when extracting a pattern while your inventory is full.");
        MESSAGES.define(builder, "encoding_terminal.no_room.encoded_slot",
                "Shown when extracting a pattern while the encoding slot already holds one.");
        MESSAGES.define(builder, "encoding_terminal.content_changed",
                "Shown when the disk's contents changed since the list you clicked, so the pattern index you picked is no longer valid.");
        MESSAGES.define(builder, "encoding_terminal.written_to_disk",
                "Shown after a pattern was written into a disk (receipt).");
        MESSAGES.define(builder, "encoding_terminal.mark_written",
                "Shown after a recipe-type mark was written onto a disk (receipt).");
        MESSAGES.define(builder, "encoding_terminal.mark_skipped",
                "Shown when marking was skipped because your cursor is empty (no recipe type to read).");
        MESSAGES.define(builder, "encoding_terminal.mark_unidentified",
                "Shown when marking was skipped because the held item's recipe type could not be identified.");
        MESSAGES.define(builder, "encoding_terminal.advanced_needs_pattern",
                "Shown when switching to the advanced tier while the output slot holds no processing pattern.");

        // ---- 管理终端：把磁盘放进容器时的回执与拒绝原因（PatternDiskManagementTermMenu.notifyPlayer） ----
        builder.comment("Pattern disk management terminal - storing a disk into a container.");
        MESSAGES.define(builder, "management_terminal.disk_store.no_host",
                "Shown when the container you picked is no longer on the grid.");
        MESSAGES.define(builder, "management_terminal.disk_store.no_disk",
                "Shown when your cursor is not holding a pattern disk.");
        MESSAGES.define(builder, "management_terminal.disk_store.no_room",
                "Shown when the container has no free disk slot.");
        MESSAGES.define(builder, "management_terminal.disk_store.disk_gone",
                "Shown when the disk you picked is no longer there.");
        MESSAGES.define(builder, "management_terminal.disk_store.slot_changed",
                "Shown when the disk is no longer in the slot the list showed.");
        MESSAGES.define(builder, "management_terminal.disk_store.ok",
                "Shown after a disk was stored into a container (receipt).");

        // ---- 元件管理终端：唯一一条服务端直发的回执（标记收藏的四项计数） ----
        // 同终端的其余反馈走 CellNoticePayload 由客户端显示，那些开关在客户端配置里。
        builder.comment("Cell management terminal.");
        MESSAGES.define(builder, "cell_management_terminal.notice.mark_bookmarks",
                "Shown after 'mark bookmarks' finished, with how many were marked / duplicated / had no room / were not items.");

        // ---- 部件：扳手切换推送方向（动作栏） ----
        builder.comment("Provider part - wrench changes the push direction.");
        MESSAGES.define(builder, "pattern_disk_provider.push_direction",
                "Shown on the action bar when a wrench flips the provider between pushing to all sides and to one side.");

        COMMON_SPEC = builder.build();
    }

    /** 这条服务端直发的聊天栏消息现在该不该显示。 */
    public static boolean isMessageShown(String messageKey) {
        return MESSAGES.isShown(messageKey);
    }

    private AEPDCommonConfig() {
    }
}
