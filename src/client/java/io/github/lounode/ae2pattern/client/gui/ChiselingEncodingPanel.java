package io.github.lounode.ae2pattern.client.gui;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import appeng.client.Point;
import appeng.client.gui.Icon;
import appeng.client.gui.Tooltip;
import appeng.client.gui.WidgetContainer;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.widgets.Scrollbar;
import appeng.core.AELog;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.integration.rechiseledae.ChiselingRecipes;

/**
 * 雕凿编码面板：外观与几何整体继承切石那一档（同一张覆盖层底图与同一套条目格），只是每格画的东西不同——
 * 切石画配方产物，这里画雕凿候选项的产物。
 *
 * <p>候选来自 Rechiseled 自己的配方表（{@link ChiselingRecipes}），不进网络同步：客户端与服务端各自持有一份
 * 同源的副本（Rechiseled 在玩家加入时全量下发），所以这里只按序号读，与菜单里那个 {@code selectedChiseling}
 * 序号对齐。</p>
 *
 * <p>列出哪些候选**由输入槽里那个物品决定**：放普通台阶就只列普通台阶、放连接台阶就只列连接台阶。空槽时
 * 列表为空，玩家需要先往槽里放一个方块（JEI/EMI 拖进去——那个槽是伪槽，不接玩家的常规放置）。</p>
 */
public final class ChiselingEncodingPanel extends DiskEncodingModePanel {
    private static final Blitter BG = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/pattern_modes.png"))
            .src(121, 72, 115, 66);
    private static final Blitter BG_SLOT = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/pattern_modes.png"))
            .src(240, 72, 16, 18);
    private static final Blitter BG_SLOT_SELECTED = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/pattern_modes.png"))
            .src(240, 90, 16, 18);
    private static final Blitter BG_SLOT_HOVER = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/pattern_modes.png"))
            .src(240, 108, 16, 18);

    /** 与切石同一套几何（画板相对覆盖层 (157-121, 79-72)，4 列 3 行，条目 16×18）。 */
    private static final int CANVAS_OFFSET_X = 36;
    private static final int CANVAS_OFFSET_Y = 7;
    private static final int COLS = 4;
    private static final int ROWS = 3;
    private static final int SLOT_W = 16;
    private static final int SLOT_H = 18;
    private static final int ROW_SPACING = SLOT_H;

    /** 借外部样式文档、拿不到滚动条的宿主里为 null（与高级面板同款退化）。 */
    @Nullable
    private final Scrollbar scrollbar;

    /** 拿不到滚动条时自己滚。 */
    private int scroll;

    /**
     * 候选缓存。配方表按输入分组，同一输入下的那组也是几百项的量级，而绘制、命中、滚动范围每帧都要读它，
     * 所以不能每帧现算。
     *
     * <p>失效时机有两个：进入本档那次跃迁，以及输入槽里换了东西（{@link #cachedInput} 对不上就重算）。</p>
     */
    @Nullable
    private List<ChiselingRecipes.Candidate> cachedCandidates;

    /** 上一次算候选时用的输入物品；null 表示输入槽是空的（或还没算过）。 */
    @Nullable
    private Item cachedInput;

    /** 上一次的可见性：用来识别「刚进本档」这个跃迁（见 {@link #setVisible}）。 */
    private boolean lastVisible;

    /**
     * 上一次消费过的输入修订号：用来分辨「输入槽的变化是程序干的」与「玩家自己换了输入」。
     *
     * <p>构造时对齐当前值，不假设「新屏幕一定配新菜单」（与屏幕那侧看导入修订号同一个写法）。</p>
     */
    private int seenInputRevision;

    public ChiselingEncodingPanel(PatternDiskEncodingTermScreen screen, WidgetContainer widgets) {
        super(screen, widgets);
        this.seenInputRevision = menu.chiselingInputRevision;
        // 自己一条滚动条：切石那条不能借用（两条滚动条会同时可见，而且位置绑定在两个不同的面板上）。
        // 样式文档没这一格时退化成「不可滚动」，不影响其余部分（同高级面板）。
        Scrollbar bar = null;
        try {
            bar = widgets.addScrollBar("chiselingPatternModeScrollbar", Scrollbar.SMALL);
            bar.setCaptureMouseWheel(false);
        } catch (IllegalStateException e) {
            AELog.debug("Screen style has no 'chiselingPatternModeScrollbar' widget; the chiseling panel stays unscrollable");
        }
        this.scrollbar = bar;
    }

    /**
     * 槽里那个输入物品；空槽返回 null。物品身份只看 {@link Item}：雕凿配方的粒度就是物品，不看数据组件。
     * 客户端读得到这个槽（它就是编码输入库存的第一格，走原版槽同步）。
     */
    @Nullable
    private Item inputItem() {
        var stack = menu.getChiselingInputSlot().getItem();
        return stack.isEmpty() ? null : stack.getItem();
    }

    private List<ChiselingRecipes.Candidate> candidates() {
        if (this.cachedCandidates == null) {
            this.cachedInput = inputItem();
            this.cachedCandidates = ChiselingRecipes.clientCandidates(this.cachedInput);
        }
        return this.cachedCandidates;
    }

    private int maxScroll() {
        return Math.max(0, (candidates().size() + COLS - 1) / COLS - ROWS);
    }

    @Override
    public void updateBeforeRender() {
        // 输入与选中常常是程序一起设的（配方导入、放入雕凿样板切档），先取一次修订号把这一下认出来。
        var inputRevision = menu.chiselingInputRevision;
        var inputSetByProgram = inputRevision != this.seenInputRevision;
        this.seenInputRevision = inputRevision;

        // 输入槽里换了东西：候选换一批，顺带把上一批的选中项与滚动位作废（那个序号在新列表里对应别的物品，
        // 留着它落盘时会编出一枚跟屏幕对不上的样板）。
        //
        // **但程序设输入那一下要除外**：JEI/EMI 的 + 与「放入雕凿样板就切档」都是先把输入槽填上、
        // 再把选中序号设好，序号正是为这一批候选选的。不做这个区分的话，那两下之后下一帧就会把它清成 -1
        // ——面板上档位对、输入对，却没有选中项，点「编写样板」只能得到一句「需要先选候选」。
        var input = inputItem();
        if (this.cachedInput != input) {
            this.cachedInput = input;
            this.cachedCandidates = ChiselingRecipes.clientCandidates(input);
            if (menu.selectedChiseling != -1 && !inputSetByProgram) {
                menu.setChiseling(-1);
            }
            this.scroll = 0;
        }

        if (this.scrollbar == null) {
            scroll = net.minecraft.util.Mth.clamp(scroll, 0, maxScroll());
            return;
        }
        // 下限给 1：与另外三条一致——maxScroll == 0 时 AE2 会把滚动条画成「禁用」外观。
        this.scrollbar.setRange(0, Math.max(1, maxScroll()), ROWS);
        scroll = net.minecraft.util.Mth.clamp(this.scrollbar.getCurrentScroll(), 0, maxScroll());
    }

    @Override
    public void drawBackgroundLayer(GuiGraphics guiGraphics, Rect2i bounds, Point mouse) {
        BG.dest(bounds.getX() + x, bounds.getY() + y).blit(guiGraphics);

        var all = candidates();
        var startIndex = scroll * COLS;
        var endIndex = startIndex + ROWS * COLS;

        for (int i = startIndex; i < endIndex && i < all.size(); ++i) {
            var slotBounds = getSlotBounds(i - startIndex);

            Blitter blitter = BG_SLOT;
            if (i == menu.selectedChiseling) {
                blitter = BG_SLOT_SELECTED;
            } else if (mouse.isIn(slotBounds)) {
                blitter = BG_SLOT_HOVER;
            }

            var renderX = bounds.getX() + slotBounds.getX();
            var renderY = bounds.getY() + slotBounds.getY();
            blitter.dest(renderX, renderY).blit(guiGraphics);
            // 槽位 16×18、物品 16×16：水平对齐，垂直偏移 1px 让物品在槽位里居中（与切石一致）。
            ItemStack resultItem = new ItemStack(all.get(i).output());
            guiGraphics.renderItem(resultItem, renderX, renderY + 1);
            guiGraphics.renderItemDecorations(Minecraft.getInstance().font, resultItem, renderX, renderY + 1);
        }
    }

    @Override
    public boolean onMouseDown(Point mousePos, int button) {
        var index = getCandidateIndexAt(mousePos);
        if (index >= 0) {
            menu.setChiseling(index);
            Minecraft.getInstance().getSoundManager()
                    .play(SimpleSoundInstance.forUI(SoundEvents.UI_STONECUTTER_SELECT_RECIPE, 1.0F));
            return true;
        }
        return false;
    }

    @Nullable
    @Override
    public Tooltip getTooltip(int mouseX, int mouseY) {
        var index = getCandidateIndexAt(new Point(mouseX, mouseY));
        if (index < 0) {
            return null;
        }
        var candidate = candidates().get(index);
        return new Tooltip(screen.getTooltipFromContainerItem(new ItemStack(candidate.output())));
    }

    /** 命中的候选在整体列表里的下标；没命中返回 -1。 */
    private int getCandidateIndexAt(Point point) {
        var size = candidates().size();
        var startIndex = scroll * COLS;
        var endIndex = startIndex + COLS * ROWS;

        for (int i = startIndex; i < endIndex && i < size; ++i) {
            if (point.isIn(getSlotBounds(i - startIndex))) {
                return i;
            }
        }
        return -1;
    }

    /** 返回条目相对屏幕的边界（面板位置 + 画板偏移 + 行列），与切石同一算式。 */
    private Rect2i getSlotBounds(int index) {
        var col = index % COLS;
        var row = index / COLS;
        int slotX = x + CANVAS_OFFSET_X + col * SLOT_W;
        int slotY = y + CANVAS_OFFSET_Y + row * ROW_SPACING;
        return new Rect2i(slotX, slotY, SLOT_W, SLOT_H);
    }

    @Override
    public boolean onMouseWheel(Point mousePos, double delta) {
        if (this.scrollbar == null) {
            var next = net.minecraft.util.Mth.clamp(scroll + (delta > 0 ? 1 : -1), 0, maxScroll());
            if (next == scroll) {
                return false;
            }
            scroll = next;
            return true;
        }
        return this.scrollbar.onMouseWheel(mousePos, delta);
    }

    @Override
    Icon getIcon() {
        // 没有专门的雕凿 tab 图标；这一档的外观本来就继承切石，图标也一并沿用。
        return Icon.TAB_STONECUTTING;
    }

    @Override
    public Component getTabTooltip() {
        return Component.translatable("gui.ae2_pattern_disk.encoding_terminal.chiseling_mode");
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (visible && !this.lastVisible) {
            // 进档时重算候选：输入槽里可能停着上一次留下的东西，而缓存不跨开屏复用（另一个终端里刚换过
            // 数据包）。只在 false→true 的跃迁上清：屏幕是每帧调 setVisible 的（AEBaseScreen.render），
            // 无条件清就等于每帧重枚举一遍配方表。
            this.cachedCandidates = null;
            this.cachedInput = null;
        }
        this.lastVisible = visible;
        // 滚动条是屏幕级 widget，不在面板里——屏幕只对本面板调 setVisible，不同步它就藏不掉。
        if (this.scrollbar != null) {
            this.scrollbar.setVisible(visible);
        }
        // 这个槽是雕凿档自己的，只由本面板管，所以写对称的 !visible（切石那个槽归切石面板，不去碰）。
        screen.setSlotsHidden(AEPatternRegistries.CHISELING_INPUT, !visible);
    }
}
