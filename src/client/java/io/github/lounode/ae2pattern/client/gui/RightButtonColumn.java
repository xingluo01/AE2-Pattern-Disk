package io.github.lounode.ae2pattern.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.renderer.Rect2i;

import appeng.client.Point;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.ICompositeWidget;

/**
 * 界面右缘外侧竖着排的一列按钮，AE2 的 {@code VerticalButtonBar} 的右手版。
 *
 * <p>
 * 存在的理由不是「好看」，是位置、可见区域和 JEI 排除区只有一份来源。右侧的上传按钮不只有我们创建的那个：
 * NEO ECO 的按钮由本模组建，ExtendedAE Plus 的按钮由它自己的 mixin 建、只回来问一次坐标
 * （{@code IPatternUploadTerminal#getUploadAnchor()}）。两边各算一遍几何，早晚会错开一两像素；错开的那一方就
 * 会落在 JEI 的物品面板底下——那也是本列自动 {@code addExclusionZones} 要解决的问题。
 * </p>
 *
 * <p>
 * 几何全部按 <b>GUI 自身的尺寸</b> 算（{@link #populateScreen} 拿到的 {@code bounds} 就是绝对 GUI 矩形）：
 * 横向贴右缘（{@code imageWidth}），纵向从「底边往上 {@code bottomOffset}」开始往下堆。**不用样式文档里的
 * left/top**——它是占位值，坐标在这里统一算，免得每份布局文档各写一遍尺寸。
 * </p>
 *
 * <p>
 * 槽有两种：真实控件（本列负责摆放并加进屏幕）与只占位（第三方自己创建，本列只提供
 * {@link #anchorOf(int)} 给的锚点）。占位槽的尺寸由调用方声明，锚点才算得出来。
 * </p>
 */
public final class RightButtonColumn implements ICompositeWidget {

    /** 槽与槽之间不留缝（上传按钮那两个就是上下紧贴的）。 */
    private static final int SPACING = 0;

    private static final class Slot {
        @Nullable
        final AbstractWidget widget;
        final int width;
        final int height;

        Slot(@Nullable AbstractWidget widget, int width, int height) {
            this.widget = widget;
            this.width = width;
            this.height = height;
        }
    }

    private final List<Slot> slots = new ArrayList<>();

    /** 屏幕左上角的窗口坐标，{@link #populateScreen} 时记下。 */
    private Point screenOrigin = Point.ZERO;
    private int guiWidth;
    private int guiHeight;
    /** 第一个槽到 GUI 底边的距离：按钮列挂在界面右侧偏下的位置。 */
    private int bottomOffset;

    /** 首个槽距 GUI 底边多远（像素）。必须在 {@link #populateScreen} 之前设置。 */
    public void setBottomOffset(int bottomOffset) {
        this.bottomOffset = bottomOffset;
    }

    /** 加一个由本列摆放的控件。 */
    public void add(AbstractWidget widget, int width, int height) {
        this.slots.add(new Slot(widget, width, height));
    }

    /**
     * 预留一个槽，控件由别人创建：本列只保证 {@link #anchorOf(int)} 给出的坐标与其它槽同源。
     */
    public void reserve(int width, int height) {
        this.slots.add(new Slot(null, width, height));
    }

    /**
     * 第 {@code index} 个槽的矩形（窗口坐标）。没有这个槽时返回 0 尺寸的矩形，调用方应先查
     * {@link #slotCount()}。
     */
    public Rect2i anchorOf(int index) {
        if (index < 0 || index >= this.slots.size()) {
            return new Rect2i(0, 0, 0, 0);
        }
        var slot = this.slots.get(index);
        int y = this.guiHeight - this.bottomOffset;
        for (int i = 0; i < index; i++) {
            y += this.slots.get(i).height + SPACING;
        }
        return new Rect2i(this.screenOrigin.getX() + this.guiWidth, this.screenOrigin.getY() + y,
                slot.width, slot.height);
    }

    /**
     * 把控件绑到一个已预留的槽。槽不存在或已被占用时返回 false（调用方不应再把它加进屏幕）。
     *
     * <p>槽必须**在构造期**就登记好：ExtendedAE Plus 在 {@code AEBaseScreen.init} 的 TAIL 就来要锚点，
     * 那时本列的 {@code populateScreen} 已跑过、但屏幕的 {@code init()} 体还没执行，现填槽就晚了。</p>
     */
    public boolean attach(int index, AbstractWidget widget) {
        if (index < 0 || index >= this.slots.size()) {
            return false;
        }
        var slot = this.slots.get(index);
        if (slot.widget != null) {
            return false;
        }
        this.slots.set(index, new Slot(widget, slot.width, slot.height));
        return true;
    }

    /** 该槽是否已绑上真实控件。 */
    public boolean hasWidget(int index) {
        return index >= 0 && index < this.slots.size() && this.slots.get(index).widget != null;
    }

    /** 槽数量（诊断/调试用；不要拿它当「按钮是否存在」的判据，那个用 {@link #hasWidget(int)}）。 */
    public int slotCount() {
        return this.slots.size();
    }

    @Override
    public boolean isVisible() {
        return !this.slots.isEmpty();
    }

    /**
     * 样式文档给的 left/top 只是为了让 {@code WidgetContainer} 收下这个复合控件，坐标在
     * {@link #populateScreen} 里按 GUI 尺寸算。
     */
    @Override
    public void setPosition(Point position) {
        // 有意忽略：位置来自 guiWidth / guiHeight，两份布局文档不必各写一遍尺寸。
    }

    @Override
    public void setSize(int width, int height) {
        // 尺寸也由槽自己报，不收样式文档的值。
    }

    @Override
    public Rect2i getBounds() {
        return this.bounds;
    }

    private Rect2i bounds = new Rect2i(0, 0, 0, 0);

    @Override
    public void populateScreen(Consumer<AbstractWidget> addWidget, Rect2i bounds, AEBaseScreen<?> screen) {
        this.screenOrigin = Point.fromTopLeft(bounds);
        this.guiWidth = bounds.getWidth();
        this.guiHeight = bounds.getHeight();
        for (var slot : this.slots) {
            if (slot.widget == null) {
                continue;
            }
            if (slot.widget.isFocused()) {
                slot.widget.setFocused(false);
            }
            addWidget.accept(slot.widget);
        }
        // 立刻算一次几何：此时锚点已经算得出来（槽在构造期就登记了）。
        this.rebuild();
    }

    @Override
    public void updateBeforeRender() {
        // 每帧重算：控件的可见性、槽的增减都可能在运行期变。
        this.rebuild();
    }

    private void rebuild() {
        int y = this.guiHeight - this.bottomOffset;
        int startY = y;
        int maxWidth = 0;
        for (var slot : this.slots) {
            if (slot.widget != null) {
                slot.widget.setX(this.screenOrigin.getX() + this.guiWidth);
                slot.widget.setY(this.screenOrigin.getY() + y);
            }
            y += slot.height + SPACING;
            maxWidth = Math.max(maxWidth, slot.width);
        }
        if (maxWidth == 0) {
            this.bounds = new Rect2i(0, 0, 0, 0);
            return;
        }
        // 向左多让 1px：两个上传按钮的实际左沿都在锚点左边（neoecoae 的 UploadButton 画在 x-1）。
        this.bounds = new Rect2i(this.guiWidth - 1, startY, maxWidth + 1, y - startY);
    }
}
