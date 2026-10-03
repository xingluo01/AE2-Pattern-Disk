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
 * <p>展开的列表不注册成 widget，就画在自己的 {@link #renderWidget} 里。命中则**不走 children 广播**：
 * 工具栏其它按钮与样式面板排在更前面，会把落在它们地盘的点击先吃掉，列表底下那几项就永远点不中。
 * 所以由屏幕在派发之前调一次 {@link #handleMenuClick}，把这一下先抢过来；屏幕也会在同一个位置调
 * {@link #closeMenu} 补上「点到别的控件上也要收起」。</p>
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
    /**
     * 底板与内容之间的内缩。
     *
     * <p>取值与 AE2WTLib 一致（3）。它的 {@code BackgroundGenerator} 边框固定 4px，所以 3 会每边压掉
     * 1px（四个角最明显）——为了与那枚钮的观感对齐，这里接受这点代价，不取零风险的 4。</p>
     */
    private static final int PADDING = 3;
    /** 列表与按钮本体之间的空隙。 */
    private static final int PANEL_GAP = 5;
    /** 图标在底板里的内缩（底板 18 宽包着 16 宽的图标）。 */
    private static final int ICON_OFFSET = 1;
    /** 一列最多几项，超出就另起一列（与 AE2WTLib 同值）。 */
    private static final int MAX_ROWS = 3;

    private final Supplier<List<Choice>> choices;

    private boolean menuOpen;

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
        if (!choices().isEmpty()) {
            this.menuOpen = !this.menuOpen;
        }
    }

    /** 列表开着的时候点击「穿过」按钮之外的地方也算数，所以只要开着就消费掉，交给 {@link #mouseClicked} 判。 */
    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);

        if (this.menuOpen && this.visible) {
            renderMenu(guiGraphics, mouseX, mouseY);
        }
    }

    private void renderMenu(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        var entries = choices();
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

            // 悬停时整体下移 1px（与 AE2WTLib 一致）：悬停底板比常态矮 1px（18×19 vs 18×20），配上这个
            // 位移看上去就是「按下去」；图标跟着下移同样的量，免得在底板里跳。
            var yOffset = hovered ? 1 : 0;
            var bg = hovered ? ITEM_BG_HOVERED : entry.selected() ? ITEM_BG_SELECTED : ITEM_BG;
            bg.dest(itemX, itemY + yOffset).zOffset(2).blit(guiGraphics);
            entry.icon().dest(itemX + ICON_OFFSET, itemY + ICON_OFFSET + yOffset).zOffset(3).blit(guiGraphics);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (this.menuOpen) {
            // 点到别处：收起，但不消费这次点击（让它在别的地方照常生效）。正常路径下屏幕已经先问过
            // handleMenuClick，这里只是按钮边界内那一下的兜底。
            this.menuOpen = false;
        }
        return false;
    }

    /**
     * 由屏幕在派发点击之前调用：这一下是不是点在展开的列表里；是则执行那一项并消费掉这次点击。
     *
     * <p>命中不能只靠 {@link #mouseClicked}——工具栏其它按钮与样式面板都排在 children 前面，先被派发
     * 就会把落在它们地盘的点击吃掉，列表底下那几项永远收不到。屏幕在 {@code super.mouseClicked} 之前
     * 问一次，就把这条依赖彻底去掉了（列表即使压在别的按钮上也点得中）。</p>
     */
    public boolean handleMenuClick(double mouseX, double mouseY) {
        if (!this.menuOpen) {
            return false;
        }
        var index = itemAt(mouseX, mouseY);
        if (index < 0) {
            return false;
        }
        choices().get(index).onPick().run();
        this.menuOpen = false;
        return true;
    }

    /** 展开状态下把列表收起来。屏幕侧的 mouseClicked 会调它，因为点到别的控件上时本按钮收不到点击。 */
    public void closeMenu() {
        this.menuOpen = false;
    }

    /**
     * 展开列表的占地（屏幕坐标）。给「点到别处就收起」用。
     *
     * <p>不能拿 {@link #getTooltipArea()} 代替：列表比按钮宽，那个包络会把中间的背景也算进去，
     * 而提示区必须停在按钮本体。
     */
    public net.minecraft.client.renderer.Rect2i getPanelArea() {
        return new net.minecraft.client.renderer.Rect2i(panelX() - 1, panelY() - 1,
                panelWidth() + 2, panelHeight() + 2);
    }

    // ---- 几何 ----------------------------------------------------------------

    private List<Choice> choices() {
        return this.choices.get();
    }

    private int itemCount() {
        return choices().size();
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
        return PADDING * 2 + columns * ITEM_W + Math.max(0, columns - 1) * GAP;
    }

    /** 多出的 2 是照攄 AE2WTLib 的取值：上下内边距不对称（上 PADDING、下 PADDING+2）。 */
    private int panelHeight() {
        var rows = rows();
        return PADDING * 2 + 2 + rows * ITEM_H + Math.max(0, rows - 1) * GAP;
    }

    /**
     * 面板开在按钮**左侧**、与按钮**顶对齐**——AE2WTLib 的终端选择钮就是这个相对关系
     * （{@code panelX = getX() - PANEL_GAP - panelWidth()}、{@code panelY = getY()}），照着摆观感才一致。
     *
     * <p>左侧放不下时（工具栏贴屏幕左缘、窗口又窄）翻到按钮右侧。命中由屏幕的
     * {@link #handleMenuClick} 先抢，压在谁身上都点得中，所以这里不必躲别的控件。</p>
     */
    private int panelX() {
        var left = getX() - PANEL_GAP - panelWidth();
        return left >= 0 ? left : getX() + getWidth() + PANEL_GAP;
    }

    /** 与按钮顶对齐（照 AE2WTLib）；窗口太矮时上移，保证整块落在窗口内。 */
    private int panelY() {
        var guiHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        return Math.max(0, Math.min(getY(), guiHeight - panelHeight()));
    }

    private int itemX(int index) {
        return panelX() + PADDING + (index / rows()) * (ITEM_W + GAP);
    }

    private int itemY(int index) {
        return panelY() + PADDING + (index % rows()) * (ITEM_H + GAP) + lastColumnYOffset(index);
    }

    /**
     * 最后一列没摆满时，把这一列整体垂直居中（照 AE2WTLib）。
     *
     * <p>列优先摆放意味着未满的那几项全落在最右列，不居中就会顶在上边、与其它列错不开。</p>
     */
    private int lastColumnYOffset(int index) {
        var entries = itemCount() % rows();
        if (entries == 0 || index / rows() != columns() - 1) {
            return 0;
        }
        return (rows() - entries) * (ITEM_H + GAP) / 2;
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
