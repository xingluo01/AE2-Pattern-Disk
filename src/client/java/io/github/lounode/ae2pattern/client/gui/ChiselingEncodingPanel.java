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
import net.minecraft.world.item.ItemStack;

import appeng.client.Point;
import appeng.client.gui.Icon;
import appeng.client.gui.Tooltip;
import appeng.client.gui.WidgetContainer;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.widgets.Scrollbar;
import appeng.core.AELog;
import appeng.menu.SlotSemantics;

import io.github.lounode.ae2pattern.integration.rechiseledae.ChiselingRecipes;

/**
 * 雕凿编码面板：外观与几何整体继承切石那一档（同一张覆盖层底图与同一套条目格），只是每格画的东西不同——
 * 切石画配方产物，这里画雕凿候选项的产物。
 *
 * <p>候选来自 Rechiseled 自己的配方表（{@link ChiselingRecipes}），不进网络同步：客户端与服务端各自持有一份
 * 同源的副本（Rechiseled 在玩家加入时全量下发），所以这里只按序号读，与菜单里那个 {@code selectedChiseling}
 * 序号对齐。</p>
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
     * 候选缓存。配方表是几百项，而绘制、命中、滚动范围每帧都要读它，所以不能每帧现枚举。
     *
     * <p>失效时机取「每次进入本档」：配方表在开屏期间不会变（Rechiseled 只在玩家加入时同步一次），
     * 进档时刷一次就够。</p>
     */
    @Nullable
    private List<ChiselingRecipes.Candidate> cachedCandidates;

    /** 上一次的可见性：用来识别「刚进本档」这个跃迁（见 {@link #setVisible}）。 */
    private boolean lastVisible;

    public ChiselingEncodingPanel(PatternDiskEncodingTermScreen screen, WidgetContainer widgets) {
        super(screen, widgets);
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

    private List<ChiselingRecipes.Candidate> candidates() {
        if (this.cachedCandidates == null) {
            this.cachedCandidates = ChiselingRecipes.clientCandidates();
        }
        return this.cachedCandidates;
    }

    private int maxScroll() {
        return Math.max(0, (candidates().size() + COLS - 1) / COLS - ROWS);
    }

    @Override
    public void updateBeforeRender() {
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
        var lines = screen.getTooltipFromContainerItem(new ItemStack(candidate.output()));
        // 一格上只画得出产物，输入是看不见的——不写一行的话玩家没法知道这个候选「把什么雕成什么」。
        lines.add(Component.translatable(
                "gui.ae2_pattern_disk.encoding_terminal.chiseling_from",
                new ItemStack(candidate.input()).getHoverName()));
        return new Tooltip(lines);
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
            // 进档时刷一次候选：配方表不会在开屏期间变，但不能跨开屏复用（另一个终端里刚换过数据包）。
            // 只在 false→true 的跃迁上清：屏幕是每帧调 setVisible 的（AEBaseScreen.render），无条件清就
            // 等于每帧重枚举一遍配方表。
            this.cachedCandidates = null;
        }
        this.lastVisible = visible;
        // 滚动条是屏幕级 widget，不在面板里——屏幕只对本面板调 setVisible，不同步它就藏不掉。
        if (this.scrollbar != null) {
            this.scrollbar.setVisible(visible);
        }
        screen.setSlotsHidden(SlotSemantics.STONECUTTING_INPUT, !visible);
    }
}
