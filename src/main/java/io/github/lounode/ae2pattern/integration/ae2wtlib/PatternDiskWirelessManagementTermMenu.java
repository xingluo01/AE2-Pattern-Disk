package io.github.lounode.ae2pattern.integration.ae2wtlib;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.slot.RestrictedInputSlot;

import de.mari_023.ae2wtlib.api.gui.AE2wtlibSlotSemantics;
import de.mari_023.ae2wtlib.api.terminal.WTMenuHost;

import io.github.lounode.ae2pattern.common.menu.PatternDiskManagementTermMenu;

/**
 * 无线版样板磁盘管理终端的菜单：分组表格、盘内内容按需推送等都继承面板版，只换宿主与类型（理由同
 * {@link PatternDiskWirelessEncodingTermMenu}）。
 */
public class PatternDiskWirelessManagementTermMenu extends PatternDiskManagementTermMenu {

    public static final MenuType<PatternDiskWirelessManagementTermMenu> TYPE = MenuTypeBuilder
            .create(PatternDiskWirelessManagementTermMenu::new, WirelessPatternDiskTerminalHost.class)
            .buildUnregistered(
                    ResourceLocation.parse("ae2_pattern_disk:wireless_pattern_disk_management_terminal"));

    public PatternDiskWirelessManagementTermMenu(int id, Inventory ip, WirelessPatternDiskTerminalHost host) {
        super(TYPE, id, ip, host);
        this.host = host;
        // 奇点槽（理由同无线编码终端：升级面板靠它认列表首元素）。
        addSlot(new RestrictedInputSlot(RestrictedInputSlot.PlacableItemType.QE_SINGULARITY,
                host.getSubInventory(WTMenuHost.INV_SINGULARITY), 0), AE2wtlibSlotSemantics.SINGULARITY);
    }

    /** 无线终端要升级槽（理由同无线编码终端）。 */
    @Override
    protected boolean supportsUpgradeSlots() {
        return true;
    }

    /** 屏幕侧要宿主来挂 AE2WTLib 的面板（理由同编码终端的无线菜单）。 */
    public WirelessPatternDiskTerminalHost getTerminalHost() {
        return this.host;
    }

    private final WirelessPatternDiskTerminalHost host;
}
