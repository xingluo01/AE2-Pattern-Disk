package io.github.lounode.ae2pattern.integration.ae2wtlib;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.slot.RestrictedInputSlot;

import de.mari_023.ae2wtlib.api.gui.AE2wtlibSlotSemantics;
import de.mari_023.ae2wtlib.api.terminal.WTMenuHost;

import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;

/**
 * 无线版样板磁盘编码终端的菜单：内容全部继承面板版（编码区、磁盘列表、写盘动作都一样），只换宿主与类型。
 *
 * <p>类型必须在这里显式传给父类：父类的公开构造器写死了面板版的 TYPE，沿用它在客户端会开出面板版界面，
 * 而数据来自无线菜单——界面与数据错位（面板版的管理终端已经踩过同一条）。</p>
 */
public class PatternDiskWirelessEncodingTermMenu extends PatternDiskEncodingTermMenu {

    // 与面板版同一条注册口径：不经 AE2 的 InitMenuTypes，单通道注册。
    public static final MenuType<PatternDiskWirelessEncodingTermMenu> TYPE = MenuTypeBuilder
            .create(PatternDiskWirelessEncodingTermMenu::new, WirelessPatternDiskTerminalHost.class)
            .buildUnregistered(
                    ResourceLocation.parse("ae2_pattern_disk:wireless_pattern_disk_encoding_terminal"));

    public PatternDiskWirelessEncodingTermMenu(int id, Inventory ip, WirelessPatternDiskTerminalHost host) {
        super(TYPE, id, ip, host);
        this.host = host;
        // 奇点槽必须排在升级槽前面：AE2WTLib 的升级面板按「列表第一个槽是奇点槽」来判定要不要把它藏起来，
        // 缺了它就会把第一格升级槽当奇点槽藏掉，可用升级卡位少一个。
        addSlot(new RestrictedInputSlot(RestrictedInputSlot.PlacableItemType.QE_SINGULARITY,
                host.getSubInventory(WTMenuHost.INV_SINGULARITY), 0), AE2wtlibSlotSemantics.SINGULARITY);
    }

    /** 无线终端要升级槽：AdvancedAE 的高级样板编码器放这儿（面板版终端不走这条路）。 */
    @Override
    protected boolean supportsUpgradeSlots() {
        return true;
    }

    /**
     * 屏幕侧要宿主来挂 AE2WTLib 的升级面板与终端切换按钮（{@code IUniversalTerminalCapable} 要求
     * {@code WTMenuHost}）；父类那个宿主字段是私有的，所以这里自己留一份。
     */
    public WirelessPatternDiskTerminalHost getTerminalHost() {
        return this.host;
    }

    private final WirelessPatternDiskTerminalHost host;
}
