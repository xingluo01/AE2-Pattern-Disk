package io.github.lounode.ae2pattern.client.gui;

import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;

import appeng.client.Point;
import appeng.client.gui.ICompositeWidget;
import appeng.client.gui.Icon;
import appeng.client.gui.WidgetContainer;

import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;

public abstract class DiskEncodingModePanel implements ICompositeWidget {
    protected final PatternDiskEncodingTermScreen screen;
    protected final PatternDiskEncodingTermMenu menu;
    protected final WidgetContainer widgets;
    protected boolean visible = false;
    protected int x;
    protected int y;

    /**
     * 面板尺寸：默认与各终端样式文档的 modePanel* 的 width/height 一致（115×66），文档给了非零值就以文档为准
     * （见 {@link #setSize}）。边界用于 JEI/EMI 的幽灵拖放与命中测试。
     */
    private int width = 115;
    private int height = 66;

    public DiskEncodingModePanel(PatternDiskEncodingTermScreen screen, WidgetContainer widgets) {
        this.screen = screen;
        this.menu = screen.getMenu();
        this.widgets = widgets;
    }

    abstract Icon getIcon();

    abstract Component getTabTooltip();

    @Override
    public void setPosition(Point position) {
        x = position.getX();
        y = position.getY();
    }

    @Override
    public void setSize(int width, int height) {
        // 样式文档没写 width/height 时 Gson 留 0，那会把边界缩没、拖放直接失效，所以只对正值生效。
        if (width > 0) {
            this.width = width;
        }
        if (height > 0) {
            this.height = height;
        }
    }

    @Override
    public Rect2i getBounds() {
        return new Rect2i(x, y, this.width, this.height);
    }

    @Override
    public final boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }
}