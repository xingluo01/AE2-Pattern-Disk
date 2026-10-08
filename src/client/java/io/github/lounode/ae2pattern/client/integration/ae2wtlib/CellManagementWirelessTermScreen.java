package io.github.lounode.ae2pattern.client.integration.ae2wtlib;

import org.anti_ad.mc.ipn.api.IPNPlayerSideOnly;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import appeng.client.gui.style.ScreenStyle;

import de.mari_023.ae2wtlib.api.gui.ScrollingUpgradesPanel;
import de.mari_023.ae2wtlib.api.terminal.ItemWUT;
import de.mari_023.ae2wtlib.api.terminal.IUniversalTerminalCapable;
import de.mari_023.ae2wtlib.api.terminal.WTMenuHost;

import io.github.lounode.ae2pattern.client.gui.CellManagementTermScreen;
import io.github.lounode.ae2pattern.client.gui.ToolbarOrder;
import io.github.lounode.ae2pattern.integration.ae2wtlib.CellManagementWirelessTermMenu;

/**
 * 无线版元件管理终端的屏幕：表格、标记区、工具栏全部继承面板版，无线那一套（升级卡面板、终端切换按钮、热键）
 * 与{@link PatternDiskWirelessManagementTermScreen 无线样板磁盘管理终端}同一口径。
 *
 * <p>工具栏那一段有个差别：本终端没有「编码模式」那枚下拉按钮（那是样板编码屏的），所以切换按钮无处可依，
 * 直接排到整条栏的最后——通用终端的切换钮本来就该在最外侧。</p>
 *
 * <p>{@code @IPNPlayerSideOnly}：整理模组的标注，与父屏同源（它没有 {@code @Inherited}，故在此重标一份）。</p>
 */
@IPNPlayerSideOnly
public class CellManagementWirelessTermScreen extends CellManagementTermScreen
        implements IUniversalTerminalCapable {

    /** 升级卡面板：留给 {@code init()} 之后按可见行数回写行数（同另外两个无线终端）。 */
    private ScrollingUpgradesPanel upgradesPanel;

    /** 通用终端里的切换按钮：构造器里挂上，{@code init()} 里再排到整条栏的最后。 */
    private de.mari_023.ae2wtlib.api.gui.IconButton terminalSwitchButton;

    public CellManagementWirelessTermScreen(CellManagementWirelessTermMenu menu, Inventory playerInventory,
            Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);
        // 与另外两个无线终端同一时机（addToLeftToolbar 依赖的 widgets 这时已就绪）；放进 init() 不生效，
        // 还会随每次 resize 反复追加。只在通用终端里打开时才有得切，判据照 wtlib：宿主物品是不是通用终端。
        if (menu.getTerminalHost().getItemStack().getItem() instanceof ItemWUT) {
            this.terminalSwitchButton = cycleTerminalButton();
            addToLeftToolbar(this.terminalSwitchButton);
        }
    }

    @Override
    public void init() {
        // init() 在窗口 resize（rebuildWidgets）时会被重复调用，而 addUpgradePanel 会往同一个
        // WidgetContainer 里注册名为 upgradeScrollbar 的滚动条——第二次注册直接抛 Duplicate id。
        // 所以面板只在首次开屏时建，之后只更新行数。
        if (this.upgradesPanel == null) {
            this.upgradesPanel = addUpgradePanel(widgets, getMenu());
        }
        super.init();
        this.upgradesPanel.setMaxRows(Math.max(2, getVisibleRows()));
        // 切换按钮排到最后：父屏的 reorderToolbar 已经把那六枚放成一串，这里排完正好收在它们后面。
        if (this.terminalSwitchButton != null) {
            ToolbarOrder.placeAtEnd(this, java.util.List.of(this.terminalSwitchButton));
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return checkForTerminalKeys(keyCode, scanCode) || super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public WTMenuHost getHost() {
        return ((CellManagementWirelessTermMenu) getMenu()).getTerminalHost();
    }
}
