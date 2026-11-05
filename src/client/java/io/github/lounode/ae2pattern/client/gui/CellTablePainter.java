package io.github.lounode.ae2pattern.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import appeng.client.gui.style.Blitter;

import org.jetbrains.annotations.Nullable;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.client.render.DriveHighlight;
import io.github.lounode.ae2pattern.network.CellHostListPayload;

/**
 * 把元件管理终端表格的状态画成像素与提示：行带、槽底、占用条与描边，以及首格/元件格的悬停提示内容。
 *
 * <p>这是一块<b>无状态</b>的呈现逻辑：几何与九条切片在构造期作为不可变快照注入，每帧变化的表格状态
 * （行模型、滚动位置、选中键、字体、槽位清单）由屏幕打包成 {@link Frame} / {@link SlotFrame} 传进来。
 * 因此它既不持有屏幕、也不持有菜单，屏幕的 {@code drawBG} / {@code drawFG} 覆写只留一行转调；打断遮罩那层
 * 仍归屏幕（它要调基类的 {@code renderDisconnectedOverlay}）。</p>
 *
 * <p>位置解析、钉住状态、提示文本这些纯工具也放这里：它们只被表格的绘制与交互用到，跟着同一份
 * 「把表格说清楚」的职责走。</p>
 */
final class CellTablePainter {

    /** 构造期注入的表格几何快照：全部是开屏时算定、之后不变的量。 */
    record Geometry(int listX, int listY, int cellInset, int rowTextYInset,
            int headerHeight, int footerHeight, int rowHeight, int cellSize,
            int contentSize, int listWidth, int textColor) {
    }

    /** 样式文档里那九条切片中的八条（blankCell 只用来反推格距，不参与绘制）。 */
    record Bands(Blitter headerBand, Blitter footerBand,
            Blitter rowTextTop, Blitter rowSlotsTop,
            Blitter rowTextMiddle, Blitter rowSlotsMiddle,
            Blitter rowTextBottom, Blitter rowSlotsBottom) {
    }

    /** 每帧的表格状态快照：屏幕持有这些状态，画的时候一次性交给呈现层。 */
    record Frame(List<CellTableRows.Row> rows, int scrollOffset, int visibleRows,
            String selectedLeaderKey, Map<String, CellHostListPayload.HostGroup> driveLookup, Font font) {
    }

    /** 每帧的槽位覆盖层数据：真槽位的实坐标由屏幕按语义取好，连显隐判据一起打包进来。 */
    record SlotFrame(List<Slot> encodeSlots, List<Slot> upgradeSlots, List<Slot> markerSlots,
            boolean markerAreaEditable, int markerSlotCount, int markerRowOffset) {
    }

    /** 第一遍记下的占用条：物品批次刷出之后才按它补画，别在 {@code drawDriveRow} 里直接画。 */
    private record OccupancyBar(int x, int y, CellHostListPayload.CellSlot cell) {}

    /** 驱动器首格的淡绿底，与样板终端的「这一格是主机」同色。 */
    private static final int DRIVE_SLOT_TINT = 0x66B9F6CA;

    /** 右键选中那一格的描边：黄。左键钉住的那格用虹色，两个状态在表格里就能分开。 */
    private static final int DRIVE_SELECTED_TINT = 0xCCFFD54F;

    /**
     * 钉住那一格的描边色（半透明虹色）：直接取世界里那套颜色，两处同色同节奏。
     *
     * <p>只有左键钉住用它——右键选中另有黄色。同一台同时命中两个状态时表格只画黄的那圈（选中优先），
     * 但悬停提示里「已选中」「已钉住」两行都会在。</p>
     */
    private static int rainbowTint() {
        return DriveHighlight.rainbowArgb(0xCC);
    }

    /** 两根占用条的颜色：种类用蓝、数量用绿（与 AE2 存储元件的两类占用同源）。 */
    private static final int TYPE_BAR_COLOR = 0xFF3B6EDB;
    private static final int BYTE_BAR_COLOR = 0xFF3FBF4F;
    private static final int BAR_TRACK_COLOR = 0x80000000;

    /**
     * 五张覆盖层。它们来自本模组自己的 states.png（与 AE2 同款的几格，坐标已按本模组那份复制过来），
     * 与贴图上画好的槽框同位置：元件存储/编码槽、元件升级槽、元件标记槽各一张，加一张「放不进去的位置」，
     * 以及一张 ECO 家族的存储矩阵座。
     */
    private static final Blitter CELL_SLOT_OVERLAY = states(240, 80);
    private static final Blitter UPGRADE_SLOT_OVERLAY = states(240, 96);
    private static final Blitter MARKER_SLOT_OVERLAY = states(240, 112);
    /** 空白槽位覆盖层：盖在段尾那些放不进去的列上（这台/这个主机没有这么多格）。 */
    private static final Blitter EMPTY_SLOT_OVERLAY = states(240, 128);
    /** 存储矩阵座：表里 ECO 家族的空格用它，而不是 AE2 那只方槽。 */
    private static final Blitter MATRIX_SLOT_OVERLAY = states(240, 160);

    /**
     * 哪些模组的存储主机用矩阵座：ECO 本体与青春版。
     *
     * <p>认法是第一格（首格单）那件方块物品的命名空间——清单里每组的图标就是主机的方块物品，
     * 它跟着 {@code HostGroup} 一起同步到客户端，所以不必为「画哪种槽底」再往协议里加一个字段。</p>
     */
    private static final Set<String> MATRIX_SLOT_NAMESPACES = Set.of(
            "neoecoae", "neoecoprototype");

    /** 标记区不可编辑时槽底的淡化比例（AE2 元件工作台里元件没插时也是这个值）。 */
    private static final float DISABLED_SLOT_ALPHA = 0.2f;

    /** 空白槽覆盖层整体左移的像素数（实测：不挪则左边界对齐格带、右边界会翻到右边框上）。 */
    private static final int EMPTY_CELL_SHIFT = 1;

    /**
     * 最右那一列要压窄的像素：它右边没有下一格来挡，多铺的这一列 px 会压到表格外沿。
     *
     * <p>Blitter 是把 16×16 的源图拉伸铺满目标矩形，少铺 1 像素就是横向整体压窄一点，不是裁 UV。</p>
     */
    private static final int EMPTY_CELL_TRIM_RIGHT = 1;

    private final Geometry geo;
    private final Bands bands;

    CellTablePainter(Geometry geo, Bands bands) {
        this.geo = geo;
        this.bands = bands;
    }

    // ---- 表格绘制 ----

    /** 表头带 / 可见行带 / 尾饰带，以及每台驱动器首格的绿底与选中、钉住两圈描边。 */
    void drawBackground(GuiGraphics guiGraphics, int offsetX, int offsetY, Frame frame) {
        blit(this.bands.headerBand(), guiGraphics, offsetX, offsetY);

        int y = offsetY + this.geo.headerHeight();
        for (int i = 0; i < frame.visibleRows(); i++) {
            int rowIndex = frame.scrollOffset() + i;
            boolean firstLine = i == 0;
            boolean lastLine = i == frame.visibleRows() - 1;
            int rowY = y + i * this.geo.rowHeight();
            boolean slotsRow = rowKindAt(frame.rows(), rowIndex) == RowKind.SLOTS;

            blit(selectRowBand(false, firstLine, lastLine), guiGraphics, offsetX, rowY);
            if (slotsRow) {
                blit(selectRowBand(true, firstLine, lastLine), guiGraphics, offsetX, rowY);
            }
            if (rowIndex < frame.rows().size()
                    && frame.rows().get(rowIndex) instanceof CellTableRows.DriveRow driveRow) {
                // 每台 AE2 / EAE 驱动器、每个 ECO 主机都有自己的首格：逐格压一层绿底，选中的那一个描一圈。
                for (int column = 0; column < driveRow.cells().size(); column++) {
                    var entry = driveRow.cells().get(column);
                    if (!entry.isLeader()) {
                        continue;
                    }
                    int frameX = offsetX + this.geo.listX() + column * this.geo.cellSize();
                    guiGraphics.fill(frameX + this.geo.cellInset(), rowY + this.geo.cellInset(),
                            frameX + this.geo.cellInset() + this.geo.contentSize(),
                            rowY + this.geo.cellInset() + this.geo.contentSize(),
                            DRIVE_SLOT_TINT);
                    // 两个状态的描边颜色不同：右键选中的黄，左键钉住的虹色（世界里只有钉住会画）。
                    if (entry.leaderKey().equals(frame.selectedLeaderKey())) {
                        outlineCell(guiGraphics, frameX, rowY, DRIVE_SELECTED_TINT);
                    } else if (isPinned(entry.leaderKey())) {
                        outlineCell(guiGraphics, frameX, rowY, rainbowTint());
                    }
                }
            }
        }

        blit(this.bands.footerBand(), guiGraphics, offsetX,
                offsetY + this.geo.headerHeight() + frame.visibleRows() * this.geo.rowHeight());
    }

    /**
     * 标题栏与元件行：物品图标、优先级文本、元件物品，段尾盖空白槽位；两根占用条留到最后单独补画。
     *
     * <p>第一遍只记下每根条的位置：{@code renderItem} 与 {@code fill} 都只往 GuiGraphics 的 bufferSource
     * 提交顶点，屏幕拿的是 managed 的 GuiGraphics，{@code flushIfUnmanaged()} 不会真的刷出；整帧在末尾按
     * RenderType 统一 endBatch，物品那一批会画在 {@code RenderType.gui()} 的矩形之上。所以必须先把物品刷出去
     * 再画条，条才落在最上层。整张表只 flush 一次，不按格 endBatch。</p>
     */
    void drawForegroundRows(GuiGraphics guiGraphics, Frame frame) {
        // renderLabels 的 pose 已平移到 (leftPos, topPos)，这里必须用裸局部坐标。
        int baseX = this.geo.listX();
        int baseY = this.geo.listY() + this.geo.headerHeight();
        var drives = frame.driveLookup();
        var bars = new ArrayList<OccupancyBar>();

        for (int i = 0; i < frame.visibleRows(); i++) {
            int rowIndex = frame.scrollOffset() + i;
            if (rowIndex < 0 || rowIndex >= frame.rows().size()) {
                continue;
            }
            int rowY = baseY + i * this.geo.rowHeight();
            boolean topRow = i == 0;
            boolean bottomRow = i == frame.visibleRows() - 1;
            switch (frame.rows().get(rowIndex)) {
                case CellTableRows.HeaderRow header ->
                        drawHeaderRow(guiGraphics, baseX, rowY, header, drives, frame.font());
                case CellTableRows.DriveRow driveRow ->
                        drawDriveRow(guiGraphics, baseX, rowY, driveRow, drives, topRow, bottomRow, frame.font(), bars);
            }
        }

        if (!bars.isEmpty()) {
            guiGraphics.flush();
            for (var bar : bars) {
                drawOccupancyBars(guiGraphics, bar.x(), bar.y(), bar.cell());
            }
        }
    }

    /**
     * 三类槽位的覆盖层：元件存储槽、元件编码槽、元件升级槽各一张切片，元件标记槽按显隐淡化。
     *
     * <p>贴图上这些位置没有画槽框（它们不是 AE2 那套固定布局），所以槽框得由屏幕补上。位置不写死，
     * 直接读菜单里那批槽的实坐标——样式文档一改，覆盖层跟着走。</p>
     *
     * <p><b>落点用 {@code slot.x/slot.y}，不是 {@code x-1/y-1}</b>：AE2 的槽位坐标是「18x18 槽框的内侧
     * 16x16 内容区左上角」（贴图实测：槽框占 x=181..198，内容区从 182,195 起），而覆盖层切片就是 16x16，
     * 该点正是它要盖的那块内容区。再减 1 会整体偏左上 1 像素。</p>
     */
    void drawSlotOverlays(GuiGraphics guiGraphics, SlotFrame slots) {
        for (var slot : slots.encodeSlots()) {
            blitSlotOverlay(guiGraphics, slot.x, slot.y, CELL_SLOT_OVERLAY, 1f);
        }
        for (var slot : slots.upgradeSlots()) {
            blitSlotOverlay(guiGraphics, slot.x, slot.y, UPGRADE_SLOT_OVERLAY, 1f);
        }
        // 标记槽：有元件且可编辑时实画，没元件（或在 CLEAR_ON_REMOVE 下元件装不下）时淡到 0.2——
        // 与 AE2 元件工作台同一套显隐口径，槽底不消失，只变淡。
        // 元件自己给上限的那些（格数比 63 小）多出来的格连槽底都不画：那些格永远收不下东西。
        float markerAlpha = slots.markerAreaEditable() ? 1f : DISABLED_SLOT_ALPHA;
        for (var slot : slots.markerSlots()) {
            if (markerIndex(slot, slots.markerRowOffset()) >= slots.markerSlotCount()) {
                continue;
            }
            blitSlotOverlay(guiGraphics, slot.x, slot.y, MARKER_SLOT_OVERLAY, markerAlpha);
        }
    }

    /** 这一格标记槽在整片 63 格里的序号：窗口内序号 + 已滚过的格数。 */
    static int markerIndex(Slot slot, int markerRowOffset) {
        return slot.getContainerSlot()
                + markerRowOffset * AEPatternRegistries.CELL_MARKER_COLUMN.length;
    }

    // ---- 屏幕交互/提示也用的纯工具 ----

    /** 鼠标所在列对应的那一格；超出本行格子数（行尾余量）时返回 null。 */
    @Nullable
    static CellTableRows.TableCell entryAt(CellTableRows.DriveRow row, int column) {
        return column >= 0 && column < row.cells().size() ? row.cells().get(column) : null;
    }

    /**
     * 左键：钉住这一台（再左键一次取消）。
     *
     * <p>钉住与右键选中的分工是：右键只是 GUI 里的目标（关屏就没了，世界里不画），钉住才是「在世界里把它
     * 显示出来」——虹色的负尺寸方框 + 一条从眼睛过去的虹色直线，关屏也还在。</p>
     */
    static void togglePinnedDrive(String leaderKey) {
        var location = locationOf(leaderKey);
        if (location == null) {
            return;
        }
        DriveHighlight.togglePinned(location.dimension(), location.pos());
    }

    /** 首格（一个首格单）的提示：哪家的、在哪、几个元件，然后一条手势一行。 */
    static List<Component> leaderTooltip(Map<String, CellHostListPayload.HostGroup> drives,
            CellTableRows.DriveRow row, CellTableRows.TableCell entry) {
        var group = drives.get(row.driveKey());
        var lines = new ArrayList<Component>();
        lines.add(Component.translatable("gui.ae2_pattern_disk.cell_management_terminal.drive_tooltip.host",
                group != null ? group.name() : row.driveKey(), describePosition(entry.leaderKey())));
        if (group != null) {
            lines.add(Component.translatable(
                    "gui.ae2_pattern_disk.cell_management_terminal.drive_tooltip.priority", group.priority()));
            lines.add(Component.translatable(
                    "gui.ae2_pattern_disk.cell_management_terminal.drive_tooltip.cell_count",
                    filledSlotsOf(group, entry.leaderKey())));
        }
        // 选中与钉住的状态不另写一行：行上的高亮、世界里的方框与连线已经把它们说清楚了，tooltip 再复述
        // 一遍只是噪音。手势行下面照旧。
        lines.add(hint("gui.ae2_pattern_disk.cell_management_terminal.drive_tooltip.left"));
        lines.add(hint("gui.ae2_pattern_disk.cell_management_terminal.drive_tooltip.right"));
        lines.add(hint("gui.ae2_pattern_disk.cell_management_terminal.drive_tooltip.mid"));
        return lines;
    }

    /**
     * 那一个首格单里真的插了几个元件。
     *
     * <p>不能拿 {@code cells().size()}，也不能拿整组的：那张表是稠密的（每个物理格一条，空格也在），
     * 而首格只对应现在那一段（一台驱动器或一个主机）——十格插三张要写成三张。</p>
     */
    private static int filledSlotsOf(CellHostListPayload.HostGroup group, String leaderKey) {
        int filled = 0;
        for (CellHostListPayload.CellSlot cell : group.cells()) {
            if (cell.leaderKey().equals(leaderKey) && !cell.stack().isEmpty()) {
                filled++;
            }
        }
        return filled;
    }

    /**
     * 元件格的提示：物品自己的提示在前（含 AE2 给存储元件加的自定义组件），后面只补一条不是常识的手势。
     *
     * <p>取到光标、放进这一格、Shift 收背包都不写：容器里最基础的操作，玩家不需要有人告诉他左键是拿、
     * Shift 是整组搬。只有「右键会移到选中的驱动器，没有选中时进编码槽」这条不是常识，才值得占一行。</p>
     *
     * <p>用 {@code getTooltipFromItem} 取物品提示而不是自己拼名字：元件的信息比一个名字多得多。</p>
     */
    static List<Component> cellTooltip(ItemStack stack) {
        var lines = new ArrayList<Component>(Screen.getTooltipFromItem(Minecraft.getInstance(), stack));
        lines.add(hint("gui.ae2_pattern_disk.cell_management_terminal.cell_tooltip.to_encode"));
        return lines;
    }

    /** 手势提示行：统一灰色，与物品自身的提示区分开。 */
    static Component hint(String key) {
        return Component.translatable(key).withStyle(ChatFormatting.GRAY);
    }

    /** 这一台在哪：坐标串；键不是位置时原样给出键，好让人看出是协议对不上。 */
    private static String describePosition(String hostKey) {
        var location = locationOf(hostKey);
        return location == null ? hostKey : location.pos().toShortString();
    }

    /** 这一台钉着没有（表格描边与世界里那圈框同一个口径）。 */
    private static boolean isPinned(String leaderKey) {
        var location = locationOf(leaderKey);
        return location != null && DriveHighlight.isPinned(location.dimension(), location.pos());
    }

    /** 宿主键解成「维度 + 坐标」；解不开返回 null。 */
    @Nullable
    private static HostLocation locationOf(String hostKey) {
        int at = hostKey.lastIndexOf('@');
        if (at <= 0) {
            return null;
        }
        try {
            return new HostLocation(ResourceLocation.parse(hostKey.substring(0, at)),
                    BlockPos.of(Long.parseLong(hostKey.substring(at + 1))));
        } catch (NumberFormatException | net.minecraft.ResourceLocationException broken) {
            return null;
        }
    }

    /** 一台宿主的物理位置（与 {@code CellDriveScanner.keyOf} 的编码同一口径）。 */
    private record HostLocation(ResourceLocation dimension, BlockPos pos) {
    }

    // ---- 绘制辅助 ----

    /** 一行在贴图上该用哪条带：标题栏用文本带，驱动器行用物品带。 */
    private enum RowKind { TEXT, SLOTS }

    private RowKind rowKindAt(List<CellTableRows.Row> rows, int rowIndex) {
        if (rowIndex < 0 || rowIndex >= rows.size()) {
            return RowKind.TEXT;
        }
        return rows.get(rowIndex) instanceof CellTableRows.HeaderRow ? RowKind.TEXT : RowKind.SLOTS;
    }

    private Blitter selectRowBand(boolean slotsRow, boolean firstLine, boolean lastLine) {
        if (slotsRow) {
            return firstLine ? this.bands.rowSlotsTop()
                    : lastLine ? this.bands.rowSlotsBottom() : this.bands.rowSlotsMiddle();
        }
        return firstLine ? this.bands.rowTextTop()
                : lastLine ? this.bands.rowTextBottom() : this.bands.rowTextMiddle();
    }

    /** 本模组那份 states.png 里的一格：16x16，坐标即 UV。 */
    private static Blitter states(int u, int v) {
        return Blitter.texture(net.minecraft.resources.ResourceLocation
                .parse("ae2_pattern_disk:textures/guis/states.png")).src(u, v, 16, 16);
    }

    private static void blit(Blitter blitter, GuiGraphics guiGraphics, int destX, int destY) {
        blitter.dest(destX, destY).blit(guiGraphics);
    }

    /** 一张槽位覆盖层（带可选透明度）：按切片原尺寸铺，用在菜单里那三批真槽位上。 */
    private static void blitSlotOverlay(GuiGraphics guiGraphics, int destX, int destY, Blitter overlay, float alpha) {
        overlay.dest(destX, destY).color(1f, 1f, 1f, alpha).blit(guiGraphics);
    }

    /**
     * 给表里的一格铺空白槽位（段尾那些放不进去的列）：把 16×16 的切片铺满 18 的格距。
     *
     * <p>横向：左缘对齐格带左框线（减掉槽框那一像素），右缘再裁 1 像素——切片比内容区宽，不裁就压住右邻
     * 那一格的左边框。每列同一算式，所以不会出现一列宽一列窄。</p>
     *
     * <p>纵向上下各 1 像素，可视区顶行不向上、末行不向下，免得溢进表头带与尾饰带。</p>
     */
    private void blitEmptyCell(GuiGraphics guiGraphics, int cellX, int rowY, int column, boolean topRow,
            boolean bottomRow) {
        // 左边界对齐格带（减 1 抵消槽框那一像素）；宽度：只有最右那一列压窄 1 像素——它右边没有下一格来挡，
        // 多铺的那一像素会压到表格外沿；中间的列照满格宽铺，相邻两列之间才不会出现缝。
        int x = cellX - EMPTY_CELL_SHIFT;
        int y = topRow ? rowY + this.geo.cellInset() : rowY;
        int width = column == CellTableRows.COLUMNS - 1
                ? this.geo.cellSize() - EMPTY_CELL_TRIM_RIGHT
                : this.geo.cellSize();
        int height = topRow || bottomRow ? this.geo.cellSize() - this.geo.cellInset() : this.geo.cellSize();
        EMPTY_SLOT_OVERLAY.dest(x, y, width, height).blit(guiGraphics);
    }

    /** 把一格的边框描出来（选中/钉住用）：画在框线上，不盖住格内内容，与样板磁盘管理终端同规格。 */
    private void outlineCell(GuiGraphics guiGraphics, int cellX, int rowY, int tint) {
        int size = this.geo.cellSize();
        guiGraphics.fill(cellX, rowY, cellX + size, rowY + 1, tint);
        guiGraphics.fill(cellX, rowY + size - 1, cellX + size, rowY + size, tint);
        guiGraphics.fill(cellX, rowY + 1, cellX + 1, rowY + size - 1, tint);
        guiGraphics.fill(cellX + size - 1, rowY + 1, cellX + size, rowY + size - 1, tint);
    }

    /** 标题栏：驱动器物品图标 + 优先级文本 + 这一组有几个元件。 */
    private void drawHeaderRow(GuiGraphics guiGraphics, int baseX, int rowY, CellTableRows.HeaderRow header,
            Map<String, CellHostListPayload.HostGroup> drives, Font font) {
        var group = drives.get(header.driveKey());
        int textX = baseX + 4;
        if (group != null && !group.icon().isEmpty()) {
            // 与样板磁盘终端的标题栏同口径：图标落点 baseX+1、基线 rowY+ROW_TEXT_Y_INSET 再上移 1px（实机反馈），文字从图标右边 4 像素起。
            guiGraphics.renderItem(group.icon(), baseX + 1, rowY + this.geo.rowTextYInset() - 1);
            textX = baseX + 21;
        }
        String label = Component.translatable("gui.ae2_pattern_disk.cell_management_terminal.drive_header",
                header.name(), header.priority(), header.cellCount()).getString();
        guiGraphics.drawString(font, font.plainSubstrByWidth(label, this.geo.listWidth() - (textX - baseX) - 4),
                textX, rowY + this.geo.rowTextYInset() + 4, this.geo.textColor(), false);
    }

    /**
     * 元件行：首格画那一段的图标（一台驱动器或一个主机），元件格里插了元件的画物品并记下两根占用条
     * 的位置（由调用方在物品批次刷出后补画），空着的真格按 16×16 盖元件存储槽那一层，段尾超出格数的
     * 列盖空白槽位。
     *
     * <p>两种「空」是不同的东西：前者是那台上真实存在、可以放元件的格（盖元件槽底），后者是那一台根本没有的
     * 列（盖空白槽位，不接任何点击）。</p>
     */
    private void drawDriveRow(GuiGraphics guiGraphics, int baseX, int rowY, CellTableRows.DriveRow row,
            Map<String, CellHostListPayload.HostGroup> drives, boolean topRow, boolean bottomRow, Font font,
            List<OccupancyBar> bars) {
        var group = drives.get(row.driveKey());
        if (group == null) {
            return;
        }
        for (int column = 0; column < CellTableRows.COLUMNS; column++) {
            int cellX = baseX + column * this.geo.cellSize() + this.geo.cellInset();
            int cellY = rowY + this.geo.cellInset();
            var entry = column < row.cells().size() ? row.cells().get(column) : null;
            if (entry == null) {
                // 段尾：这一台（主机）没有这么多格，盖一层空白槽位，并按格距铺满不留缝
                blitEmptyCell(guiGraphics, cellX, rowY, column, topRow, bottomRow);
                continue;
            }
            if (entry.isLeader()) {
                // 首格：那一段的领物品（AE2 系是那台驱动器的方块物品，ECO 系是主机的）
                guiGraphics.renderItem(group.icon(), cellX, cellY);
                continue;
            }
            var cell = entry.cell();
            if (cell.stack().isEmpty()) {
                // 真实存在、还能放元件的空格：ECO 家族盖矩阵座，其余盖元件存储槽底，都按切片原尺寸 16×16 铺
                blitSlotOverlay(guiGraphics, cellX, cellY,
                        usesMatrixSlot(group) ? MATRIX_SLOT_OVERLAY : CELL_SLOT_OVERLAY, 1f);
                continue;
            }
            guiGraphics.renderItem(cell.stack(), cellX, cellY);
            guiGraphics.renderItemDecorations(font, cell.stack(), cellX, cellY);
            bars.add(new OccupancyBar(cellX, cellY, cell));
        }
    }

    /** 这一段的存储格是不是 ECO 家族的矩阵座：认首格图标的命名空间。 */
    private static boolean usesMatrixSlot(@Nullable CellHostListPayload.HostGroup group) {
        if (group == null || group.icon().isEmpty()) {
            return false;
        }
        return MATRIX_SLOT_NAMESPACES.contains(
                BuiltInRegistries.ITEM.getKey(group.icon().getItem()).getNamespace());
    }

    /**
     * 物品 16×16 槽内贴左缘的两根竖直占用条：左列种类、右列数量，各占 1px 宽，自下而上填充。
     *
     * <p>两条固定取槽内的最左两列（{@code cellX} 与 {@code cellX + 1}）：实机确认占用条必须落在物品槽内，
     * 画到槽外那两列余量会串到相邻格。原先横着画在图标内侧底部，与存储元件物品自带的占用显示叠在同一处
     * 看不清，竖排到左缘后避开那两行。</p>
     *
     * <p>底轨先铺满再叠填充，空元件也看得见这两列；高度按 contentSize 缩放，与格距同源。</p>
     *
     * <p>调用方必须先 {@code guiGraphics.flush()} 把物品那一批刷出去。本方法的 fill 顶点若与物品顶点同批
     * 提交，会按 RenderType 排序后被物品盖住——这是渲染批次问题，改坐标或 z 都绕不过去。</p>
     */
    private void drawOccupancyBars(GuiGraphics guiGraphics, int cellX, int cellY,
            CellHostListPayload.CellSlot cell) {
        int size = this.geo.contentSize();
        int bottom = cellY + size;
        int typeX = cellX;
        int byteX = cellX + 1;
        guiGraphics.fill(typeX, cellY, typeX + 1, bottom, BAR_TRACK_COLOR);
        guiGraphics.fill(byteX, cellY, byteX + 1, bottom, BAR_TRACK_COLOR);
        int typeHeight = Math.round(size * Math.clamp(cell.typeFill(), 0f, 1f));
        int byteHeight = Math.round(size * Math.clamp(cell.byteFill(), 0f, 1f));
        if (typeHeight > 0) {
            guiGraphics.fill(typeX, bottom - typeHeight, typeX + 1, bottom, TYPE_BAR_COLOR);
        }
        if (byteHeight > 0) {
            guiGraphics.fill(byteX, bottom - byteHeight, byteX + 1, bottom, BYTE_BAR_COLOR);
        }
    }
}
