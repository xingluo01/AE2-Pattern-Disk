package io.github.lounode.ae2pattern.integration.ae2wtlib;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.slot.RestrictedInputSlot;

import de.mari_023.ae2wtlib.api.gui.AE2wtlibSlotSemantics;
import de.mari_023.ae2wtlib.api.terminal.WTMenuHost;

import io.github.lounode.ae2pattern.common.menu.CellManagementTermMenu;

/**
 * 无线版元件管理终端的菜单：表格、标记区、元件编码槽与升级槽全部继承面板版，只换宿主与类型。
 *
 * <p>类型必须在这里显式传给父类：父类那个公开构造器写死了面板版的 TYPE，沿用它在客户端会开出面板版界面，
 * 而数据来自无线菜单——界面与数据错位（两个样板终端都踩过同一条）。</p>
 */
public class CellManagementWirelessTermMenu extends CellManagementTermMenu {

    // 与另外两个无线终端同一条注册口径：不经 AE2 的 InitMenuTypes，单通道注册。
    public static final MenuType<CellManagementWirelessTermMenu> TYPE = MenuTypeBuilder
            .create(CellManagementWirelessTermMenu::new, WirelessCellManagementTerminalHost.class)
            .buildUnregistered(
                    ResourceLocation.parse("ae2_pattern_disk:wireless_cell_management_terminal"));

    public CellManagementWirelessTermMenu(int id, Inventory ip, WirelessCellManagementTerminalHost host) {
        super(TYPE, id, ip, host);
        this.host = host;
        // 奇点槽（理由同另外两个无线终端：AE2WTLib 的升级面板靠它认列表首元素，缺了它会把第一格升级槽
        // 当成奇点槽藏掉）。
        addSlot(new RestrictedInputSlot(RestrictedInputSlot.PlacableItemType.QE_SINGULARITY,
                host.getSubInventory(WTMenuHost.INV_SINGULARITY), 0), AE2wtlibSlotSemantics.SINGULARITY);
    }

    /** 屏幕侧要宿主来挂 AE2WTLib 的升级面板与终端切换按钮（理由同另外两个无线菜单的对应方法）。 */
    public WirelessCellManagementTerminalHost getTerminalHost() {
        return this.host;
    }

    private final WirelessCellManagementTerminalHost host;
}
