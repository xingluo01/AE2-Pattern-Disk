package io.github.lounode.ae2pattern.client.gui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import guideme.PageAnchor;

import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.AETextField;
import appeng.client.gui.widgets.Scrollbar;

import io.github.lounode.ae2pattern.common.logic.MarkedPatternDiskProviderLogic;
import io.github.lounode.ae2pattern.client.integration.JechPinyin;
import io.github.lounode.ae2pattern.common.menu.ClientActionPayload;
import io.github.lounode.ae2pattern.common.menu.MarkedPatternDiskProviderMenu;

/**
 * 标记式样板磁盘供应器的屏幕：在普通供应器那套左侧工具栏之上，加一块「标记候选网格」。
 *
 * <p>候选区是 7×3 的按钮矩阵（每个 16×18，紧密排列），格子间没有间隙；超出的部分靠右侧滚动条换页。
 * 每个格子画该标记对应的催化剂物品，多个催化剂按固定节拍轮换。搜索栏默认不聚焦，右键清空。</p>
 *
 * <p>布局常量来自美术稿，坐标都是 GUI 内相对坐标；实际渲染时再加上 {@code leftPos/topPos}。</p>
 */
public class MarkedPatternDiskProviderScreen extends PatternDiskProviderScreen<MarkedPatternDiskProviderMenu> {

    /** 候选格子：7 列 × 3 行，16×18 紧密排列。 */
    private static final int GRID_COLUMNS = 7;
    private static final int GRID_ROWS = 3;
    private static final int GRID_LEFT = 15;
    private static final int GRID_TOP = 52;
    private static final int VISIBLE_CELLS = GRID_COLUMNS * GRID_ROWS;

    /**
     * 显示模式与清空两枚按钮的图标取自**按钮精灵图** states.png（0/32,112 那两格，每格左右各一个状态），
     * 不是 GUI 背景图：背景图里那两处只是留白的占位。
     */
    private static final ResourceLocation STATES_TEXTURE =
            ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png");

    /** 显示模式：左半「显示全部标记」、右半「显示选中标记」。 */
    private static final Blitter SHOW_ALL_ICON = Blitter.texture(STATES_TEXTURE).src(0, 112, 16, 16);
    private static final Blitter SHOW_SELECTED_ICON = Blitter.texture(STATES_TEXTURE).src(16, 112, 16, 16);
    /** 清空：左半常态、右半确认态。 */
    private static final Blitter CLEAR_IDLE_ICON = Blitter.texture(STATES_TEXTURE).src(32, 112, 16, 16);
    private static final Blitter CLEAR_CONFIRM_ICON = Blitter.texture(STATES_TEXTURE).src(48, 112, 16, 16);

    /** 工具栏按钮背景，与供应器其余按钮同一套 18×20 底图。 */
    private static final Blitter BUTTON_BG_NORMAL = Blitter
            .texture(STATES_TEXTURE)
            .src(208, 224, 18, 20);
    private static final Blitter BUTTON_BG_HOVER = Blitter
            .texture(STATES_TEXTURE)
            .src(226, 224, 18, 20);

    private final AETextField markSearch;
    private final Scrollbar markScrollbar;
    private final StatesIconButton showModeButton;
    private final StatesIconButton clearMarkButton;
    private final List<MarkCandidateCell> markCells = new ArrayList<>();

    private boolean showSelectedOnly;
    private boolean clearConfirm;
    private int scrollOffset;
    private List<String> filteredMarks = List.of();
    private String lastSignature = "";

    public MarkedPatternDiskProviderScreen(MarkedPatternDiskProviderMenu menu, Inventory playerInventory,
            Component title) {
        this(menu, playerInventory, title,
                StyleManager.loadStyleDoc("/screens/ae2_pattern_disk/marked_pattern_disk_provider.json"));
    }

    public MarkedPatternDiskProviderScreen(MarkedPatternDiskProviderMenu menu, Inventory playerInventory,
            Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style, false);

        this.markSearch = this.widgets.addTextField("markSearch");
        this.markSearch.setPlaceholder(Component.translatable("gui.ae2_pattern_disk.marked_provider.search"));
        this.markSearch.setResponder(text -> invalidateAndRefresh());
        this.markSearch.setFocused(false);

        this.markScrollbar = this.widgets.addScrollBar("markScrollbar", Scrollbar.SMALL);
        // 轨道高度就是候选区的高度：滑块才能从头跑到尾。
        this.markScrollbar.setHeight(GRID_ROWS * MarkCandidateCell.HEIGHT);
        this.markScrollbar.setRange(0, 0, 1);

        this.showModeButton = new StatesIconButton(
                () -> this.showSelectedOnly ? SHOW_SELECTED_ICON : SHOW_ALL_ICON,
                btn -> {
                    this.showSelectedOnly = !this.showSelectedOnly;
                    invalidateAndRefresh();
                });
        this.showModeButton.setBackground(BUTTON_BG_NORMAL, BUTTON_BG_HOVER);
        this.showModeButton.setTooltip(showModeTooltip());
        this.widgets.add("showMode", this.showModeButton);

        this.clearMarkButton = new StatesIconButton(
                () -> this.clearConfirm ? CLEAR_CONFIRM_ICON : CLEAR_IDLE_ICON,
                btn -> onClearPressed());
        this.clearMarkButton.setBackground(BUTTON_BG_NORMAL, BUTTON_BG_HOVER);
        this.clearMarkButton.setTooltip(clearTooltip());
        this.widgets.add("clearMark", this.clearMarkButton);

        for (int i = 0; i < VISIBLE_CELLS; i++) {
            this.markCells.add(new MarkCandidateCell(this::sendToggleMark));
        }
    }

    @Override
    protected void init() {
        super.init();
        for (var cell : this.markCells) {
            addRenderableWidget(cell);
        }
        this.markSearch.setFocused(false);
        // 资源重载（F3+T）后 EMI/JEI 的类别表可能已经换了一批，旧图标不能继续挂着。
        MarkCatalysts.clearCache();
        invalidateAndRefresh();
    }

    @Override
    protected PageAnchor getHelpTopic() {
        return new PageAnchor(
                ResourceLocation.parse("ae2_pattern_disk:items-blocks-machines/marked_pattern_disk_provider.md"),
                null);
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        invalidateAndRefresh();
        layoutCells();
        this.showModeButton.setTooltip(showModeTooltip());
        this.clearMarkButton.setTooltip(clearTooltip());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 搜索栏右键清空：需求里指定的唯一清空手势。
        if (button == 1 && this.markSearch.isMouseOver(mouseX, mouseY)) {
            this.markSearch.setValue("");
            return true;
        }
        // 确认态下点别处：退回常态，不执行清空。
        if (this.clearConfirm && !this.clearMarkButton.isMouseOver(mouseX, mouseY)) {
            this.clearConfirm = false;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void onClearPressed() {
        if (!this.clearConfirm) {
            this.clearConfirm = true;
            return;
        }
        this.clearConfirm = false;
        sendAction("clearSelectedMarks", null);
    }

    /**
     * 只在输入真的变了才重算候选：这个方法每帧都会被 {@link #updateBeforeRender()} 调到。
     */
    private void invalidateAndRefresh() {
        var menu = getMenu();
        var signature = menu.selectedMarks + '\u0002' + menu.availableMarks + '\u0002'
                + this.markSearch.getValue() + '\u0002' + this.showSelectedOnly;
        if (signature.equals(this.lastSignature)) {
            return;
        }
        this.lastSignature = signature;
        refreshMarks();
    }

    private void refreshMarks() {
        var menu = getMenu();
        var selected = selectedCanonicals();
        var query = this.markSearch.getValue().strip().toLowerCase(Locale.ROOT);
        var marks = new ArrayList<String>();
        for (var mark : menu.getAvailableMarks()) {
            if (this.showSelectedOnly
                    && !selected.contains(MarkedPatternDiskProviderLogic.canonicalMark(mark))) {
                continue;
            }
            if (!query.isEmpty() && !matchesQuery(mark, query)) {
                continue;
            }
            marks.add(mark);
        }
        this.filteredMarks = marks;
        syncScrollbar();
    }

    private void syncScrollbar() {
        int maxScroll = maxScroll();
        this.markScrollbar.setRange(0, maxScroll, 1);
        this.scrollOffset = this.markScrollbar.getCurrentScroll();
        if (this.scrollOffset > maxScroll) {
            this.scrollOffset = maxScroll;
            this.markScrollbar.setCurrentScroll(maxScroll);
        }
    }

    private int maxScroll() {
        return Math.max(0, this.filteredMarks.size() - VISIBLE_CELLS);
    }

    private void layoutCells() {
        var selected = selectedCanonicals();
        for (int i = 0; i < this.markCells.size(); i++) {
            var cell = this.markCells.get(i);
            int index = this.scrollOffset + i;
            if (index >= this.filteredMarks.size()) {
                cell.bind(null, selected);
                continue;
            }
            int column = i % GRID_COLUMNS;
            int row = i / GRID_COLUMNS;
            cell.setX(this.leftPos + GRID_LEFT + column * MarkCandidateCell.WIDTH);
            cell.setY(this.topPos + GRID_TOP + row * MarkCandidateCell.HEIGHT);
            cell.bind(this.filteredMarks.get(index), selected);
        }
    }

    /** 已选标记的归一集合：同一标记的不同写法在界面上算同一个。 */
    private Set<String> selectedCanonicals() {
        var out = new HashSet<String>();
        for (var mark : menu().getSelectedMarkList()) {
            var canonical = MarkedPatternDiskProviderLogic.canonicalMark(mark);
            if (!canonical.isEmpty()) {
                out.add(canonical);
            }
        }
        return out;
    }

    /**
     * 标记是否匹配搜索词。走 {@link JechPinyin}：装了 JECH 时中文标记能用拼音与首字母搜（敲 {@code fs}
     * 就能找到「#粉碎」），没装就退成小写子串。
     *
     * <p>先比标记原文，再比它的可读名——玩家搜的是他在格子上看见的那个名字。</p>
     */
    private static boolean matchesQuery(String mark, String query) {
        if (JechPinyin.contains(mark, query)) {
            return true;
        }
        var name = PatternDiskMarks.displayName(mark);
        return name != null && JechPinyin.contains(name.getString(), query);
    }

    private List<Component> showModeTooltip() {
        var lines = new ArrayList<Component>();
        lines.add(Component.translatable("gui.ae2_pattern_disk.marked_provider.show_mode"));
        lines.add(Component.translatable(this.showSelectedOnly
                ? "gui.ae2_pattern_disk.marked_provider.show_mode.selected"
                : "gui.ae2_pattern_disk.marked_provider.show_mode.all"));
        return lines;
    }

    private List<Component> clearTooltip() {
        var lines = new ArrayList<Component>();
        lines.add(Component.translatable(this.clearConfirm
                ? "gui.ae2_pattern_disk.marked_provider.clear.confirm"
                : "gui.ae2_pattern_disk.marked_provider.clear"));
        return lines;
    }

    /** 点一格候选：加入/移出选中集合由服务端判，这里只负责把标记安全地送过去。 */
    private void sendToggleMark(String mark) {
        // AE2 用 Gson 解动作参数，而 Gson 默认宽松：# 开头的内容会被当注释读成 null。
        sendAction("toggleMark", ClientActionPayload.json(mark));
    }

    private void sendAction(String name, @Nullable String data) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new appeng.core.network.serverbound.GuiActionPacket(this.menu.containerId, name, data));
    }

    private MarkedPatternDiskProviderMenu menu() {
        return getMenu();
    }

}
