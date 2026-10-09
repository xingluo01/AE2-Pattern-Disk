package io.github.lounode.ae2pattern.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;

import appeng.api.config.Settings;
import appeng.client.gui.me.common.MEStorageScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.OpenGuideButton;
import appeng.client.gui.widgets.SettingToggleButton;
import appeng.core.localization.ButtonToolTips;
import appeng.menu.slot.DisabledSlot;

import io.github.lounode.ae2pattern.common.menu.AbstractPatternDiskTermMenu;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * 本模组终端的公共屏幕基座：背包里的 Shift+左键只有一个出口。
 *
 * <p>AE2 给这条手势的默认含义是「把物品送进 ME 网络」。本模组的终端分两类：编码终端有物品网格，照旧能收
 * （网络、样板编辑槽都算去处）；管理终端没有网络物品栏，菜单侧那条出口（{@link
 * AbstractPatternDiskTermMenu#transferStackToMenu}）已经关掉。屏幕这层的作用是：物品确实没地方去时给一句
 * 提示，而不是让玩家点了没反应。</p>
 *
 * <p>子类只需回答一个问题：这件物品有没有自己的去处。有（例如存进当前选中的那台驱动器）就接过去并返回
 * {@code true}；返回 {@code false} 时基类再看菜单里有没有真目标槽（或终端本来就接网络），实在没有才提示。</p>
 */
public abstract class AbstractPatternDiskTermScreen<T extends AbstractPatternDiskTermMenu>
        extends MEStorageScreen<T> {

    protected AbstractPatternDiskTermScreen(T menu, Inventory playerInventory, Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);
    }

    @Override
    protected void slotClicked(@Nullable Slot slot, int slotIdx, int mouseButton, ClickType clickType) {
        if (clickType == ClickType.QUICK_MOVE && slot != null && !(slot instanceof DisabledSlot)
                && !slot.getItem().isEmpty() && getMenu().isPlayerSideSlot(slot)) {
            if (onPlayerInventoryQuickMove(slot)) {
                return; // 子类给这件物品找了去处（例如存进选中的驱动器）
            }
            if (getMenu().wouldHandleQuickMove(slot.getItem())) {
                // 菜单里有真去处（真目标槽，或终端本来就接 ME 网络）：照常走原路，由服务端分配。
                super.slotClicked(slot, slotIdx, mouseButton, clickType);
                return;
            }
            showLocalNotice("gui.ae2_pattern_disk.notice.no_network_quick_move");
            return;
        }
        super.slotClicked(slot, slotIdx, mouseButton, clickType);
    }

    /**
     * 背包里 Shift+左键点到了某件物品：本屏给这件物品找了去处就在这里接过去。
     *
     * @return true 表示已接手（不再提示）；false 表示没地方去，由基类提示一句。
     */
    protected boolean onPlayerInventoryQuickMove(Slot slot) {
        return false;
    }

    /**
     * 一句只给自己看的提示。
     *
     * <p>落聊天栏（{@code false}）而不是动作栏：这类拒绝要能回头看见，动作栏三秒就没了。
     */
    protected static void showLocalNotice(String key) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable(key), false);
        }
    }

    //
    // 工具栏按钮的身份（槽位名）。三个终端共用这一套词汇，各自的清单只管排列与取舍（见 ToolbarPlan）。
    // 认不出来的返回 null，调用方按「不在清单里」处理。
    //

    /**
     * 这一枚是哪个槽位；认不出来返回 null。
     *
     * <p>子类接着往下认自家那几枚（模式轮换、分区/清除/复制、显示模式…）：先调 {@code super} 再过自家的字段。</p>
     */
    /**
     * 本屏左侧工具栏的清单：顺序即清单顺序，不在清单里的一律隐藏；返回空清单表示不管（照 AE2 的默认）。
     *
     * <p>各屏覆写它。**只在 {@link #init()} 里读一次**，不要各屏自己调 {@code ToolbarPlan.apply}：编解码屏与
     * 管理屏是继承关系，两边各应用一次的话，父屏那份清单会先把子屏多出来的按钮（显示模式 / 隐藏槽位）从栏里
     * 摘掉，子屏的清单就再也认不回来——它们会被永久隐藏。</p>
     */
    protected List<String> toolbarPlan() {
        return List.of();
    }

    @Override
    public void init() {
        super.init();
        // 取最派生那份清单（本类被各屏继承，virtual 调用自然就取到子屏的）。
        var plan = toolbarPlan();
        if (!plan.isEmpty()) {
            ToolbarPlan.apply(this, getClass().getSimpleName(), plan, this::toolbarSlot);
        }
    }

    /**
     * 按钮 → 槽位名；不在清单里、或认不出的一律返回 null（按不在清单里处理）。
     */
    @Nullable
    protected String toolbarSlot(Button button) {
        if (button instanceof OpenGuideButton) {
            return "guide";
        }
        if (button instanceof SettingToggleButton<?> toggle) {
            var setting = toggle.getSetting();
            if (setting == Settings.SORT_BY) {
                return "sortBy";
            }
            if (setting == Settings.SORT_DIRECTION) {
                return "sortOrder";
            }
            if (setting == Settings.VIEW_MODE) {
                return "viewMode";
            }
            if (setting == Settings.TERMINAL_STYLE) {
                return "terminalStyle";
            }
            if (setting == Settings.TERMINAL_SHOW_PATTERN_PROVIDERS) {
                return "showProviders";
            }
        }
        if (isTerminalSettingsButton(button)) {
            return "terminalSettings";
        }
        // 闪电科技那两枚（频率卡配置 / 自动连接开关）：按 tooltip 分身份，不引用它的类型。
        if (isFrequencyCardButton(button)) {
            return "frequencyCard";
        }
        if (isFrequencyAutoConnectButton(button)) {
            return "frequencyAutoConnect";
        }
        if (isTerminalSwitchButton(button)) {
            return "terminalSwitch";
        }
        return null;
    }

    /** AE2WTLib 那枚「切换终端」的类名与其父类名（它自己造的是前者，包私有，所以只比名字）。 */
    private static final String AE2WTLIB_TERMINAL_SELECTION = "de.mari_023.ae2wtlib.api.terminal.TerminalSelectionButton";
    private static final String AE2WTLIB_ICON_BUTTON = "de.mari_023.ae2wtlib.api.gui.IconButton";

    /**
     * 无线通用终端里 AE2WTLib 那枚「切换终端」（{@link #AE2WTLIB_TERMINAL_SELECTION}）。
     *
     * <p>按类名认，而不是靠各无线屏存一个句柄：靠句柄就得每个屏都记得把实例存好，漏一个就表现为「那枚按钮
     * 不见了」——白名单会把认不出身份的按钮隐藏。按类名认则一次覆盖三个无线屏。</p>
     *
     * <p>先比具体类名，再退一步比父类（{@code api.gui.IconButton}）——那个父类是 public 的，附属模组可以
     * 自己拿它造按钮；先比具体类名就不会把别人家的按钮误认成「切换终端」。父类名作后备是为了 AE2WTLib
     * 哪天换了实现类时还能认得出来。两个都只做字符串比对，不加载类。</p>
     *
     * <p>也不写成 {@code instanceof}：AE2WTLib 在本模组只是可选依赖（{@code compileOnly} + {@code localRuntime}），
     * 它缺席时那个类不在场，而这里会对每个按钮都调一次——用类名避开解析它的类。</p>
     */
    private static boolean isTerminalSwitchButton(Button button) {
        for (Class<?> type = button.getClass(); type != null; type = type.getSuperclass()) {
            var name = type.getName();
            if (AE2WTLIB_TERMINAL_SELECTION.equals(name)) {
                return true;
            }
            if (AE2WTLIB_ICON_BUTTON.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 「终端设置」那枚：AE2 的 {@code ActionButton(ActionItems.TERMINAL_SETTINGS)}。
     *
     * <p>不按类比对：它和本模组自己的「编码」等按钮同是 {@link appeng.client.gui.widgets.ActionButton}，
     * 只能按 tooltip 认（拿 AE2 自带的那条语言键去比已解析的文本，中英任一种语言下都成立）。</p>
     */
    protected static boolean isTerminalSettingsButton(Button button) {
        return tooltipMatches(button, ButtonToolTips.TerminalSettings.text().getString());
    }

    /**
     * 闪电科技那两枚：靠 tooltip 分身份，不引用它的类型（那个模组是可选的）。
     *
     * <p>两枚都是它自己的 {@code TextureToggleButton}，类上分不出谁是谁；而它们的 tooltip 键是固定的
     * （{@code ae2lt.gui.button.open_frequency_card} 与 {@code ae2lt.gui.button.auto_connect_on/off}），
     * 所以拿字面键去本地化再比文本。用字面键而不是它的常量：那个类不在编译面内。</p>
     */
    protected static boolean isFrequencyCardButton(Button button) {
        return tooltipMatches(button, Component.translatable("ae2lt.gui.button.open_frequency_card").getString());
    }

    protected static boolean isFrequencyAutoConnectButton(Button button) {
        return tooltipMatches(button, Component.translatable("ae2lt.gui.button.auto_connect_on").getString())
                || tooltipMatches(button, Component.translatable("ae2lt.gui.button.auto_connect_off").getString());
    }

    /**
     * 按 tooltip 文本认按钮；任何一枚的提示里含这个文本就算中。
     *
     * <p>闸门是 AE2 的 {@code ITooltip} 而不是 {@code IconButton}：AE2 自家的按钮都是 IconButton（它实现了
     * ITooltip），而闪电科技那两枚是它自己的 {@code TextureToggleButton extends Button implements ITooltip}
     * ——按 IconButton 认会把它们漏掉，于是它们落进「不在清单里」而被隐藏。</p>
     */
    private static boolean tooltipMatches(Button button, String text) {
        if (!(button instanceof appeng.client.gui.widgets.ITooltip tooltip)) {
            return false;
        }
        return tooltip.getTooltipMessage().stream()
                .anyMatch(line -> line.getString().contains(text));
    }
}
