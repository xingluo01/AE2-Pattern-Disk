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
import io.github.lounode.ae2pattern.client.gui.ToolbarOrder;
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

    /** 通用终端里的切换按钮：构造器里挂上，{@code init()} 里再排到「切换模式」之后。 */
    private de.mari_023.ae2wtlib.api.gui.IconButton terminalSwitchButton;

    public PatternDiskWirelessManagementTermScreen(PatternDiskWirelessManagementTermMenu menu,
            Inventory playerInventory, Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);
        // 同无线编码终端：切换按钮在构造器里挂（与 AE2WTLib 自己的无线终端同一时机）。
        if (menu.getTerminalHost().getItemStack().getItem() instanceof ItemWUT) {
            this.terminalSwitchButton = cycleTerminalButton();
            addToLeftToolbar(this.terminalSwitchButton);
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
        // 切换按钮排在「切换模式」（本屏的 modeCycleButton 继承自编码屏）之后：工具栏按挂载顺序摆，
        // 而本按钮是构造器里挂的，不重排就会跑到模式按钮前面。
        if (this.terminalSwitchButton != null) {
            ToolbarOrder.placeAfter(this, this.terminalSwitchButton, this.modeCycleButton);
        }
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
