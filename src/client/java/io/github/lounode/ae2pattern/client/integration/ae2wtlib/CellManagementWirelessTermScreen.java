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
import io.github.lounode.ae2pattern.integration.ae2wtlib.CellManagementWirelessTermMenu;

/**
 * 无线版元件管理终端的屏幕：表格、标记区、工具栏全部继承面板版，无线那一套（升级卡面板、终端切换按钮、热键）
 * 与{@link PatternDiskWirelessManagementTermScreen 无线样板磁盘管理终端}同一口径。
 *
 * <p>工具栏与另两个终端同一口径：清单在 {@code CellManagementTermScreen#toolbarPlan()} 里，「切换终端」
 * 就排在其中（第 7 位）。清单定顺序是有原因的：本屏面板只有 220 高（按钮步进 22px、起点 y≈3），
 * 排在末尾的按钮会被 AE2LT 那类“往同一条栏追加按钮”的模组一起顶出可视区。</p>
 *
 * <p>{@code @IPNPlayerSideOnly}：整理模组的标注，与父屏同源（它没有 {@code @Inherited}，故在此重标一份）。</p>
 */
@IPNPlayerSideOnly
public class CellManagementWirelessTermScreen extends CellManagementTermScreen
        implements IUniversalTerminalCapable {

    /** 升级卡面板：留给 {@code init()} 之后按可见行数回写行数（同另外两个无线终端）。 */
    private ScrollingUpgradesPanel upgradesPanel;

    public CellManagementWirelessTermScreen(CellManagementWirelessTermMenu menu, Inventory playerInventory,
            Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);
        // 与另外两个无线终端同一时机（addToLeftToolbar 依赖的 widgets 这时已就绪）；挂在这里而不是 init()：
        // init() 会随每次 resize 被重复调用，而那是个纯追加的表，重挂会多出一枚同按钮。只在通用终端里打开时才有得切，判据照 wtlib：宿主物品是不是通用终端。
        if (menu.getTerminalHost().getItemStack().getItem() instanceof ItemWUT) {
            addToLeftToolbar(cycleTerminalButton());
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
        // 切换按钮不再重排：现在「排到哪」已由父屏那份工具栏清单统一决定，而它会把「切换终端」排在中部
        // （第 7 位，落点 y≈135），不再有旧口径那个麻烦——以前没有清单、靠自己在 init() 里挪，本屏面板
        // 只有 220 高（按钮步进 22px、起点 y≈3），挪到末尾就会被 AE2LT 追加的按钮一起顶出可视区，
        // 表现为“点了没反应”。
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
