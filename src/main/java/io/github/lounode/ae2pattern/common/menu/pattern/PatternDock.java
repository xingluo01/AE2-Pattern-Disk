package io.github.lounode.ae2pattern.common.menu.pattern;

import java.util.function.Supplier;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.Actionable;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.StorageHelper;
import appeng.core.definitions.AEItems;
import appeng.menu.slot.RestrictedInputSlot;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.menu.slot.BlankPatternSlot;

/**
 * 编码终端的两个样板槽与它们那本账：<b>编码槽</b>（成品/空白样板，一格）与<b>空白样板槽</b>（终端自带的
 * 一格存货，由网络补满）。
 *
 * <p>这块状态有一组必须一起成立的不变量，所以它整体搬到这里，菜单只持有它：</p>
 *
 * <ul>
 *   <li><b>槽优先，网络兜底</b>：编码先扣槽里的空白样板，槽空了才向网络要——玩家看得见的那一份先用掉；</li>
 *   <li><b>补料只补差额</b>：每 tick 把槽补到上限，只从网络抽缺的那几张，网络不够就抽到多少算多少；</li>
 *   <li><b>退还是一条兜底链</b>：空白样板回终端自己的槽 → ME 网络 → 玩家背包 → 编码槽，一路都放不下时也不丢；</li>
 *   <li><b>带能耗提取</b>：从网络拿空白样板要付能量（与 ME 终端 Shift 取货同口径），
 *       所以网络侧一律走 {@link StorageHelper#poweredExtraction}。</li>
 * </ul>
 *
 * <p>网络与玩家通过构造参数注入（网格、动作源、能量源、玩家、推送回调），本类不认识菜单——菜单只负责把
 * 两个槽建出来登记给 AE2，然后转调这里。</p>
 */
final class PatternDock {

    private final RestrictedInputSlot encoded;
    private final BlankPatternSlot blank;

    /** 当前可用的网；断链时为 null（与菜单 {@code gridOrNull()} 同一判据）。 */
    private final Supplier<IGrid> grid;
    private final Supplier<IActionSource> actionSource;
    private final Supplier<IEnergySource> energy;
    private final Supplier<Player> player;

    /** 槽内容变了要通知客户端（光标与槽位都靠这一下同步）。 */
    private final Runnable push;

    PatternDock(RestrictedInputSlot encoded, BlankPatternSlot blank,
            Supplier<IGrid> grid, Supplier<IActionSource> actionSource, Supplier<IEnergySource> energy,
            Supplier<Player> player, Runnable push) {
        this.encoded = encoded;
        this.blank = blank;
        this.grid = grid;
        this.actionSource = actionSource;
        this.energy = energy;
        this.player = player;
        this.push = push;
    }

    RestrictedInputSlot encodedSlot() {
        return encoded;
    }

    BlankPatternSlot blankSlot() {
        return blank;
    }

    /** 编码槽里那张样板；空槽给空栈。 */
    ItemStack encoded() {
        return encoded.getItem();
    }

    void setEncoded(ItemStack stack) {
        encoded.set(stack);
    }

    /** 这张是不是空白样板（编码消耗与退还链的判据只写在这里）。 */
    static boolean isBlank(ItemStack stack) {
        return !stack.isEmpty() && AEItems.BLANK_PATTERN.is(stack);
    }

    /** 编码槽里停着的是已编好的样板时，换成等量的空白样板（「清除」与「取走」用）。 */
    void clearToBlank() {
        ItemStack current = encoded();
        if (PatternDetailsHelper.isEncodedPattern(current)) {
            encoded.set(AEItems.BLANK_PATTERN.stack(current.getCount()));
        }
    }

    // ---- 空白样板：槽优先，网络兜底 -----------------------------------------------------

    /**
     * 服务端：把空白样板槽补满（只抽差额），返回抽完之后网络里还剩多少。
     *
     * <p>槽是终端自带的一格存货，不是网络库存的又一次同步——网络只是它的来源。补满放在每 tick 的
     * {@code broadcastChanges} 里，所以编码消耗一张、玩家拿走一张，下一 tick 都会回到一叠；网络里没那么多
     * 就抽到多少算多少（不能凭空造）。</p>
     *
     * <p>返回值同时是「客户端还能不能编」的依据：一次模拟查询同时回答「要不要补」与「还有没有」，不必问两遍。</p>
     *
     * <p>调用方负责推送：这里只改槽，不 broadcast——补料发生在 {@code broadcastChanges} 里，再推一次会自递归。</p>
     */
    long refill() {
        var currentGrid = grid.get();
        var storage = currentGrid == null ? null : currentGrid.getStorageService();
        if (storage == null) {
            return 0;
        }
        var key = AEItemKey.of(AEItems.BLANK_PATTERN);
        var source = actionSource.get();
        long available = storage.getInventory().extract(key, Long.MAX_VALUE, Actionable.SIMULATE, source);

        ItemStack current = blank.getItem();
        int has = current.isEmpty() ? 0 : current.getCount();
        int missing = BlankPatternSlot.CAPACITY - has;
        if (missing <= 0 || available <= 0) {
            return available;
        }
        // 走 AE2 的带能耗提取：终端从网络拿东西要付能量，与 ME 终端 Shift 取货同一口径。
        long drawn = StorageHelper.poweredExtraction(energy.get(), storage.getInventory(), key,
                Math.min(missing, available), source);
        if (drawn <= 0) {
            return available;
        }
        // 从现有栈派生而不是新建一叠：第三方给空白样板挂的组件不该被补料抹掉。
        blank.set(current.isEmpty()
                ? AEItems.BLANK_PATTERN.stack((int) drawn)
                : current.copyWithCount(has + (int) drawn));
        return available - drawn;
    }

    /**
     * 取一张空白样板：先扣槽里的，槽空了才向网络要（槽本身就由网络补满，这一层让「编出来的第一张」用玩家
     * 看得见的那一份）。两处都没有就返回 false。
     */
    boolean consume() {
        ItemStack inSlot = blank.getItem();
        if (isBlank(inSlot)) {
            blank.set(inSlot.copyWithCount(inSlot.getCount() - 1));
            return true;
        }
        return consumeFromNetwork();
    }

    /** 从网络取走一个空白样板；网络里没有或能量不够则返回 false（带能耗提取）。 */
    private boolean consumeFromNetwork() {
        var currentGrid = grid.get();
        var storage = currentGrid == null ? null : currentGrid.getStorageService();
        if (storage == null) {
            return false;
        }
        return StorageHelper.poweredExtraction(energy.get(), storage.getInventory(),
                AEItemKey.of(AEItems.BLANK_PATTERN), 1, actionSource.get()) > 0;
    }

    // ---- 退还：一条兜底链 ---------------------------------------------------------------

    /** 样板存进磁盘后，把回退产生的空白样板还回去。 */
    void returnBlank() {
        returnToStorage(AEPatternRegistries.blankPattern());
    }

    /**
     * 把一张样板交回：先回终端自己的空白样板槽（那是它的家，槽满才溢出），再网络，再背包，都放不下时退回
     * 编码槽——调用方已经清空编码槽，那是它唯一的去处。网络只吃下了一部分时，余量接着走同一条兜底链：
     * 不能因为「进去了一点」就把整张当作已退。
     *
     * <p>今天所有调用方都只退单件（编码槽本身限一件），所以「部分合入」在这些路径上不可达；这里仍按
     * {@code count} 推导余量，免得将来有人拿它退多件时把余量吞掉。</p>
     *
     * @param pattern 要退还的样板；空表示不退还（样板只是被搬了位置，物品并没有消失）
     */
    void returnToStorage(ItemStack pattern) {
        var remaining = pattern.copy();
        if (remaining.isEmpty()) {
            return;
        }
        // 槽优先：空白样板回到终端自己那一格，网络只是后备（与快捷移动、编码消耗同一口径）。
        if (isBlank(remaining) && blank.mayPlace(remaining)) {
            remaining = blank.safeInsert(remaining);
            if (remaining.isEmpty()) {
                push.run();
                return;
            }
        }
        var currentGrid = grid.get();
        var storage = currentGrid == null ? null : currentGrid.getStorageService();
        if (storage != null) {
            long inserted = storage.getInventory().insert(AEItemKey.of(remaining), remaining.getCount(),
                    Actionable.MODULATE, IActionSource.ofPlayer(player.get()));
            if (inserted >= remaining.getCount()) {
                push.run();
                return;
            }
            if (inserted > 0) {
                remaining.shrink((int) inserted);
            }
        }
        // 背包 add 可能只收下一部分：传进去的就是 remaining 本身，剩下的量留在它手里，不会重复给。
        if (player.get().getInventory().add(remaining)) {
            push.run();
            return;
        }
        encoded.set(remaining);
        push.run();
    }
}
