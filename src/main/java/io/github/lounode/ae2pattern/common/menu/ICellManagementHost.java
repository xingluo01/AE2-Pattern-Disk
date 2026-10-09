package io.github.lounode.ae2pattern.common.menu;

import org.jetbrains.annotations.Nullable;

import appeng.api.inventories.InternalInventory;
import appeng.api.storage.ITerminalHost;
import appeng.api.upgrades.IUpgradeInventory;

/**
 * 元件管理终端的宿主契约：这台终端需要宿主提供什么，面板形态与无线形态各自实现。
 *
 * <p>与样板终端那条 {@link IPatternDiskTerminalHost} 并列、互不继承：两者的槽位与语义完全不同
 * （那边是样板编码区，这边是元件编码槽与元件标记区），共用一个接口只会让两边都长出用不上的成员。</p>
 */
public interface ICellManagementHost extends ITerminalHost {

    /**
     * 终端自己的升级库存。
     *
     * <p>面板版交空（{@code UpgradeInventories.empty()}）：那里的升级槽改的是编码槽里那张元件，不是终端本身，
     * 交空才能让 {@code MEStorageMenu} 一格 UPGRADE 都不建。</p>
     *
     * <p>无线版不交空：它走 AE2 物品自己的升级卡槽（AE2 的 {@code ItemMenuHost.getUpgrades()} 是 final，
     * 返回的就是物品上那两格），而 AE2WTLib 的升级卡面板读的正是这两格。</p>
     */
    IUpgradeInventory getUpgrades();

    /**
     * 元件编码槽的库存（一格）：这里是「正在编辑的那个元件」，标记区改的就是它的分区配置。
     * 从元件存储槽右键移进来、右键再送回去，走的就是这一格。
     */
    InternalInventory getEncodeCellInventory();

    /** 内容变化后落盘。面板版转交主机，无线版写回物品组件。 */
    void markForSave();

    /**
     * 记下「现在要给哪一台配优先级」（首格键），供 AE2 的优先级界面读写。
     *
     * <p>选中态是屏幕本地的，服务端不知道玩家右键点了哪一台；开那个界面前必须由菜单把目标报到这里。
     * 传 null 表示没有目标。面板版存在部件自己身上，无线版写回物品组件：界面是按 locator 重新解析出的新
     * 实例，不落盘就读不到目标。</p>
     */
    void setPriorityTarget(@Nullable String leaderKey);
}
