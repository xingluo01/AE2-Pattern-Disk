package io.github.lounode.ae2pattern.client.integration.ae2wtlib;

import org.anti_ad.mc.ipn.api.IPNPlayerSideOnly;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import appeng.client.gui.style.ScreenStyle;

import de.mari_023.ae2wtlib.api.gui.ScrollingUpgradesPanel;
import de.mari_023.ae2wtlib.api.terminal.ItemWUT;
import de.mari_023.ae2wtlib.api.terminal.IUniversalTerminalCapable;
import de.mari_023.ae2wtlib.api.terminal.WTMenuHost;

import io.github.lounode.ae2pattern.client.gui.PatternDiskEncodingTermScreen;
import io.github.lounode.ae2pattern.integration.ae2wtlib.PatternDiskWirelessEncodingTermMenu;

/**
 * 无线版编码终端的屏幕：布局、磁盘列表、附加排序按钮全部继承面板版，只多出 AE2WTLib 那一套无线终端的东西
 * ——升级卡面板、终端切换按钮、热键。三样都由 {@link IUniversalTerminalCapable} 的默认实现提供，本类负责
 * 把它们挂上去、并在按键时先问一句热键。
 *
 * <p>顺序与 AE2WTLib 自己的无线终端（{@code WETScreen}）一致：先挂按钮与升级面板、再走父类的
 * {@code init()} 铺开布局。</p>
 *
 * <p>{@code @IPNPlayerSideOnly}：整理模组的标注，与父屏同源（它没有 {@code @Inherited}，故在此重标一份），
 * 理由见 {@code PatternDiskEncodingTermScreen} 的类注释。</p>
 */
@IPNPlayerSideOnly
public class PatternDiskWirelessEncodingTermScreen extends PatternDiskEncodingTermScreen
        implements IUniversalTerminalCapable {

    /** 升级卡面板：留给 {@code init()} 之后按可见行数回写行数（与 AE2WTLib 自己的无线终端同口径）。 */
    private ScrollingUpgradesPanel upgradesPanel;

    public PatternDiskWirelessEncodingTermScreen(PatternDiskWirelessEncodingTermMenu menu,
            Inventory playerInventory, Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);
        // 终端切换按钮：挂在构造器里，与 AE2WTLib 自己的无线终端同一时机（addToLeftToolbar 依赖的
        // widgets 这时已就绪）；挂在这里而不是 init()：init() 会随每次 resize 重复调用，而那是纯追加的表。
        // 只在通用终端里打开时才有得切，判据照 wtlib：宿主物品是不是通用终端。
        // 挂上之后不再重排：它在栏里的位置就决定它能不能用——本屏面板高 251，末位按钮落点 y≈245，
        // 只差几像素就出面板；AE2LT 往同一条栏追加按钮时就会越过（见 CellManagementWirelessTermScreen）。
        if (menu.getTerminalHost().getItemStack().getItem() instanceof ItemWUT) {
            addToLeftToolbar(cycleTerminalButton());
        }
    }

    @Override
    public void init() {
        // 与父屏同一处理：upgradesPanel 只在首次开屏时建，否则 resize 触发的第二次 init 会重复注册
        // 同名滚动条（upgradeScrollbar）而抛 Duplicate id。
        if (this.upgradesPanel == null) {
            this.upgradesPanel = addUpgradePanel(widgets, getMenu());
        }
        super.init();
        // 行数按屏幕实际能放下多少收：不写这一句就恒为默认的 2 行，高屏会白白空着。
        this.upgradesPanel.setMaxRows(Math.max(2, getVisibleRows()));
        // 切换按钮不重排，理由同另两个无线屏（见 CellManagementWirelessTermScreen 里那段）。
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 无线终端的热键（切换终端、打开无线设置）优先于普通按键。
        return checkForTerminalKeys(keyCode, scanCode) || super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public WTMenuHost getHost() {
        return ((PatternDiskWirelessEncodingTermMenu) getMenu()).getTerminalHost();
    }
}
