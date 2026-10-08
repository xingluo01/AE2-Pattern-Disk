package io.github.lounode.ae2pattern.common.menu;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.FuzzyMode;
import appeng.api.inventories.InternalInventory;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import appeng.api.upgrades.Upgrades;

/**
 * 「元件升级槽」的库存：不跟着终端走，跟着<b>元件编码槽里那张元件</b>走。
 *
 * <p>这是元件工作台的语义：升级卡插的是元件本身（容量卡、模糊卡……），把元件从编码槽里拿走，
 * 卡也跟着元件走。所以这里不持有任何库存，每次读写都现问编码槽里当前那张元件要它自己的升级库存
 * （{@link ICellWorkbenchItem} 继承自 {@code IUpgradeableItem}，这条读法正是 AE2 元件工作台用的）。</p>
 *
 * <p>槽位数量固定为 {@link #SIZE}，与贴图上的六个格子一致；元件自己允许插几张由它自己的升级库存判定
 * （{@code isItemValid} 直接转发），超出部分会被它拒收——不是我们替它决定的。</p>
 *
 * <p>编码槽为空时整片槽位不可用（读回空栈、写入被拒），槽底由图层的显隐逻辑画成暗色。</p>
 */
public class EncodeCellUpgrades implements InternalInventory {

    /** 槽位数：贴图上元件升级槽一行六格。 */
    public static final int SIZE = 6;

    private final ICellManagementHost host;

    public EncodeCellUpgrades(ICellManagementHost host) {
        this.host = host;
    }

    /** 编码槽里当前那张元件；为空（或不是元件工作台物品）时返回 null。 */
    private IUpgradeInventory current() {
        ItemStack cell = host.getEncodeCellInventory().getStackInSlot(0);
        if (cell.isEmpty() || !(cell.getItem() instanceof ICellWorkbenchItem workbenchItem)) {
            return null;
        }
        // 接口没写“必定非 null”，而 AE2 自己读同一方法时也兜了一层（CellWorkbenchBlockEntity），跟着它兜。
        IUpgradeInventory upgrades = workbenchItem.getUpgrades(cell);
        return upgrades != null ? upgrades : UpgradeInventories.empty();
    }

    @Override
    public int size() {
        return SIZE;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        IUpgradeInventory upgrades = current();
        if (upgrades == null || slot < 0 || slot >= upgrades.size()) {
            return ItemStack.EMPTY;
        }
        return upgrades.getStackInSlot(slot);
    }

    @Override
    public void setItemDirect(int slot, ItemStack stack) {
        IUpgradeInventory upgrades = current();
        if (upgrades == null || slot < 0 || slot >= upgrades.size()) {
            return;
        }
        upgrades.setItemDirect(slot, stack);
        host.markForSave();
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        IUpgradeInventory upgrades = current();
        if (upgrades == null || slot < 0 || slot >= upgrades.size()) {
            return false;
        }
        return upgrades.isItemValid(slot, stack);
    }

    /** 只转发槽位边界：真正的准入判定在元件自己的升级库存里，这里不替它拍板。 */
    @Override
    public int getSlotLimit(int slot) {
        IUpgradeInventory upgrades = current();
        if (upgrades == null || slot < 0 || slot >= upgrades.size()) {
            return 0;
        }
        return upgrades.getSlotLimit(slot);
    }

    /** 模糊模式转发给元件（模糊卡装在元件上，档位也记在元件上）。 */
    public FuzzyMode fuzzyMode() {
        ItemStack cell = host.getEncodeCellInventory().getStackInSlot(0);
        return cell.getItem() instanceof ICellWorkbenchItem workbenchItem
                ? workbenchItem.getFuzzyMode(cell)
                : FuzzyMode.IGNORE_ALL;
    }

    /**
     * 这张元件能插入的升级卡清单（带已装/上限），供升级槽的槽位提示用。
     *
     * <p>卡的种类问 AE2 的全局登记表（把 {@code Upgrades.getUpgradableItems()} 的各家支持集合并起来），
     * 「能不能插这张元件」由元件自己的升级库存回话（{@code getMaxInstalled}）——不在这里再维护一份白名单，
     * 否则模组新加的卡就漏了。</p>
     */
    public List<Component> insertableCards() {
        // AE2 原生的那一套：它给「可升级对象」生成的提示行（Upgrades 登记表里那张对照表）。
        // 元件不走 Upgrades.add 登记（AE2 的存储元件就没登记），拿不到就退回去按元件自己的升级库存列一遍。
        ItemStack cell = host.getEncodeCellInventory().getStackInSlot(0);
        if (cell.isEmpty()) {
            return List.of();
        }
        var nativeLines = Upgrades.getTooltipLinesForMachine(cell.getItem());
        if (!nativeLines.isEmpty()) {
            return nativeLines;
        }
        IUpgradeInventory upgrades = current();
        if (upgrades == null) {
            return List.of();
        }
        var seen = new LinkedHashSet<Item>();
        var lines = new ArrayList<Component>();
        for (var cards : Upgrades.getUpgradableItems().values()) {
            for (Item card : cards) {
                if (!seen.add(card)) {
                    continue;
                }
                int max = upgrades.getMaxInstalled(card);
                if (max <= 0) {
                    continue;
                }
                lines.add(Component.translatable(
                        "gui.ae2_pattern_disk.cell_management_terminal.upgrade_tooltip.entry",
                        new ItemStack(card).getHoverName(), upgrades.getInstalledUpgrades(card), max));
            }
        }
        return lines;
    }
}
