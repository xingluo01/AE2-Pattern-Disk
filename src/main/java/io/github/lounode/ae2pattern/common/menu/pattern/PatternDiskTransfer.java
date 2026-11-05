package io.github.lounode.ae2pattern.common.menu.pattern;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import appeng.api.crafting.PatternDetailsHelper;

import org.jetbrains.annotations.Nullable;

import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.menu.pattern.EncodingTermStateMenu.ExtractRequest;
import io.github.lounode.ae2pattern.common.menu.pattern.EncodingTermStateMenu.ExtractTarget;

/**
 * 样板磁盘的写入与取出：编码槽 ↔ 网络里那些盘。
 *
 * <p>写盘一次一张，按客户端给的候选顺序试，进第一张收得下的就停；取样板一次一张（或整张盘），取样板要拿
 * 网络里的一张空白样板换（与样板访问终端同一本账），落点腾不出位置就整件事都不做。</p>
 *
 * <p>两边都不是「先动再报错」：写盘先问 {@code PatternDiskItem.tryInsert}，取样板先问落点与空白样板。
 * 任何一步不成立都退回去，盘里的东西一件不少。菜单侧只留「客户端发动作 / 服务端调这里」那道岔口。</p>
 */
final class PatternDiskTransfer {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory
            .getLogger("ae2_pattern_disk.disk_transfer");

    private final PatternDiskEncodingTermMenu menu;

    PatternDiskTransfer(PatternDiskEncodingTermMenu menu) {
        this.menu = menu;
    }

    /**
     * 按顺序把编码槽里的样板写进候选磁盘，写进第一张能收的就停。
     *
     * <p>候选与顺序都由客户端给出，顺序就是玩家的意图顺序：编码终端是搜索栏筛出的列表，管理终端是
     * 右键选中的那张盘加上同一容器内的其他盘。全部写不进时只报<b>第一张被拒</b>（已过期的候选会被跳过）的
     * 原因，而不是逐张刷屏；一张盘都没碰到（列表过期）只留 debug 线头。</p>
     *
     * @return 是否真的写进了某一张盘
     */
    boolean transferTo(long[] serials) {
        var encoded = menu.dock().encoded();
        if (encoded.isEmpty() || !PatternDetailsHelper.isEncodedPattern(encoded)) {
            return false;
        }

        var level = menu.getPlayer().level();
        boolean stale = false;
        Component refusedName = null;
        PatternDiskItem.InsertFailure refusedReason = null;
        for (long serial : serials) {
            var ref = menu.diskIndex.refOf(serial);
            if (ref == null) {
                // 客户端列表可能比服务端旧：那张盘已经被拿走了。全部失败时再一起说，免得顺位中途刷屏。
                stale = true;
                continue;
            }
            var inv = ref.host().getDiskInventory();
            var stack = inv.getStackInSlot(ref.slot());
            if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem disk)) {
                stale = true;
                continue;
            }

            // 接收判据（容量/锁定类型/主产物互斥）统一由 PatternDiskItem.canInsert/tryInsert 负责
            var updated = stack.copy();
            if (disk.tryInsert(updated, encoded, level)) {
                menu.writeDiskSlot(inv, ref.slot(), updated);
                // 样板已存入磁盘：编码槽清空，原编码样板回退为空白样板，按 空白样板槽→ME网络→玩家背包→编码槽 落位
                menu.dock().setEncoded(ItemStack.EMPTY);
                menu.returnBlankPatternToStorage();
                return true;
            }
            if (refusedName == null) {
                refusedName = stack.getHoverName();
                refusedReason = disk.whyCannotInsert(stack, encoded, level);
            }
        }

        if (refusedName != null) {
            notifyDiskRefused(refusedName, refusedReason);
        } else if (stale) {
            // 顺位里的盘全没了（客户端列表滞后）：AE2 原生对「目标已经不在」也是静默的，这里不出声。
            LOGGER.debug("[encoding] 顺位写盘时目标磁盘都已不在（客户端列表滞后）");
        }
        return false;
    }

    /** 样板写不进磁盘时说明理由。原因与写入路径共用同一套判据（见 whyCannotInsert）。 */
    private void notifyDiskRefused(Component diskName, @Nullable PatternDiskItem.InsertFailure reason) {
        var key = reason == null ? "unknown" : switch (reason) {
            case FULL -> "full";
            case TYPE_LOCKED -> "type_locked";
            case DUPLICATE_OUTPUT -> "duplicate_output";
            case UNRESOLVABLE -> "unresolvable";
        };
        menu.tell("gui.ae2_pattern_disk.encoding_terminal.disk_refused." + key, diskName);
    }

    // ---- 从磁盘取东西（管理终端的左键 / Shift+左键 / 右键）----------------------------------

    /**
     * 服务端：从磁盘里取出一张样板，或者整张磁盘。
     *
     * <p>样板是「物化」出来的：按本模组一贯口径，把一张样板带出磁盘要消耗网络里的一张空白样板（与样板访问
     * 终端取走同一本账）；磁盘本身只是件物品，不扣。落点腾不出位置、或网络里没有空白样板时，整件事都不做：
     * 既不扣空白样板，也不动磁盘。</p>
     */
    void extract(@Nullable ExtractRequest request) {
        // 畸形的客户端可能送来缺字段的请求；这里不报错也不改任何东西。
        if (request == null || request.target == null) {
            return;
        }
        var ref = menu.diskIndex.refOf(request.serial);
        if (ref == null) {
            LOGGER.debug("[encoding] 目标磁盘已不在列表（客户端滞后），丢弃这次请求");
            return;
        }
        var inv = ref.host().getDiskInventory();
        var stack = inv.getStackInSlot(ref.slot());
        if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem disk)) {
            LOGGER.debug("[encoding] 槽里已不是样板磁盘（客户端滞后），丢弃这次请求");
            return;
        }

        if (request.index == PatternDiskEncodingTermMenu.WHOLE_DISK) {
            // 编辑槽只收样板，整张磁盘不进它（客户端不会这么请，防的是改造过的客户端）。
            if (request.target == ExtractTarget.ENCODED_SLOT || !hasRoomFor(request.target)) {
                return;
            }
            inv.setItemDirect(ref.slot(), ItemStack.EMPTY);
            menu.syncDiskList(true);
            deliverExtracted(stack.copy(), request.target);
            return;
        }

        var patterns = disk.contents(stack).patterns();
        if (request.index < 0 || request.index >= patterns.size()) {
            // 盘里的张数与客户端看到的不一致（刚被写入/取走）。不是「落点」也不是「网络缺料」，说清楚。
            notifyContentChanged();
            return;
        }
        if (patterns.get(request.index).isEmpty()) {
            return;
        }
        if (!hasRoomFor(request.target)) {
            return;
        }
        if (!menu.consumeBlankPattern()) {
            return;
        }

        var extracted = patterns.get(request.index).copy();
        var updated = stack.copy();
        disk.removeAt(updated, request.index);
        menu.writeDiskSlot(inv, ref.slot(), updated);
        deliverExtracted(extracted, request.target);
    }

    /** 取出来的东西有没有地方放。 */
    private boolean hasRoomFor(ExtractTarget target) {
        return switch (target) {
            case CURSOR -> menu.getCarried().isEmpty();
            case INVENTORY -> menu.getPlayer().getInventory().getFreeSlot() >= 0;
            case ENCODED_SLOT -> menu.dock().encoded().isEmpty();
        };
    }

    private void deliverExtracted(ItemStack stack, ExtractTarget target) {
        switch (target) {
            case CURSOR -> menu.setCarried(stack);
            case INVENTORY -> {
                // 预检过有空位；万一还是整件塞不下，丢在玩家脚下也不让它凭空消失。
                if (!menu.getPlayer().getInventory().add(stack)) {
                    menu.getPlayer().drop(stack, false);
                }
            }
            // 走到这里的只能是样板：extract 已挡住「整张磁盘进编辑槽」，取出来的单张也必定是编码样板。
            case ENCODED_SLOT -> menu.dock().setEncoded(stack);
        }
        // 推一次：槽位内容与光标上的东西都靠这一个包到客户端（光标的同步在 broadcastChanges 里）。
        menu.broadcastChanges();
    }

    /** 盘里那张样板的序号已经无效（内容刚变过）时记一条：不是落点问题，也不是网络缺料。 */
    private void notifyContentChanged() {
        LOGGER.info("[encoding] 客户端列表滞后：盘里的样板序号已失效，丢弃这次取样板");
    }
}
