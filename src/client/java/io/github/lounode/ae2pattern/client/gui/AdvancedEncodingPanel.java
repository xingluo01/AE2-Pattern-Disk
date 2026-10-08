package io.github.lounode.ae2pattern.client.gui;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import org.jetbrains.annotations.Nullable;

import appeng.client.Point;
import appeng.client.gui.Icon;
import appeng.client.gui.WidgetContainer;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.widgets.Scrollbar;
import appeng.core.AELog;
import appeng.menu.SlotSemantics;

/**
 * 高级编码模式面板：给样板输出栏那张高级样板的每个原材料分配一个接入面。
 *
 * <p>它不改样板的输入输出，只决定「这份材料从哪一面进邻居机器」——那正是 AdvancedAE 自己的编码器编不出来、
 * 而本模组的供应器按面投递时要读的东西。分配完之后点「编写样板」，编码路径会把方向表并进去，翻出高级处理
 * 样板，再走原有的顺位写盘。</p>
 *
 * <p>贴图 {@code pattern_modes_ext.png} 的 (0,0,115,66) 就是这一整块：左侧 (6,6,5,54) 是滚动轨道、
 * (14,6,18,54) 是三行原料格、右侧是 7&times;3 的方向按钮格。按钮格的上边框在 x=33/44/55/66/77/88/99
 * （格宽 10、连同分隔线间距 11），三行的上边框在 y=8/26/44（行距 18）。那三行原料格是画在**底图**上的，
 * 实际物品由本面板自己渲染——槽本身归处理模式的 3 列布局管，不借来用。</p>
 *
 * <p>按钮图标在 {@code adv_button/}（文件是 16&times;16 的画布，但**内容只占左上 10&times;14**，右 6 列与下
 * 2 行全透明，且它已经自带底图的格框）。所以按 1:1 取 10&times;14 贴到格子上，不缩放、不加偏移。</p>
 */
public class AdvancedEncodingPanel extends DiskEncodingModePanel {

    private static final Blitter BG = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/pattern_modes_ext.png"))
            .src(0, 0, 115, 66);

    /** 底图上的几何：第一颗按钮的左上角、格距、可见行数、原料格的位置。 */
    private static final int BUTTONS_X = 33;
    private static final int BUTTONS_Y = 8;

    /** 格宽 10，但相邻两格的上边框相距 11（中间那 1px 是分隔线）。 */
    private static final int BUTTON_W = 11;
    private static final int BUTTON_H = 18;
    private static final int ICON_W = 10;
    private static final int ICON_H = 14;
    private static final int COLUMNS = 7;
    private static final int ROWS = 3;
    private static final int SLOT_X = 14;
    private static final int SLOT_Y = 6;
    private static final int SLOT_SIZE = 18;

    /**
     * 底图左侧那条轨道的落点，同时也是样式文档里这条滚动条的坐标换算依据：
     * {@code left = 面板 left + (TRACK_X - 1)}、{@code bottom = 面板 bottom - TRACK_Y}。
     *
     * <p>位置本身不在这里设——滚动条是独立控件（不吃面板的坐标系），落点由 {@code advancedPatternModeScrollbar}
     * 在样式文档里给出。这两个常量现在只剩文档价值：挪面板时按上面的关系同步那两份样式文档。</p>
     */
    private static final int TRACK_X = 6;
    private static final int TRACK_Y = 6;
    private static final int TRACK_H = 54;

    /**
     * 七个选项，顺序与底图从左到右一致：相邻（不指定面）、北、南、西、东、顶、底。
     *
     * <p>「相邻」是 AE2 自己的默认口径（{@code PatternProviderTarget} 按邻居对着我的那一面接），其余六个
     * 就是 {@link Direction} 的六个面。</p>
     */
    private static final String[] OPTION_NAMES = { "any", "north", "south", "west", "east", "up", "down" };

    /**
     * 七个方向 &times; 选中与否 的按钮图，7&times;2 张，[列][选中?1:0]。建一次用一辈子。
     *
     * <p>它们只是**模板**：{@code Blitter.dest()} 是原地修改，直接拿模板去定位会把它改坏——绘制时必须
     * {@code .copy()}。原先每帧每按钮现建一个（每帧 21 次字符串拼接 + {@code ResourceLocation.parse} +
     * 构造），换到这里之后每帧只剩一次 copy。</p>
     *
     * <p><b>参考尺寸必须写成 16×16</b>：Blitter 默认按 256×256 把 src 矩形换算成归一化 UV，对这张小图
     * 会把 (0,0,10,14) 读成「256 分之 10」，只采到一个亚像素块再放大成糊。复制新贴图前先看真实像素尺寸。</p>
     * <p><b>模板别设 {@code zOffset}/{@code transform}/{@code blending}</b>：{@code copy()} 只带走
     * texture / 参考尺寸 / srcRect / destRect / 颜色，这三项不在其中——在模板上设会给副本静默丢掉。</p>
     */
    private static final Blitter[][] ADJ_BUTTON_ICONS = new Blitter[OPTION_NAMES.length][2];

    static {
        for (int column = 0; column < OPTION_NAMES.length; column++) {
            for (int selected = 0; selected < 2; selected++) {
                ADJ_BUTTON_ICONS[column][selected] = Blitter
                        .texture(ResourceLocation.parse(
                                "ae2_pattern_disk:textures/guis/adv_button/" + OPTION_NAMES[column] + "_button"
                                        + (selected == 1 ? "_selected" : "") + ".png"),
                                16, 16)
                        // 只取左上那 10×14 的内容：文件是 16×16 画布，余下部分是透明的。
                        .src(0, 0, ICON_W, ICON_H);
            }
        }
    }
    private static final int[] OPTION_SIDES = {
            -1,
            Direction.NORTH.ordinal(),
            Direction.SOUTH.ordinal(),
            Direction.WEST.ordinal(),
            Direction.EAST.ordinal(),
            Direction.UP.ordinal(),
            Direction.DOWN.ordinal(),
    };

    /** 第几行正在滚动到窗口顶部。 */
    private int scroll;

    /** 与处理/切石两档同款的滚动条控件；底图左侧那条轨道就是给它留的。样式文档没这一格时为 null。 */
    @Nullable
    private final Scrollbar scrollbar;

    public AdvancedEncodingPanel(PatternDiskEncodingTermScreen screen, WidgetContainer widgets) {
        super(screen, widgets);
        // 同 advancedPanel：ExtendedAE Plus 的上传子屏借用了它自己的样式文档，那份里没有这一格，
        // 而 AE2 对缺失的键是开界面那一刻抛异常。挂不上就退化成「不可滚动」，不影响其余部分。
        Scrollbar bar = null;
        try {
            bar = widgets.addScrollBar("advancedPatternModeScrollbar", Scrollbar.SMALL);
            bar.setHeight(TRACK_H);
            bar.setCaptureMouseWheel(false);
        } catch (IllegalStateException e) {
            AELog.debug("Screen style has no 'advancedPatternModeScrollbar' widget; the advanced panel stays unscrollable");
        }
        this.scrollbar = bar;
    }

    private int rowCount() {
        return menu.getSlots(SlotSemantics.PROCESSING_INPUTS).size();
    }

    private int maxScroll() {
        return Math.max(0, rowCount() - ROWS);
    }

    @Override
    public void updateBeforeRender() {
        if (this.scrollbar == null) {
            // 借外部样式文档、拿不到滚动条的宿主（EAE+ 的上传子屏）里，退回面板自己的滚轮滚动。
            scroll = Mth.clamp(scroll, 0, maxScroll());
            return;
        }
        // 下限给 1：maxScroll == 0 时 AE2 会把滚动条画成「禁用」外观，看上去像压根没有这条控件。
        this.scrollbar.setRange(0, Math.max(1, rowCount() - ROWS), ROWS);
        // 位置不在这里设：样式文档的 advancedPatternModeScrollbar 给的就是它。两份终端样式各自的
        // advancedPanel 与这条滚动条必须同源（滚动条 = 面板位置 + (TRACK_X - 1, TRACK_Y)，即
        // left = 面板 left + 5、bottom = 面板 bottom - 6）；面板挪了这里要跟着改。
        // 以前这行是 setPosition(x + TRACK_X - 1, y + TRACK_Y)，会把 JSON 的值盖掉——于是管理终端那份
        // JSON 里的坐标写错了也看不出来。
        scroll = Mth.clamp(this.scrollbar.getCurrentScroll(), 0, maxScroll());
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        // 滚动条是屏幕级 widget（构造器里的 widgets.addScrollBar），而屏幕切模式时只对本面板调
        // setVisible——滚动条不在面板里，不会跟着藏起来，于是切回别的模式后它还留在那儿。这里手动同步。
        // 判空是必须的：屏幕在构造完 widget 就会设一次可见性，那一刻 scrollbar 可能还没赋值。
        if (this.scrollbar != null) {
            this.scrollbar.setVisible(visible);
        }
    }

    @Override
    public void drawBackgroundLayer(GuiGraphics guiGraphics, Rect2i bounds, Point mouse) {
        var originX = bounds.getX() + x;
        var originY = bounds.getY() + y;
        BG.dest(originX, originY).blit(guiGraphics);

        var inputs = menu.getSlots(SlotSemantics.PROCESSING_INPUTS);
        for (int row = 0; row < ROWS; row++) {
            var index = row + scroll;
            if (index >= inputs.size()) {
                break;
            }

            // 原料格：物品自己画（底图上那三格只是边框）。
            var stack = inputs.get(index).getItem();
            if (!stack.isEmpty()) {
                var slotX = originX + SLOT_X + 1;
                var slotY = originY + SLOT_Y + row * SLOT_SIZE + 1;
                guiGraphics.renderItem(stack, slotX, slotY);
                guiGraphics.renderItemDecorations(Minecraft.getInstance().font, stack, slotX, slotY);
            }

            // 方向按钮：亮出这张输入当前分配的面。
            var current = menu.advancedSideAt(index);
            for (int column = 0; column < COLUMNS; column++) {
                var selected = OPTION_SIDES[column] == current;
                // 模板拿方向与选中两维的图；copy 才能 dest（dest 是原地改）。
                var icon = ADJ_BUTTON_ICONS[column][selected ? 1 : 0]
                        .copy()
                        .dest(originX + BUTTONS_X + column * BUTTON_W, originY + BUTTONS_Y + row * BUTTON_H,
                                ICON_W, ICON_H);
                icon.blit(guiGraphics);
            }
        }
    }

    @Override
    public boolean onMouseDown(Point mousePos, int button) {
        if (button != 0) {
            return false;
        }
        var localX = mousePos.getX() - x;
        var localY = mousePos.getY() - y;
        var column = (localX - BUTTONS_X) / BUTTON_W;
        var row = (localY - BUTTONS_Y) / BUTTON_H;
        // localY 也要查：Java 的整除向零截断，localY=0..7 时 (localY-8)/18 得 0 而不是 -1，少了这一条，
        // 面板顶部那条空带会被当成第 0 行，点一下就把方向改掉了。
        if (localX < BUTTONS_X || localY < BUTTONS_Y || column < 0 || column >= COLUMNS || row < 0
                || row >= ROWS) {
            return false;
        }
        var index = row + scroll;
        if (index >= rowCount()) {
            return false;
        }
        menu.setAdvancedSide(index, OPTION_SIDES[column]);
        return true;
    }

    @Override
    public boolean onMouseWheel(Point mousePos, double delta) {
        if (this.scrollbar == null) {
            // 没滚动条时自己滚（否则面板只能看前 3 行，第 4 行起的输入再也分配不了面）。
            var next = Mth.clamp(scroll + (delta > 0 ? 1 : -1), 0, maxScroll());
            if (next == scroll) {
                return false;
            }
            scroll = next;
            return true;
        }
        // 有滚动条时交给它算：拖拽与滚轮共用同一个 currentScroll，比另存一份 scroll 更不容易错位。
        return this.scrollbar.onMouseWheel(mousePos, delta);
    }

    @Override
    Icon getIcon() {
        return Icon.TAB_PROCESSING;
    }

    @Override
    public Component getTabTooltip() {
        return Component.translatable("gui.ae2_pattern_disk.encoding_terminal.advanced_mode");
    }
}
