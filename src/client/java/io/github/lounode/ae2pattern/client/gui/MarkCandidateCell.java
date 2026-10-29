package io.github.lounode.ae2pattern.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import appeng.client.gui.style.Blitter;
import appeng.client.gui.widgets.ITooltip;

import io.github.lounode.ae2pattern.common.logic.MarkedPatternDiskProviderLogic;

/**
 * 标记候选网格里的一格：三态底图 + 该标记对应的催化剂（或类别）图标。
 *
 * <p>格子之间没有间隙，所以命中区必须与底图完全重合；底图本身就是完整的 16×18 按钮，图标铺满 16 宽、
 * 纵向留 1px 顶边——它不是 18×18 的 ME 槽，不适用槽位那套「内部再缩 1px」的口径。</p>
 *
 * <p>三态取自设备自己的 GUI 精灵图（176,0 那一列）：常态、已选中、光标选中。选中态按归一值判定，
 * 所以 {@code #mode:crafting} 与 {@code #minecraft:crafting} 这两种写法会一起亮。</p>
 */
final class MarkCandidateCell extends Button implements ITooltip {

    /** 格子尺寸：与三态底图完全一致。 */
    static final int WIDTH = 16;
    static final int HEIGHT = 18;

    private static final ResourceLocation TEXTURE =
            ResourceLocation.parse("ae2_pattern_disk:textures/guis/marked_pattern_disk_provider.png");

    private static final Blitter STATE_NORMAL = Blitter.texture(TEXTURE).src(176, 0, WIDTH, HEIGHT);
    private static final Blitter STATE_SELECTED = Blitter.texture(TEXTURE).src(176, 18, WIDTH, HEIGHT);
    private static final Blitter STATE_HOVER = Blitter.texture(TEXTURE).src(176, 36, WIDTH, HEIGHT);

    /** 玩家点这一格时要做的事（交回屏幕去发动作，格子自己不懂网络）。 */
    @FunctionalInterface
    interface OnPick {
        void pick(String mark);
    }

    private final OnPick onPick;

    private String mark = "";
    private String canonicalMark = "";
    private MarkCatalysts.Icon icon = MarkCatalysts.EMPTY;
    private Set<String> selectedCanonicals = Set.of();

    MarkCandidateCell(OnPick onPick) {
        super(0, 0, WIDTH, HEIGHT, Component.empty(),
                button -> {
                    var cell = (MarkCandidateCell) button;
                    if (!cell.mark.isEmpty()) {
                        cell.onPick.pick(cell.mark);
                    }
                },
                Button.DEFAULT_NARRATION);
        this.onPick = onPick;
        this.visible = false;
        this.active = false;
    }

    /**
     * 每帧都会被调到，所以只有标记真的换了才重算图标：催化剂查询要走 EMI/JEI 的类别表，
     * 21 个格子每帧各查一次就是每秒上千次全表扫描。
     *
     * @param selectedCanonicals 当前所有已选标记的归一集合（多选）
     */
    void bind(@Nullable String mark, Set<String> selectedCanonicals) {
        var next = mark == null ? "" : mark;
        if (!next.equals(this.mark)) {
            this.mark = next;
            this.canonicalMark = MarkedPatternDiskProviderLogic.canonicalMark(next);
            this.icon = MarkCatalysts.iconFor(next);
        }
        this.selectedCanonicals = selectedCanonicals;
        this.visible = !this.mark.isEmpty();
        this.active = this.visible;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var blitter = isHovered() ? STATE_HOVER : isSelected() ? STATE_SELECTED : STATE_NORMAL;
        blitter.dest(getX(), getY()).zOffset(2).blit(graphics);

        // 图标可能是一组轮换的催化剂物品，也可能只是那个类别的贴图（MEK/ECO 那类只给了图标的模组）。
        this.icon.render(graphics, getX(), getY() + 1);
    }

    private boolean isSelected() {
        return this.selectedCanonicals.contains(this.canonicalMark);
    }

    @Override
    public Rect2i getTooltipArea() {
        return new Rect2i(getX(), getY(), this.width, this.height);
    }

    @Override
    public List<Component> getTooltipMessage() {
        var lines = new ArrayList<Component>();
        var name = PatternDiskMarks.displayName(this.mark);
        lines.add(name != null ? name : Component.literal(this.mark));
        return lines;
    }

    @Override
    public boolean isTooltipAreaVisible() {
        return this.visible && !this.mark.isEmpty();
    }
}
