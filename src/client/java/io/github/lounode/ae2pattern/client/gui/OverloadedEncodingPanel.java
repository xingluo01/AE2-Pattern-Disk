package io.github.lounode.ae2pattern.client.gui;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import org.jetbrains.annotations.Nullable;

import appeng.client.Point;
import appeng.client.gui.Icon;
import appeng.client.gui.Tooltip;
import appeng.client.gui.WidgetContainer;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.widgets.Scrollbar;
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
 * 右侧 {@value #LABEL_GAP}px、与行垂直居中）和一枚组件匹配开关。文字左端起于 x={@value #LABEL_X}，
 * 开关右边缘停在 {@value #PANEL_W}-{@value #BUTTON_MARGIN}，两者都不越出 115 宽的覆盖层。</p>
 *
 * <p>开关是**关=启用组件匹配**（默认）、开=忽略组件匹配，用 AE2 那枚 22&times;12 的左右切换条
 * （{@code checkbox.png}，关 v=28、开 v=40）——与 AE2LT 自己的过载样板编码器同一枚、同一区域。
 * 它**不用 widget**（那是屏幕级容器，位置由样式文档给死，而这里每显示行一枚、还要跟滚动走），
 * 所以绘制与命中都在本面板里手工做；命中判定只有一份（{@link #switchRowAt}），绘制、点击、悬停提示
 * 三处共用，免得各算一遍坐标而分叉。</p>
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
    /** 开关右边缘与覆盖层右边缘的间隔。 */
    private static final int BUTTON_MARGIN = 6;
    /** 开关尺寸：AE2 那枚复选框就是 22&times;12。 */
    private static final int SWITCH_W = 22;
    private static final int SWITCH_H = 12;

    /** 文字左端：物品格右边缘 + 10px。 */
    private static final int LABEL_X = SLOT_X + SLOT_SIZE + LABEL_GAP;
    /** 开关左端：右边缘倒推边距与自身宽度。 */
    private static final int SWITCH_X = PANEL_W - BUTTON_MARGIN - SWITCH_W;

    /**
     * 组件匹配开关的两个状态，取自 AE2 的 {@code checkbox.png}（本模组自带一份，见同目录 resources）。
     * 与 AE2LT 的过载样板编码器用的是同一张图的同一段区域。
     *
     * <p><b>参考尺寸必须写成 {@value #CHECKBOX_TEX}&times;{@value #CHECKBOX_TEX}</b>：Blitter 默认按
     * 256×256 把 src 矩形换算成归一化 UV，对这张 64×64 的小图会把 (0,28,22,12) 读成「256 分之 28」——
     * 只在图中间采到一个亚像素块，表观就是开关整个不见了。同样的坑本项目已经记过两回（管理终端那张
     * 512×512 的底图、高级档那套 16×16 的方向按钮），加新贴图时先看它的真实像素尺寸。</p>
     */
    private static final int CHECKBOX_TEX = 64;
    private static final Blitter CHECKBOX_OFF = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/checkbox.png"),
                    CHECKBOX_TEX, CHECKBOX_TEX)
            .src(0, 28, SWITCH_W, SWITCH_H);
    private static final Blitter CHECKBOX_ON = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/checkbox.png"),
                    CHECKBOX_TEX, CHECKBOX_TEX)
            .src(0, 40, SWITCH_W, SWITCH_H);

    /** 第几行正在滚动到窗口顶部。 */
    private int scroll;

    /** 与其它可滚动档同款的滚动条；底图左侧那条轨道就是给它留的。样式文档没这一格时为 null。 */
    @Nullable
    private final Scrollbar scrollbar;

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

    /**
     * 点(mousePos)命中了哪一行的开关；没命中返回 -1。返回的是**槽号**（已加滚动偏移），不是显示行号。
     *
     * <p>绘制、点击、悬停提示共用这一份判定。坐标是「鼠标的绝对位置减面板原点」，与高级档的方向按钮
     * 同一口径。</p>
     */
    private int switchRowAt(Point point) {
        if (!this.visible) {
            return -1;
        }
        var localX = point.getX() - x;
        var localY = point.getY() - y;
        // 两端都要查：只看右端的话，面板左侧那片空当与开关同处一个高度带，会被当成命中了某一行的开关。
        if (localX < SWITCH_X || localX >= SWITCH_X + SWITCH_W) {
            return -1;
        }
        for (int row = 0; row < ROWS; row++) {
            var top = rowCenterY(row) - SWITCH_H / 2;
            if (localY >= top && localY < top + SWITCH_H) {
                var index = row + scroll;
                return index < rowCount() ? index : -1;
            }
        }
        return -1;
    }

    @Override
    public boolean onMouseDown(Point mousePos, int button) {
        if (button != 0) {
            return false;
        }
        var slot = switchRowAt(mousePos);
        if (slot < 0) {
            return false;
        }
        // 点开关只翻「忽略组件」这一位；行的输入/输出去点物品格那条（本档暂无），不是这里。
        menu.setOverloadedRow(slot, menu.overloadedRowIsOutput(slot),
                !menu.overloadedRowIgnoresComponents(slot));
        return true;
    }

    @Nullable
    @Override
    public Tooltip getTooltip(int mouseX, int mouseY) {
        var slot = switchRowAt(new Point(mouseX, mouseY));
        if (slot < 0) {
            return null;
        }
        var key = menu.overloadedRowIgnoresComponents(slot)
                ? "gui.ae2_pattern_disk.encoding_terminal.overloaded_match_ignore"
                : "gui.ae2_pattern_disk.encoding_terminal.overloaded_match_strict";
        return new Tooltip(List.of(Component.translatable(key)));
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
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        // 滚动条是屏幕级 widget（构造器里的 widgets.addScrollBar），切模式时屏幕只对本面板调 setVisible，
        // 它不会跟着藏起来。判空是必须的：屏幕构造完 widget 就会设一次可见性，那一刻 scrollbar 可能还没赋值。
        if (this.scrollbar != null) {
            this.scrollbar.setVisible(visible);
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

            // 组件匹配开关：忽略组件时用「开」那一格。
            var checkbox = menu.overloadedRowIgnoresComponents(index) ? CHECKBOX_ON : CHECKBOX_OFF;
            checkbox.dest(originX + SWITCH_X, originY + rowCenterY(row) - SWITCH_H / 2).blit(guiGraphics);
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
