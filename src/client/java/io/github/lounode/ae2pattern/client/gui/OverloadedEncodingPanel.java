package io.github.lounode.ae2pattern.client.gui;

import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import org.jetbrains.annotations.Nullable;

import appeng.client.Point;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.WidgetContainer;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.widgets.Scrollbar;
import appeng.client.gui.widgets.ToggleButton;
import appeng.core.AELog;
import appeng.menu.SlotSemantics;

/**
 * 过载编码模式面板：给编辑区每一行标记「这是输入还是输出」，并逐行决定要不要忽略组件匹配。
 *
 * <p>贴图 {@code pattern_modes_ext.png} 的 (0,72,115,66) 是这一整块。左侧几何与高级档**完全相同**
 * （滚动轨道 (6,6,5,54)、三行 18&times;18 物品格从 (14,6) 起、行距 18）——规格要求两档的轨道与物品槽
 * 相对位置一致，改这里时别只改一边。</p>
 *
 * <p>与高级档的区别全在右侧那两条：没有方向按钮，取而代之的是每行一枚「输入/输出」文字（贴在物品格
 * 右侧 {@value #LABEL_GAP}px、与行垂直居中）和一枚 AE2 原版的 {@link ToggleButton}（组件匹配开关）。
 * 文字左端起于 x={@value #LABEL_X}，开关右边缘严格停在 x={@value #BUTTON_X}+{@value #BUTTON_SIZE}
 * = {@value #PANEL_W}-{@value #BUTTON_MARGIN}，两者都不越出 115 宽的覆盖层。</p>
 *
 * <p>开关是**关=启用组件匹配**（默认）、开=忽略组件匹配。这与 AE2 那枚「可替换」开关的语义方向一致
 * （点亮即放宽匹配），所以直接沿用它的图标对（{@code S_SUBSTITUTION_ENABLED/DISABLED}）与 halfSize
 * 尺寸，观感与终端里既有的那几枚开关一致。</p>
 */
public class OverloadedEncodingPanel extends DiskEncodingModePanel {

    private static final Blitter BG = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/pattern_modes_ext.png"))
            .src(0, 72, 115, 66);

    // ---- 与高级档共用的左侧几何（两档必须一致，见类注释）----
    private static final int TRACK_H = 54;
    private static final int SLOT_X = 14;
    private static final int SLOT_Y = 6;
    private static final int SLOT_SIZE = 18;
    private static final int ROWS = 3;

    /** 覆盖层宽度。右侧那两样东西都以它为界。 */
    private static final int PANEL_W = 115;
    /** 输入/输出文字与物品格右边缘的距离。 */
    private static final int LABEL_GAP = 10;
    /** 切换按钮右边缘与覆盖层右边缘的间隔——规格要求严格 6px。 */
    private static final int BUTTON_MARGIN = 6;
    /** halfSize 的 IconButton 就是 8×8。 */
    private static final int BUTTON_SIZE = 8;

    /** 文字左端：物品格右边缘 + 10px。 */
    private static final int LABEL_X = SLOT_X + SLOT_SIZE + LABEL_GAP;
    /** 开关左端：右边缘倒推 6px 边距与 8px 自身宽度。 */
    private static final int BUTTON_X = PANEL_W - BUTTON_MARGIN - BUTTON_SIZE;

    /** 第几行正在滚动到窗口顶部。 */
    private int scroll;

    /** 与其它可滚动档同款的滚动条；底图左侧那条轨道就是给它留的。样式文档没这一格时为 null。 */
    @Nullable
    private final Scrollbar scrollbar;

    /**
     * 每显示行一枚组件匹配开关。
     *
     * <p>它们不进 {@code widgets}（那是**屏幕级**容器，位置由样式文档给死），而是跟着面板走、每帧摆一次：
     * 面板会随模式切换显示/隐藏、还会滚动，静态键位满足不了。绘制与命中都在本面板里手动转发。</p>
     */
    private final ToggleButton[] matchButtons = new ToggleButton[ROWS];

    public OverloadedEncodingPanel(PatternDiskEncodingTermScreen screen, WidgetContainer widgets) {
        super(screen, widgets);
        // ExtendedAE Plus 的上传子屏借用自己的样式文档，那份里没有这一格，而 AE2 对缺失的键是开界面那一刻
        // 抛异常。挂不上就退化成「不可滚动」，不影响其余部分——与高级档同一处理。
        Scrollbar bar = null;
        try {
            bar = widgets.addScrollBar("overloadedPatternModeScrollbar", Scrollbar.SMALL);
            bar.setHeight(TRACK_H);
            bar.setCaptureMouseWheel(false);
        } catch (IllegalStateException e) {
            AELog.debug("Screen style has no 'overloadedPatternModeScrollbar' widget; the overloaded panel stays unscrollable");
        }
        this.scrollbar = bar;

        for (int row = 0; row < ROWS; row++) {
            final int displayRow = row;
            // 回调里现算槽号：滚动之后同一个显示行对应的是另一格，固定下标会把开关接到错的行上。
            var button = new ToggleButton(Icon.S_SUBSTITUTION_ENABLED, Icon.S_SUBSTITUTION_DISABLED,
                    on -> {
                        var slot = displayRow + this.scroll;
                        if (slot < rowCount()) {
                            menu.setOverloadedRow(slot, menu.overloadedRowIsOutput(slot), on);
                        }
                    });
            button.setHalfSize(true);
            button.setDisableBackground(true);
            // tooltip 交给按钮自己：AE2 在 AEBaseScreen 里遍历子控件、收实现了 ITooltip 的那些
            // （AEBaseScreen:347），所以注册进屏幕的控件会自带悬停提示，不需要面板再代管。
            button.setTooltipOn(List.of(Component.translatable(
                    "gui.ae2_pattern_disk.encoding_terminal.overloaded_match_ignore")));
            button.setTooltipOff(List.of(Component.translatable(
                    "gui.ae2_pattern_disk.encoding_terminal.overloaded_match_strict")));
            this.matchButtons[row] = button;
        }
    }

    /**
     * 把三个开关交给屏幕注册。这是 AE2 给复合控件的正道（{@code AEBaseScreen.init()} 会调
     * {@code widgets.populateScreen(this::addRenderableWidget, ...)}）：注册成屏幕的 vanilla 控件后，
     * 渲染、鼠标命中、悬停提示都走原版那套，不需要面板自己代劳。
     *
     * <p>自己调 {@code button.render(...)} 是不行的——那一下不会把它们挂进屏幕的控件表，实测就是
     * 「按钮根本不出现」。位置每帧在 {@link #updateBeforeRender()} 里更新（要跟滚动走）。</p>
     */
    @Override
    public void populateScreen(java.util.function.Consumer<AbstractWidget> addWidget, Rect2i bounds,
            AEBaseScreen<?> screen) {
        for (var button : this.matchButtons) {
            addWidget.accept(button);
        }
    }

    private int rowCount() {
        return menu.getSlots(SlotSemantics.PROCESSING_INPUTS).size();
    }

    private int maxScroll() {
        return Math.max(0, rowCount() - ROWS);
    }

    /** 某一显示行的垂直中心（相对面板左上角）。文字与开关都按它对齐。 */
    private static int rowCenterY(int row) {
        return SLOT_Y + row * SLOT_SIZE + SLOT_SIZE / 2;
    }

    @Override
    public void updateBeforeRender() {
        if (this.scrollbar == null) {
            scroll = Mth.clamp(scroll, 0, maxScroll());
        } else {
            // 下限给 1：maxScroll == 0 时 AE2 会把滚动条画成「禁用」外观，看上去像压根没有这条控件。
            this.scrollbar.setRange(0, Math.max(1, rowCount() - ROWS), ROWS);
            // 位置不在这里设——落在样式文档的 overloadedPatternModeScrollbar 上，与高级档同源：
            // 滚动条 = 面板位置 + (TRACK_X - 1, TRACK_Y)，即 left = 面板 left + 5、bottom = 面板 bottom - 6。
            scroll = Mth.clamp(this.scrollbar.getCurrentScroll(), 0, maxScroll());
        }

        // 每帧摆一次开关：位置跟着面板与滚动走，状态跟着菜单字段走（服务端权威，回读后下发）。
        for (int row = 0; row < ROWS; row++) {
            var button = this.matchButtons[row];
            var slot = row + scroll;
            var inRange = slot < rowCount();
            button.setPosition(x + BUTTON_X, y + rowCenterY(row) - BUTTON_SIZE / 2);
            button.visible = this.visible && inRange;
            button.setState(inRange && menu.overloadedRowIgnoresComponents(slot));
        }
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        // 滚动条是屏幕级 widget（构造器里的 widgets.addScrollBar），切模式时屏幕只对本面板调 setVisible，
        // 它不会跟着藏起来。判空是必须的：屏幕构造完 widget 就会设一次可见性，那一刻 scrollbar 可能还没赋值。
        if (this.scrollbar != null) {
            this.scrollbar.setVisible(visible);
        }
        // 三个开关现在注册在屏幕的控件表里，而屏幕只对**可见**的复合控件调 updateBeforeRender
        // （WidgetContainer.updateBeforeRender）——不在这里藏，切到别的档后它们会继续浮在上面。
        for (var button : this.matchButtons) {
            button.visible = false;
        }
    }

    @Override
    public void drawBackgroundLayer(GuiGraphics guiGraphics, Rect2i bounds, Point mouse) {
        var originX = bounds.getX() + x;
        var originY = bounds.getY() + y;
        BG.dest(originX, originY).blit(guiGraphics);

        var font = Minecraft.getInstance().font;
        var inputs = menu.getSlots(SlotSemantics.PROCESSING_INPUTS);
        for (int row = 0; row < ROWS; row++) {
            var index = row + scroll;
            if (index >= inputs.size()) {
                break;
            }

            // 物品格：底图上只有框，物品自己画（与高级档同款）。
            var stack = inputs.get(index).getItem();
            if (!stack.isEmpty()) {
                var slotX = originX + SLOT_X + 1;
                var slotY = originY + SLOT_Y + row * SLOT_SIZE + 1;
                guiGraphics.renderItem(stack, slotX, slotY);
                guiGraphics.renderItemDecorations(font, stack, slotX, slotY);
            }

            // 物品属性：这一行算输入还是输出。
            var label = menu.overloadedRowIsOutput(index)
                    ? Component.translatable("gui.ae2_pattern_disk.encoding_terminal.overloaded_side_output")
                    : Component.translatable("gui.ae2_pattern_disk.encoding_terminal.overloaded_side_input");
            guiGraphics.drawString(font, label, originX + LABEL_X, originY + rowCenterY(row) - font.lineHeight / 2,
                    0x404040, false);
        }
    }

    @Override
    public boolean onMouseWheel(Point mousePos, double delta) {
        if (this.scrollbar == null) {
            var next = Mth.clamp(scroll + (delta > 0 ? 1 : -1), 0, maxScroll());
            if (next == scroll) {
                return false;
            }
            scroll = next;
            return true;
        }
        return this.scrollbar.onMouseWheel(mousePos, delta);
    }

    @Override
    public Rect2i getBounds() {
        return new Rect2i(x, y, PANEL_W, 66);
    }

    @Override
    Icon getIcon() {
        return Icon.TAB_PROCESSING;
    }

    @Override
    public Component getTabTooltip() {
        return Component.translatable("gui.ae2_pattern_disk.encoding_terminal.overloaded_mode");
    }
}
