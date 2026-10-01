package io.github.lounode.ae2pattern.client.gui;

import java.util.List;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import appeng.client.gui.style.BackgroundGenerator;
import appeng.client.gui.style.Blitter;

/**
 * 编码模式的下拉选择钮：点一下在按钮旁边展开一列档位图标，点其中一个直接切过去，点别处收起。
 * 交互与 AE2WTLib 的「切换终端」那枚按钮一致，底板也沿用 AE2 图集里的工具栏按钮那三张。
 *
 * <p>按钮本身不认识档位：可选列表由屏幕给（{@link Choice} 里带着图标、名字与点击动作），这样「哪些档
 * 当前可用」的判断留在屏幕那侧，与它给面板做可见性判断的地方挨着，不必在两个文件里各写一遍。</p>
 *
 * <p>展开的列表不注册成 widget，就画在自己的 {@link #renderWidget} 里，命中靠覆写 {@link #mouseClicked}：
 * {@code Screen} 会把点击广播给所有 children，所以按钮边界之外的点击也能收到。但反过来，**排在更前面的
 * widget（工具栏上其它按钮、样式面板）会先吃掉点击，本按钮就收不到**——所以列表要开在没有别的东西的位置
 * （按钮下方），并让屏幕在派发前调一次 {@link #closeMenu}，补上「点到别的控件上也要收起」这一半。</p>
 */
public final class ModeDropdownButton extends StatesIconButton {

    /**
     * 一个可选档位。
     *
     * @param icon     该档的图标（states.png 里那一格）
     * @param name     悬停时显示的档位名
     * @param selected 是否是当前档（在列表里画成选中态）
     * @param onPick   点它时执行什么（切档）
     */
    public record Choice(Blitter icon, Component name, boolean selected, Runnable onPick) {
    }

    // AE2 states.png 里的工具栏按钮底板，与 AE2WTLib 那枚选择钮用的是同一组。
    private static final Blitter ITEM_BG = states(176, 128, 18, 20);
    private static final Blitter ITEM_BG_SELECTED = states(194, 128, 18, 20);
    private static final Blitter ITEM_BG_HOVERED = states(212, 128, 18, 19);

    /** 单项尺寸与间距，照抄 AE2WTLib 的常量，展开列表的观感才与那个按钮一致。 */
    private static final int ITEM_W = 18;
    private static final int ITEM_H = 20;
    private static final int GAP = 2;
    private static final int PADDING = 3;
    /** 列表与按钮本体之间的空隙。 */
    private static final int PANEL_GAP = 5;
    /** 图标在底板里的内缩（底板 18 宽包着 16 宽的图标）。 */
    private static final int ICON_OFFSET = 1;
    /** 一列最多几项，超出就另起一列（与 AE2WTLib 同值）。 */
    private static final int MAX_ROWS = 3;

    private final Supplier<List<Choice>> choices;

    private boolean menuOpen;

    /** 悬停到的那一项的名字，由 {@link #renderWidget} 每帧更新，供 tooltip 用。 */
    @Nullable
    private Component hoveredName;

    public ModeDropdownButton(BlitterProvider blitterProvider, Supplier<List<Choice>> choices) {
        super(blitterProvider, btn -> {
        });
        this.choices = choices;
    }

    /**
     * 按本体只翻转展开状态，不再轮换档位——轮换改由展开列表里直接点选。
     *
     * <p>覆写这个方法而不是给构造器传动作：{@code Button} 的鼠标处理调的是这个虚方法，所以动作字段
     * 留空也拦得住。构造器那个空 lambda 只是为了让基类拿到非 null 的字段。</p>
     */
    @Override
    public void onPress() {
        if (!this.choices.get().isEmpty()) {
            this.menuOpen = !this.menuOpen;
        }
    }

    /** 列表开着的时候点击「穿过」按钮之外的地方也算数，所以只要开着就消费掉，交给 {@link #mouseClicked} 判。 */
    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);

        this.hoveredName = null;
        if (this.menuOpen && this.visible) {
            renderMenu(guiGraphics, mouseX, mouseY);
        }
    }

    private void renderMenu(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        var entries = this.choices.get();
        if (entries.isEmpty()) {
            return;
        }

        var panelX = panelX();
        var panelY = panelY();
        BackgroundGenerator.draw(panelWidth(), panelHeight(), guiGraphics, panelX, panelY);

        for (int i = 0; i < entries.size(); ++i) {
            var entry = entries.get(i);
            var itemX = itemX(i);
            var itemY = itemY(i);
            var hovered = isInItem(mouseX, mouseY, i);

            // 悬停时不整体下移：这几张底板本来就差 1px 高，直接换图比位移稳。
            var bg = hovered ? ITEM_BG_HOVERED : entry.selected() ? ITEM_BG_SELECTED : ITEM_BG;
            bg.dest(itemX, itemY).zOffset(2).blit(guiGraphics);
            entry.icon().dest(itemX + ICON_OFFSET, itemY + ICON_OFFSET).zOffset(3).blit(guiGraphics);

            if (hovered) {
                this.hoveredName = entry.name();
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.menuOpen && button == 0) {
            var index = itemAt(mouseX, mouseY);
            if (index >= 0) {
                this.choices.get().get(index).onPick().run();
                this.menuOpen = false;
                return true;
            }
        }
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (this.menuOpen) {
            // 点到别处：收起，但不消费这次点击（让它在别的地方照常生效）。点到别的控件上时走不到这里
            // （那几个控件比本按钮先派发，那一路由屏幕侧的 closeMenu 兜）。
            this.menuOpen = false;
        }
        return false;
    }

    /** 展开时提示区要跟着外扩，否则列表上的 tooltip 会被判成「不在本按钮范围内」而不显示。 */
    @Override
    public net.minecraft.client.renderer.Rect2i getTooltipArea() {
        if (!this.menuOpen) {
            return super.getTooltipArea();
        }
        var panelX = panelX();
        var panelY = panelY();
        var left = Math.min(getX() - 1, panelX - 1);
        var top = Math.min(getY(), panelY - 1);
        var right = Math.max(getX() + getWidth() + 1, panelX + panelWidth() + 1);
        var bottom = Math.max(getY() + getHeight() + 1, panelY + panelHeight() + 1);
        return new net.minecraft.client.renderer.Rect2i(left, top, right - left, bottom - top);
    }

    @Override
    public List<Component> getTooltipMessage() {
        if (this.menuOpen && this.hoveredName != null) {
            return List.of(this.hoveredName);
        }
        return super.getTooltipMessage();
    }

    @Override
    public boolean isTooltipAreaVisible() {
        return super.isTooltipAreaVisible() || (this.menuOpen && this.hoveredName != null);
    }

    /** 展开状态下把列表收起来。屏幕侧的 mouseClicked 会调它，因为点到别的控件上时本按钮收不到点击。 */
    public void closeMenu() {
        this.menuOpen = false;
    }

    // ---- 几何 ----------------------------------------------------------------

    private int itemCount() {
        return this.choices.get().size();
    }

    /** 分列摆放（一列最多 {@link #MAX_ROWS} 项），列优先：6 个档位就是两列三行。 */
    private int rows() {
        return Math.max(1, Math.min(MAX_ROWS, itemCount()));
    }

    private int columns() {
        return Math.max(1, (itemCount() + rows() - 1) / rows());
    }

    private int panelWidth() {
        var columns = columns();
        return PADDING * 2 + columns * ITEM_W + (columns - 1) * GAP;
    }

    private int panelHeight() {
        var rows = rows();
        return PADDING * 2 + rows * ITEM_H + (rows - 1) * GAP;
    }

    /**
     * 面板开在按钮**下方**：模式钮是工具栏里的最后一个（屏幕用 {@code ToolbarOrder.placeAtEnd} 排的），
     * 下方没有别的按钮——而工具栏按钮比它先收到点击，开在上面会被那些按钮抢走命中。左边缘与按钮对齐，
     * 避免贴到 GUI 左边之外。
     */
    private int panelX() {
        return Math.max(0, getX());
    }

    private int panelY() {
        var below = getY() + getHeight() + PANEL_GAP;
        var guiHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        if (below + panelHeight() <= guiHeight) {
            return below;
        }
        // 窗口矮到下方放不下（无线屏里本钮未必排在末尾），翻到上方；再不够就贴顶——整条落在屏幕外就真的点不到了。
        return Math.max(0, getY() - panelHeight() - PANEL_GAP);
    }

    private int itemX(int index) {
        return panelX() + PADDING + (index / rows()) * (ITEM_W + GAP);
    }

    private int itemY(int index) {
        return panelY() + PADDING + (index % rows()) * (ITEM_H + GAP);
    }

    private boolean isInItem(int mouseX, int mouseY, int index) {
        var itemX = itemX(index);
        var itemY = itemY(index);
        return mouseX >= itemX && mouseX < itemX + ITEM_W && mouseY >= itemY && mouseY < itemY + ITEM_H;
    }

    private int itemAt(double mouseX, double mouseY) {
        var count = itemCount();
        for (int i = 0; i < count; ++i) {
            if (isInItem((int) mouseX, (int) mouseY, i)) {
                return i;
            }
        }
        return -1;
    }

    /** 本项目的图标都取自 states.png，这里只把 UV 包成 Blitter。 */
    private static Blitter states(int x, int y, int width, int height) {
        return Blitter.texture(ResourceLocation.parse("ae2:textures/guis/states.png")).src(x, y, width, height);
    }
}
