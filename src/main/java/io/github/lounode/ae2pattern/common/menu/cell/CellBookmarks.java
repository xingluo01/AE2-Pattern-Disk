package io.github.lounode.ae2pattern.common.menu.cell;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.util.ConfigInventory;

import org.jetbrains.annotations.Nullable;

import io.github.lounode.ae2pattern.config.AEPDCommonConfig;

/**
 * 「标记收藏」：把 JEI / EMI 收藏夹里的物品追加到编码槽那张元件的标记里。
 *
 * <p>载荷是一串 SNBT，首行是「跳过的非物品项数 | 发之前就注定写不下的项数」，其余每行一个物品栈。
 * 客户端动作的字符串上限是 32767 字符（AE2 的 {@code sendClientAction} 超了直接抛），而收藏夹可以大得多，
 * 所以发之前要按格数与字节双重截断。</p>
 */
final class CellBookmarks {

    /** 载荷的字符预算（留出余量），超出的都算进「未写入」。 */
    private static final int MAX_PAYLOAD_CHARS = 16000;

    /** 聊天栏上报的计数上限（载荷来自客户端，夹一下免得出现负数或离谱的数）。 */
    private static final int MAX_REPORTED_COUNT = 1_000_000;

    private final CellManagementTermMenu menu;

    CellBookmarks(CellManagementTermMenu menu) {
        this.menu = menu;
    }

    /**
     * 客户端侧：先<b>探一遍</b>再截。
     *
     * <p>逐条问这张元件的标记容器「有没有哪一格收得下它」——AE2 的 {@code isAllowedIn} 是纯查询，问一遍
     * 不会动元件。探有两层用处：元件不支持的条目根本不用发；部分元件自己给标记数量设了上限，格数不是真
     * 上限，探得到的可落格数才是。</p>
     */
    @Nullable
    static String encode(@Nullable ConfigInventory config, List<ItemStack> items, int skippedNonItems) {
        var allowed = new ArrayList<ItemStack>();
        int rejected = 0;
        for (ItemStack stack : items) {
            if (config != null && accepts(config, stack)) {
                allowed.add(stack);
            } else {
                rejected++;
            }
        }
        int capacity = config == null ? 0 : config.size();
        var body = new StringBuilder();
        int written = 0;
        int overflow = rejected;
        for (ItemStack stack : allowed) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            var encoded = ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, stack).result().orElse(null);
            var snbt = encoded == null ? null : encoded.toString();
            if (snbt == null || written >= capacity || body.length() + snbt.length() + 1 > MAX_PAYLOAD_CHARS) {
                overflow++;
                continue;
            }
            body.append('\n').append(snbt);
            written++;
        }
        return Math.max(0, skippedNonItems) + "|" + overflow + body;
    }

    /** 这张元件的标记容器里有没有哪一格收得下这个键；纯查询，不改元件。 */
    private static boolean accepts(ConfigInventory config, ItemStack stack) {
        AEKey key = AEItemKey.of(stack);
        if (key == null) {
            return false;
        }
        for (int i = 0; i < config.size(); i++) {
            if (config.isAllowedIn(i, key)) {
                return true;
            }
        }
        return false;
    }

    /** 服务端侧：解载荷并逐条写进分区库。解不开的行直接丢。 */
    void apply(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return;
        }
        var lines = encoded.split("\n", -1);
        int skippedNonItems;
        int noRoom;
        try {
            var header = lines[0].split("\\|", -1);
            // 夹一下：这两个数只是给玩家看的，但载荷完全来自客户端——不夹就能把计数推成负数。
            skippedNonItems = Math.clamp(Integer.parseInt(header[0]), 0, MAX_REPORTED_COUNT);
            noRoom = header.length > 1 ? Math.clamp(Integer.parseInt(header[1]), 0, MAX_REPORTED_COUNT) : 0;
        } catch (RuntimeException malformed) {
            // 协议错配不该把服务端带进越界取值：宁可什么都不做。
            return;
        }
        ItemStack cell = menu.getCellHost().getEncodeCellInventory().getStackInSlot(0);
        if (cell.isEmpty() || !(cell.getItem() instanceof ICellWorkbenchItem)) {
            return;
        }
        // 与窗口、与「分区存储 / 清除」用同一个实例（理由见 EncodeCellPartitions.config()）。
        var config = menu.markerConfigForWrite();
        if (config == null) {
            return;
        }
        var marked = new HashSet<AEKey>(config.keySet());
        int written = 0;
        int duplicate = 0;
        int cursor = 0;
        // 元件自己给标记数量设了上限时（部分元件如此），写到量之后每次都会失败——那时不再逐条试。
        boolean exhausted = false;
        for (int line = 1; line < lines.length; line++) {
            ItemStack stack = parseStack(lines[line]);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            AEKey key = AEItemKey.of(stack);
            // 未注册的物品（或者空栈）给 null：标记只能装键，直接塞进去会在下游炸。
            if (key == null) {
                noRoom++;
                continue;
            }
            if (marked.contains(key)) {
                duplicate++;
                continue;
            }
            // 找一格「空着且收这个键」的：收不收由元件说了算（与客户端那遍探测同一判据）。
            int slot = -1;
            if (!exhausted) {
                for (int i = cursor; i < config.size(); i++) {
                    if (config.getKey(i) == null && config.isAllowedIn(i, key)) {
                        slot = i;
                        break;
                    }
                }
            }
            if (slot < 0) {
                noRoom++;
                continue;
            }
            config.setStack(slot, new GenericStack(key, 1));
            if (config.getKey(slot) == null) {
                // 过滤器放行却写不进去：这张元件自己的上限到了——不再逐条白试，剩下的全计「未写入」。
                exhausted = true;
                noRoom++;
                continue;
            }
            marked.add(key);
            cursor = slot + 1;
            written++;
        }
        if (written > 0) {
            // ConfigInventory 的改动写在元件栈的组件里，元件栈本身得被标记变了才会落盘。
            menu.getCellHost().getEncodeCellInventory().sendChangeNotification(0);
            menu.getCellHost().markForSave();
        }
        menu.broadcastChanges();
        // 只在真有东西没写进去或被跳过时才说：全部写入成功是玩家预期内的结果；重复打标本来无害。
        String noticeKey = CellManagementTermMenu.NOTICE + "mark_bookmarks";
        if ((noRoom > 0 || skippedNonItems > 0)
                && menu.getPlayer() instanceof ServerPlayer serverPlayer
                && AEPDCommonConfig.isMessageShown(noticeKey)) {
            serverPlayer.sendSystemMessage(Component.translatable(noticeKey,
                    written, duplicate, noRoom, skippedNonItems));
        }
    }

    @Nullable
    private static ItemStack parseStack(String line) {
        try {
            return ItemStack.CODEC.parse(NbtOps.INSTANCE, TagParser.parseTag(line)).result().orElse(null);
        } catch (Exception malformed) {
            return null;
        }
    }
}
