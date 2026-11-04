package io.github.lounode.ae2pattern.common.menu.slot;

import appeng.api.inventories.InternalInventory;
import appeng.menu.slot.AppEngSlot;
import io.github.lounode.ae2pattern.common.menu.cell.CellDriveScanner;
import net.minecraft.world.item.ItemStack;
import io.github.lounode.ae2pattern.common.menu.cell.CellManagementTermMenu;

/**
 * 元件编码槽：只收存储元件与存储矩阵。
 *
 * <p>这一格是「正在被编辑的那张元件」，标记区改的就是它的分区。以前对放入的物品不设限，木棍也能塞进去，
 * 结果是界面全亮、点下去什么也没发生（见 {@code CellManagementTermMenu#hasWorkbenchCell}）。现在按
 * {@link CellDriveScanner#isStorageCell} 收口：AE2 系认 {@code IBasicCellItem} 与元存处理器，ECO 系的存储
 * 矩阵先问 ECO 的公开 API（{@code integration/neoecoae} 注册的探针），探针缺席时才退回接口名；计算元件
 * 这类「长着 {@code getCellStack} 但不是存储」的东西照旧被挡在外面。</p>
 *
 * <p>两种格式在这一格都收，因为玩家要编辑的既是元件也是矩阵；按格式分家发生在往具体格子里放那一步
 * （驱动器不收矩阵、矩阵座不收元件），闸在 {@link CellDriveScanner#insertCell}。</p>
 *
 * <p>为什么不用 {@code RestrictedInputSlot.PlacableItemType.STORAGE_CELLS}：那个枚举只认 AE2 自己的
 * 存储元件判定，ECO 系的存储矩阵（可选依赖）过不了它的闸；本项目的 {@code isStorageCell} 已经把两系
 * 都收进来了，复用它才不会出现「驱动器里列得出来、编码槽却放不进去」的错位。</p>
 */
public class StorageCellSlot extends AppEngSlot {

    public StorageCellSlot(InternalInventory inventory, int slot) {
        super(inventory, slot);
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        // 先走基类：它管着 wrapper 物品、槽位启用状态与库存自己的 isItemValid，别绕过。
        return super.mayPlace(stack) && CellDriveScanner.isStorageCell(stack);
    }
}
