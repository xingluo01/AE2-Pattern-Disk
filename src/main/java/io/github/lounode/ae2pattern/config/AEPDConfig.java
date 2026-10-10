package io.github.lounode.ae2pattern.config;

import java.util.List;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 模组配置。
 *
 * <p>三项里前两项是纯客户端视图设置：附加排序的层级词表，以及附加排序最后那层数值序的名字门槛。它们只影响
 * 客户端怎么给自己的物品网格排序，不改变任何服务端行为，所以按 {@code CLIENT} 类型注册——服务端不加载
 * 这份文件，也不会随网络同步（每个玩家的排序偏好本来就该各管各的）。</p>
 *
 * <p>第三项是聊天栏消息开关：客户端自己显示的那些（预检提示，以及服务端发「键 + 参数」的包、由客户端决定
 * 显不显示的回执）。这些开关归客户端，玩家在任何服务器上都能自己关；服务端直接往聊天栏发的那些在
 * {@link AEPDCommonConfig} 里，两边的消息键一一对得上。</p>
 *
 * <p>文件落在 {@code config/ae2_pattern_disk-client.toml}。改完存档不需要重开，客户端重载配置即生效。</p>
 */
public final class AEPDConfig {

    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ADDITIONAL_SORT_TIERS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ADDITIONAL_SORT_NUMERIC_REGEX;

    /** 聊天栏消息开关（客户端显示的那一半），一条一个。 */
    private static final ChatMessageSwitches MESSAGES = new ChatMessageSwitches();

    /**
     * 默认层级表：一组一个元素，组内按「从低到高」列层级词。
     *
     * <p>每条前面可带一个 mod 范围（{@code mekanism*} 这种，省略或写 {@code *} 即不限），用来隔开同名层级词
     * 在不同模组里的含义——{@code basic} 在 Mekanism 与 Powah 里都有，靠它各归各的顺序。</p>
     *
     * <p>层级词写注册名里的片段而不是中文名：显示名随语言变，注册名不会，所以同一个词在中英文客户端下都能
     * 命中，元素也短得多（这一项在配置文件里是单行写的，NeoForge 不会把数组拆成多行）。要按中文名匹配时写成
     * {@code 中文|english} 即可，两种写法命中其一就算。</p>
     */
    private static final List<String> DEFAULT_TIERS = List.of(
            // Mekanism 工厂：basic 基础 → advanced 高级 → elite 精英 → ultimate 终极
            // → absolute 绝对 → supreme 至尊 → cosmic 寰宇支配 → infinite 悖论无限
            "mekanism*:basic,advanced,elite,ultimate,absolute,supreme,cosmic,infinite",
            // Mekanism 合金：infused 灌注 → reinforced 强化 → atomic 原子
            // → radiance 辐光 → thermonuclear 热核 → shining 闪耀 → spectrum 光谱
            "mekanism*:infused,reinforced,atomic,radiance,thermonuclear,shining,spectrum",
            // Powah：starter 初级 → basic 基础 → hardened 硬化 → blazing 烈焰
            // → niotic 钻石 → spirited 富生 → nitro 下界 → creative 创造
            "powah:starter,basic,hardened,blazing,niotic,spirited,nitro,creative");

    /**
     * 默认的数值序门槛：任何名字里带数字的条目都参与组内数值序，与加入这一项之前覆盖的名字集合相同。
     *
     * <p>门槛用正则而不是名字清单，是为了让玩家自己收窄范围：比如只放行 {@code [0-9]+[kM]} 这类容量写法，
     * 免得别的模组里「1 号扳手 / 10 号扳手」也被当成容量来排。</p>
     */
    private static final List<String> DEFAULT_NUMERIC_REGEX = List.of("[0-9]");

    static {
        var builder = new ModConfigSpec.Builder();
        // 配置屏上每个表（节）的标题与说明按翻译键取，而自动推导只给得出「局部名」，所以逐表显式登记一次。
        // 必须在第一句 comment 之前：push 会把当时悬着的注释当成该表的注释收走。
        ChatMessageSwitches.section(builder, "additional_sort");
        ChatMessageSwitches.section(builder, "chat_messages");
        ChatMessageSwitches.section(builder, "chat_messages.cell_management_terminal");
        ChatMessageSwitches.section(builder, "chat_messages.cell_management_terminal.notice");
        ChatMessageSwitches.section(builder, "chat_messages.management_terminal");
        ChatMessageSwitches.section(builder, "chat_messages.management_terminal.disk_store");
        ChatMessageSwitches.section(builder, "chat_messages.encoding_terminal");
        ChatMessageSwitches.section(builder, "chat_messages.notice");

        builder.comment(
                "Tier groups for the additional sort; only used when the additional sort is on and the",
                "sort order is 'by mod'.",
                "",
                "One element per group, written as '<mod filter>:word1,word2,...':",
                "  - The mod filter may be omitted; '*' or an empty filter means 'any mod'.",
                "  - Within a group the words go from the lowest tier to the highest.",
                "  - Groups are tried in the order listed; the first group that matches an item wins,",
                "    so order the groups from the most specific to the most general.",
                "",
                "Words are matched against the item's registry name and its display name, anywhere in",
                "them - so every item whose name carries one of these words is affected, not just the",
                "factories and alloys the defaults were written for. Use registry name fragments",
                "(basic, advanced, ...) to keep the line short and language-independent;"
                + " 'chinese|english' works",
                "too, where either spelling matching is enough.",
                "",
                "The defaults cover (in this order):",
                "  1. Mekanism factories - basic, advanced, elite, ultimate, absolute, supreme, cosmic,",
                "     infinite (基础、高级、精英、终极、绝对、至尊、寰宇支配、悖论无限)",
                "  2. Mekanism alloys - infused, reinforced, atomic, radiance, thermonuclear, shining,",
                "     spectrum (灌注、强化、原子、辐光、热核、闪耀、光谱)",
                "  3. Powah - starter, basic, hardened, blazing, niotic, spirited, nitro, creative",
                "     (初级、基础、硬化、烈焰、钻石、富生、下界、创造)",
                "",
                "Sodium's Config API cannot present a list like this one, so it is not used here.");
        builder.translation("ae2_pattern_disk.configuration.additional_sort.tiers");
        ADDITIONAL_SORT_TIERS = builder.defineList(
                "additional_sort.tiers", DEFAULT_TIERS, () -> "", element -> element instanceof String);
        builder.comment(
                "Which names take part in the numeric level of the additional sort - the last level,",
                "where 1k < 4k < 16k < 64k < 256k < 1M.",
                "",
                "How to write one: every element is a Java regular expression (java.util.regex, the same",
                "flavour mods already use), and it is searched for anywhere inside the item's display",
                "name - so '[0-9]' means 'the name contains a digit' and does not have to describe the",
                "whole name. Matching is case-sensitive unless the expression says '(?i)'. A backslash",
                "must be doubled inside a TOML string ('\\d' becomes '\\\\d'), so prefer escape-free",
                "classes such as '[0-9]+' where you can.",
                "",
                "What the order means: nothing. The elements are not tried in turn and there is no",
                "'first one wins' - a name takes part as soon as at least one of them matches anywhere,",
                "and that is all this list decides. Where such a name ends up is then decided by the",
                "numbers it contains, with a trailing k/M/G/T/P/E counted as a power of 1024 - which is",
                "why capacity chains like 1k, 4k, 16k, 64k, 256k, 1M come out in the right order.",
                "",
                "What NOT taking part means: those names are placed after the ones that take part, and",
                "among themselves they keep the plain literal order. The two groups stay apart on",
                "purpose - a comparison whose rule depended on which two names it was handed would not",
                "be consistent, and the sort would then throw 'Comparison method violates its general",
                "contract'. (The 'additional_sort.tiers' list above is the opposite case: there the",
                "listed order is the rule.)",
                "",
                "The default '[0-9]' lets every name containing a digit take part - the same set of",
                "names this level used to cover before it became configurable. Narrow it to keep",
                "numbers that are not capacities out, e.g. '[0-9]+[kKmM]' for the '1k'/'4k'/'1M' style,",
                "or '^[0-9]+k' to only take names that start with one; an empty list turns the numeric",
                "level off altogether.",
                "",
                "A malformed expression is skipped on its own - the other elements still apply and the",
                "sort keeps working. If nothing in the list can be compiled at all, the level is left",
                "wide open (no filtering) rather than silently turned off.");
        builder.translation("ae2_pattern_disk.configuration.additional_sort.numeric_regex");
        ADDITIONAL_SORT_NUMERIC_REGEX = builder.defineList(
                "additional_sort.numeric_regex", DEFAULT_NUMERIC_REGEX, () -> "",
                element -> element instanceof String);

        builder.comment(
                "Chat messages this mod shows on the client.",
                "",
                "Each entry is one message and they are all independent: turning one off only silences",
                "that one line. Messages are grouped by the screen that shows them, and every key in here",
                "is the message's translation key with the leading 'gui.ae2_pattern_disk.' dropped - so it",
                "can be looked up in the language file directly.",
                "",
                "This file covers the messages the CLIENT displays: the pre-checks the screens run before",
                "asking the server, and the notices the server sends as 'key + argument' for the client to",
                "show. Suppressing one never changes what the action does - they are receipts and refusal",
                "reasons, not part of the flow.",
                "",
                "Messages the SERVER sends into the chat itself are in ae2_pattern_disk-common.toml",
                "(that side cannot be silenced from here). A couple of conditions report themselves on",
                "both sides - 'no_blank_pattern' is one - and each side has its own switch.",
                "",
                "Changes take effect on the next message; no restart needed, just reload the config.");

        // ---- 元件管理终端：表格手势的回执与拒绝原因 ----
        // 服务端只发「键 + 参数」，显示与否在这里定（CellNoticePayload.handleOnClient）。
        builder.comment("Cell management terminal - feedback for the table gestures, shown in the chat.");
        MESSAGES.define(builder, "cell_management_terminal.notice.encode_busy",
                "Shown when you move a cell into the encoding slot while another cell is already there.");
        MESSAGES.define(builder, "cell_management_terminal.notice.cell_locked",
                "Shown when taking a cell out fails because the storage host locks it (an ECO infinite drive, for example).");
        MESSAGES.define(builder, "cell_management_terminal.notice.cell_refused",
                "Shown when the slot holds something but the storage host refuses to hand it over.");
        MESSAGES.define(builder, "cell_management_terminal.notice.slot_empty",
                "Shown when taking a cell out of a slot that really is empty.");
        MESSAGES.define(builder, "cell_management_terminal.notice.moved_to_encode",
                "Shown after a cell was moved into the encoding slot.");
        MESSAGES.define(builder, "cell_management_terminal.notice.carried_not_empty",
                "Shown when taking a cell while your cursor already holds something.");
        MESSAGES.define(builder, "cell_management_terminal.notice.took",
                "Shown after a cell was taken out into your cursor.");
        MESSAGES.define(builder, "cell_management_terminal.notice.nothing_carried",
                "Shown when putting a cell while your cursor is empty.");
        MESSAGES.define(builder, "cell_management_terminal.notice.cell_rejected",
                "Shown when the storage host you picked does not accept that cell.");
        MESSAGES.define(builder, "cell_management_terminal.notice.put",
                "Shown after a cell was stored into a drive or into the encoding slot.");
        MESSAGES.define(builder, "cell_management_terminal.notice.inventory_full",
                "Shown when a quick move of a cell fails because your inventory is full.");
        MESSAGES.define(builder, "cell_management_terminal.notice.slot_changed",
                "Shown when the target slot moved before the action ran.");
        MESSAGES.define(builder, "cell_management_terminal.notice.not_a_cell",
                "Shown when the item on your cursor is not a storage cell.");
        MESSAGES.define(builder, "cell_management_terminal.notice.grid_offline",
                "Shown when the terminal's grid is offline (or the drive's owner could not be resolved).");
        MESSAGES.define(builder, "cell_management_terminal.notice.drive_gone",
                "Shown when the selected drive is gone or was broken.");
        MESSAGES.define(builder, "cell_management_terminal.notice.drive_full",
                "Shown when the selected drive has no free cell slot.");
        MESSAGES.define(builder, "cell_management_terminal.notice.moved",
                "Shown after a cell was moved into the selected drive.");
        MESSAGES.define(builder, "cell_management_terminal.notice.move_failed",
                "Shown when putting the cell back after a failed move did not work either.");
        MESSAGES.define(builder, "cell_management_terminal.notice.encode_empty",
                "Shown when storing the encoding slot's cell while that slot is empty.");
        MESSAGES.define(builder, "cell_management_terminal.notice.priority_unsupported",
                "Shown when opening AE2's priority GUI is not supported for the current selection.");
        MESSAGES.define(builder, "cell_management_terminal.notice.select_first",
                "Shown when Shift+clicking a cell from your inventory without a drive selected.");
        MESSAGES.define(builder, "cell_management_terminal.drive_pinned",
                "Shown when you pin a drive (it is then drawn in the world).");
        MESSAGES.define(builder, "cell_management_terminal.drive_unpinned",
                "Shown when you unpin a drive.");

        // ---- 样板磁盘管理终端：客户端预检 ----
        builder.comment("Pattern disk management terminal - the client-side pre-check.");
        MESSAGES.define(builder, "management_terminal.disk_store.select_first",
                "Shown when Shift+clicking a disk from your inventory without a container selected.");

        // ---- 编码终端：客户端预检 ----
        builder.comment("Pattern encoding terminal - the client-side pre-check.");
        MESSAGES.define(builder, "encoding_terminal.no_blank_pattern",
                "Shown when you press encode while the ME network has no blank patterns (the client-side pre-check; the server's own refusal has its own switch in the common config).");

        // ---- 三个终端共用 ----
        builder.comment("Shared by all three terminals.");
        MESSAGES.define(builder, "notice.no_network_quick_move",
                "Shown when Shift+clicking an item from your inventory that this terminal cannot send to the ME network and has no other slot for.");

        CLIENT_SPEC = builder.build();
    }

    /**
     * 这条聊天栏消息现在该不该显示（客户端显示的那些）。
     *
     * <p>没登记过的语言键照常显示，所以新消息漏了开关也只是照旧可见，不会静默消失。</p>
     */
    public static boolean isMessageShown(String messageKey) {
        return MESSAGES.isShown(messageKey);
    }

    private AEPDConfig() {
    }
}
