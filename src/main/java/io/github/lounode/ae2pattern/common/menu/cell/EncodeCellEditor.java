package io.github.lounode.ae2pattern.common.menu.cell;

import net.minecraft.world.item.ItemStack;

import appeng.api.config.CopyMode;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.util.ConfigInventory;

import org.jetbrains.annotations.Nullable;

import io.github.lounode.ae2pattern.integration.extendedae.VoidCellCompat;
import io.github.lounode.ae2pattern.integration.megacells.MegaCellsCompat;

/**
 * 元件编码槽里那张元件的编辑：分区存储 / 清除 / 复制模式，以及它自己的几档专有属性
 * （MEGA 压缩截断、ECO 大宗截断、EAE 虚空输出模式）。
 *
 * <p>分区库存是「建时读进内存、改动时整份写回」的，所有写入口都必须拿窗口自己那份缓存实例；另开一个实例
 * 去写会把窗口那份留在旧内容上。</p>
 */
final class EncodeCellEditor {

    private final CellManagementTermMenu menu;
    private final EncodeCellPartitions partitions;

    EncodeCellEditor(CellManagementTermMenu menu, EncodeCellPartitions partitions) {
        this.menu = menu;
        this.partitions = partitions;
    }

    /** 编码槽里那张元件；空着时返回空栈。 */
    ItemStack cell() {
        return menu.getCellHost().getEncodeCellInventory().getStackInSlot(0);
    }

    /** 这张物品是不是元件工作台元件：不是的话标记格与升级格都不接受操作（同 AE2 元件工作台）。 */
    boolean hasWorkbenchCell() {
        ItemStack cell = cell();
        return !cell.isEmpty() && cell.getItem() instanceof ICellWorkbenchItem;
    }

    /** 编码槽那张元件的标记容器（窗口那份缓存实例）；没有可标记元件时 null。 */
    @Nullable
    ConfigInventory config() {
        return partitions.config();
    }

    /**
     * 批量写入前拿分区库：先按元件组件复核一次缓存，再交给调用方写。
     *
     * <p>与 {@link #config()} 分开是有意的：分区库是「整份写回」的，拿旧内容去写会把别的菜单实例刚做的改动
     * 盖掉。槽位那条写入路径在 {@link EncodeCellPartitions#setItemDirect(int, ItemStack)} 里已自行复核。</p>
     */
    @Nullable
    ConfigInventory configForWrite() {
        partitions.refreshIfStale();
        return partitions.config();
    }

    /** 「分区存储」：把元件里现有的东西写成它的分区（元件工作台同款）。 */
    void partition() {
        ItemStack cell = cell();
        if (cell.isEmpty() || !(cell.getItem() instanceof ICellWorkbenchItem)) {
            return;
        }
        var config = configForWrite();
        if (config == null) {
            return;
        }
        var storage = StorageCells.getCellInventory(cell, null);
        if (storage == null) {
            return;
        }
        int index = 0;
        for (var entry : storage.getAvailableStacks()) {
            if (index >= config.size()) {
                break;
            }
            config.setStack(index, new GenericStack(entry.getKey(), 1));
            index++;
        }
        saveCell();
    }

    /** 「清除」：把编码槽里那张元件的分区全部清空。 */
    void clear() {
        if (!hasWorkbenchCell()) {
            return;
        }
        var config = configForWrite();
        if (config == null) {
            return;
        }
        config.clear();
        saveCell();
    }

    /** 「复制模式」：两档循环，与元件工作台同一个枚举、同一个含义。 */
    void cycleCopyMode() {
        menu.copyMode = menu.copyMode == CopyMode.CLEAR_ON_REMOVE
                ? CopyMode.KEEP_ON_REMOVE
                : CopyMode.CLEAR_ON_REMOVE;
        menu.getCellHost().markForSave();
        menu.broadcastChanges();
    }

    /**
     * 切编码槽里那张元件的压缩截断物（MEGA 的大宗元件专有）。
     *
     * <p>只对「接在压缩链上且已启用压缩」的元件有意义：判据由客户端每帧现算（它决定按钮显不显示），这里
     * 再兜底一次，不是那种元件就什么都不做。</p>
     */
    void cycleCompressionCutoff(boolean reverse) {
        Object bulk = MegaCellsCompat.bulkInventoryOf(cell());
        if (bulk == null || !MegaCellsCompat.switchCutoff(bulk, reverse)) {
            return;
        }
        saveCell();
    }

    /**
     * 调编码槽里 ECO 大宗元件的压缩截断物。
     *
     * <p>ECO 21.2.11 起标记物本身就是截断层，所以写回的是 {@code cycleCutoff} 返回的新一级，而不只改显示值。</p>
     */
    void cycleEcoBulkCutoff(int markerSlot, int delta) {
        var site = CellBulkCutoffCompat.open(cell());
        if (site == null || !site.cycleCutoff(markerSlot, delta)) {
            return;
        }
        saveCell();
    }

    /** 「物质聚合模式」：循环切编码槽那张 EAE 虚空元件的输出（销毁 / 物质球 / 奇点）。 */
    void cycleVoidMode(boolean reverse) {
        if (!VoidCellCompat.cycleMode(cell(), reverse)) {
            return;
        }
        saveCell();
    }

    /**
     * 分区 / 属性的改动写在元件栈的组件里，元件栈本身得被通知变了才会落盘；再推一次槽位。
     *
     * <p>面板写部件 NBT、无线写物品组件，两个形态共用这一条。</p>
     */
    private void saveCell() {
        menu.getCellHost().getEncodeCellInventory().sendChangeNotification(0);
        menu.getCellHost().markForSave();
        menu.broadcastChanges();
    }
}
