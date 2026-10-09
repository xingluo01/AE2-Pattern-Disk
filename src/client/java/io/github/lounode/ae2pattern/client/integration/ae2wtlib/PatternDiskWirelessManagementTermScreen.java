package io.github.lounode.ae2pattern.client.integration.ae2wtlib;

import org.anti_ad.mc.ipn.api.IPNPlayerSideOnly;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import appeng.client.gui.style.ScreenStyle;

import de.mari_023.ae2wtlib.api.gui.ScrollingUpgradesPanel;
import de.mari_023.ae2wtlib.api.terminal.ItemWUT;
import de.mari_023.ae2wtlib.api.terminal.IUniversalTerminalCapable;
import de.mari_023.ae2wtlib.api.terminal.WTMenuHost;

import io.github.lounode.ae2pattern.client.gui.PatternDiskManagementTermScreen;
import io.github.lounode.ae2pattern.integration.ae2wtlib.PatternDiskWirelessManagementTermMenu;

/**
 * 无线版管理终端的屏幕：表格、盘内内容、附加排序全部继承面板版管理终端，无线那一套（升级卡面板、终端切换
 * 按钮、热键）与{@link PatternDiskWirelessEncodingTermScreen 无线编码终端}同一口径。
 *
 * <p>{@code @IPNPlayerSideOnly}：整理模组的标注，与父屏同源（它没有 {@code @Inherited}，故在此重标一份），
 * 理由见 {@code PatternDiskEncodingTermScreen} 的类注释。</p>
 */
@IPNPlayerSideOnly
public class PatternDiskWirelessManagementTermScreen extends PatternDiskManagementTermScreen
        implements IUniversalTerminalCapable {

    /** 升级卡面板：留给 {@code init()} 之后按可见行数回写行数（同无线编码终端）。 */
    private ScrollingUpgradesPanel upgradesPanel;

    public PatternDiskWirelessManagementTermScreen(PatternDiskWirelessManagementTermMenu menu,
            Inventory playerInventory, Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);
        // 同无线编码终端：切换按钮在构造器里挂（与 AE2WTLib 自己的无线终端同一时机）。
        if (menu.getTerminalHost().getItemStack().getItem() instanceof ItemWUT) {
            addToLeftToolbar(cycleTerminalButton());
        }
    }

    @Override
    public void init() {
        // init() 在窗口 resize（rebuildWidgets）时会被重复调用，而 addUpgradePanel 会往同一个
        // WidgetContainer 里注册名为 upgradeScrollbar 的滚动条——第二次注册直接抛
        // IllegalStateException: Duplicate id。所以面板只在首次开屏时建，之后只更新行数。
        if (this.upgradesPanel == null) {
            this.upgradesPanel = addUpgradePanel(widgets, getMenu());
        }
        super.init();
        this.upgradesPanel.setMaxRows(Math.max(2, getVisibleRows()));
        // 切换按钮不再排到「切换模式」之后：保持构造器里的位置（上游 AE2WTLib 自家的终端也是这么摆的
        // ——它就在指南按钮之后）。挪到末尾会把按钮推到面板下沿之外（工具栏每枚 22px，末位 y≈245 而
        // 面板高 220），AE2LT 在场时还会再追加两枚按钮；那时切换按钮与它弹出的选择面板一起落到可视区
        // 之外，点上去没反应。详细推理见 CellManagementWirelessTermScreen 同名段落。
    }


    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return checkForTerminalKeys(keyCode, scanCode) || super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public WTMenuHost getHost() {
        return ((PatternDiskWirelessManagementTermMenu) getMenu()).getTerminalHost();
    }
}
