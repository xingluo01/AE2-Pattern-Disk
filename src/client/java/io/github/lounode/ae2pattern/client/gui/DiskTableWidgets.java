package io.github.lounode.ae2pattern.client.gui;

import static io.github.lounode.ae2pattern.client.gui.DiskTableRowModel.DiskRow;
import static io.github.lounode.ae2pattern.client.gui.DiskTableRowModel.FreeSlotsRow;
import static io.github.lounode.ae2pattern.client.gui.DiskTableRowModel.HostRow;
import static io.github.lounode.ae2pattern.client.gui.DiskTableRowModel.Row;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import appeng.api.config.SortDir;
import appeng.api.config.SortOrder;
import appeng.api.stacks.GenericStack;
import appeng.client.gui.StackWithBounds;
import appeng.client.gui.WidgetContainer;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.PaletteColor;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.Scrollbar;

import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.menu.pattern.PatternDiskManagementTermMenu;
import io.github.lounode.ae2pattern.network.VisibleDisksPayload;

/**
 * 管理终端表格本体：从样式文档读来的几何与贴图切片、行模型、滚动位置、盘内显示顺序，以及绘制、命中测试、
 * 内容请求与写盘候选。屏幕上那几枚按钮与视图状态在 {@link DiskToolbarWidgets}，这里只通过四个 getter 读它们。
 *
 * <p>从 {@code PatternDiskManagementTermScreen}（1100 多行）里整块搬出来——屏幕同时管着行模型、表格绘制、
 * 点击分发、滚动与状态同步，这些是同一件能一句话讲清的事：这张表长什么样、玩家点到哪一格。屏幕本体只留
 * {@code init} / {@code render} / {@code drawBG} / {@code drawFG} 等 {@code @Override} 覆写和一行转调。</p>
 *
 * <p><b>不持有屏幕</b>：需要的外部输入全部在构造期以窄回调 / 对象注入（排序档位、父类搜索口径、候选兜底、
 * 断线遮罩），随帧变化的值由这些回调现取，绘制时的 {@code font} / {@code leftPos} / {@code topPos} 走方法参数。
 * 注入的都是 {@code Supplier} / {@code Function} 这类单方法接口或具体对象，没有 {@code Screen} 类型的字段，
 * 也不放宽屏幕成员的可见性。</p>
 *
 * <p><b>控件顺序即清单</b>：滚动条在本类构造器里 {@code widgets.addScrollBar} 注册，排在工具栏四枚控件之后
 * （见 {@link DiskToolbarWidgets}），与屏幕原样一致——它决定渲染层级与输入命中，不能重排。</p>
 */
final class DiskTableWidgets {

    /** 表格断线遮罩的落点回调：屏幕把自己 {@code renderDisconnectedOverlay(...)} 注进来。 */
    @FunctionalInterface
    interface OverlayRenderer {
        void render(GuiGraphics guiGraphics, int x, int y, int width, int height);
    }

    // 表格区几何：外壳的贴图切片（表头带 / 六条行带 / 尾饰带 / 空槽格）全在样式文档的 images 里，
    // 带高与格距都从那些切片反推——文档改一格，代码不用跟着改。
    // 行分配同 AE2 的样板访问终端（PatternAccessTermScreen.drawBG）：按「行类型」从贴图取行带——
    // 文本带（无格子框，给主机名这类纯文本行）与物品带（有 17 格框，给磁盘/样板行），
    // 三份分别对应可见窗口的首行/中间行/末行（该屏的 ROW_TEXT_/ROW_INVENTORY_TOP|MIDDLE|BOTTOM_BBOX 同值）。
    // 落点口径同 AE2 槽位：格子的 x/y 就是「物品左上角」，底图画在它 −1 处，所以绘制时统一 +1——
    // LIST_X 取 7 时物品落在贴图实测的 x=8。
    private static final int LIST_X = 7;
    private static final int LIST_Y = 0;
    private static final int COLUMNS = 17;
    /**
     * 槽位内容的纵向起点：行带里格框的填充区从带上沿 +1 开始（横向同理，见各绘制处的 +1）。物品与槽底都从这里
     * 画，不然会比格框低 1px、底下露出一道缝。
     */
    private static final int CELL_Y_INSET = 1;
    /**
     * 纯文本行（组头）的纵向起点：那一行没有格框，图标/文字/开关按自己的观感取 +2，不跟着槽位一起上移。
     */
    private static final int ROW_TEXT_Y_INSET = 2;
    /** 视口外多要一行内容：滚一格时不至于先闪一帧空行。 */
    private static final int CONTENT_MARGIN_ROWS = 1;

    /**
     * 可见集合没变也重报一次的间隔（tick）。
     *
     * <p>没有这个“心跳”，盘内内容一变就会过期：客户端只会在自己那份「已请求」集合变化时上报，而往里写入一张
     * 样板并不改变集合。服务端每张盘比指纹、没变就不发包，所以这条心跳的常态开销只是一次空请求。</p>
     */
    private static final int CONTENT_REFRESH_INTERVAL_TICKS = 20;

    /**
     * 磁盘槽的浅绿色底色（纯上色，不走纹理资源）。
     *
     * <p>与盘内样板的格子在视觉上分开：一眼能看出哪一格是磁盘本身。带 alpha，底下的槽位描边与棋盘格仍透着。</p>
     */
    private static final int DISK_SLOT_TINT = 0x66B9F6CA;

    /** 右键选中的那张盘：给它的首格描一圈高亮，一眼看出「编写样板」会写进谁。 */
    private static final int DISK_SELECTED_TINT = 0xCCFFD54F;

    // ---- 构造期注入的外部依赖（不持有屏幕） ----

    private final PatternDiskManagementTermMenu menu;
    private final DiskToolbarWidgets toolbar;
    private final IntFunction<DiskListPanel.DiskEntry> diskEntryAt;
    private final BooleanSupplier diskSearchActive;
    private final Supplier<DiskEntryFilter.Query> diskSearchQuery;
    private final Supplier<long[]> tierGroupByUsage;
    private final Function<long[], long[]> capCandidates;
    private final OverlayRenderer overlayRenderer;

    // ---- 几何、贴图与行模型 ----

    /**
     * 表头带 / 尾饰带 / 行带的高与格距：全部取自样式文档 images 切片的 srcRect。
     *
     * <p>面板总高由 {@code terminalStyle} 那条链算出，可见行数是 {@code (总高 - 表头 - 尾饰) / 行高}；
     * 这两组带高都在同一份文档里，代码不再抄写一遍数字。</p>
     */
    private final int headerHeight;
    private final int footerHeight;
    private final int rowHeight;
    /** 格距（含框线）：空槽格切片的宽，列命中测试与格子落点都用它。 */
    private final int cellSize;
    /** 填充区宽：列数 × 行高。再右是滚动条区（本屏只用滚轮，不画它）。 */
    private final int listWidth;
    /** 组头文字色：样式文档调色板的 DEFAULT_TEXT_COLOR（与 AE2 其余界面同源）。 */
    private final int textColor;

    /**
     * 当前可见行数：由终端风格档位与窗口高度共同决定，数值就是父类按 terminalStyle 算好的 imageHeight 反推来的，
     * {@code init()} 里赋。屏幕不随 resize 重建，但 init() 会在 resize 后被调用，所以这一个字段就够。
     */
    private int visibleRows = 6;

    /**
     * 表格自己的滚动条（字段名与父类那枚区分开）。本屏的行带不是 AE2 物品网格，所以范围得自己喂给它（见 {@link #syncScrollbar()}）；
     * 拖动、滚轮、上下翻页按钮都由它处理，屏幕只负责把位置抄回来。
     */
    private Scrollbar tableScrollbar;

    /** 盘内样板的显示顺序（内容搜索 + 排序档位）都在 {@link DiskPatternView} 里，这里只持有它。 */
    private final DiskPatternView patternView;

    private final List<Row> rows = new ArrayList<>();
    private final LongSet requestedContents = new LongOpenHashSet();

    private int scrollOffset;
    private int contentRefreshCooldown = CONTENT_REFRESH_INTERVAL_TICKS;

    // 表外壳的切片全部来自样式文档的 images（切片坐标与 512 参考尺寸都写在文档里），代码只按名字取。
    // Blitter 是可变对象且与文档共享同一实例：每次使用必须紧接 dest(...) + blit(...)，不要另存引用或染色。
    private final Blitter headerBand;
    private final Blitter footerBand;
    /** 空槽格的底图：贴图里「空白样板」那一格，宽高正好是一个槽位框。 */
    private final Blitter blankCell;
    // 六条行带，与 AE2 的 ROW_TEXT_/ROW_INVENTORY_TOP|MIDDLE|BOTTOM_BBOX 同一分区（本屏贴图与它同源）。
    private final Blitter rowTextTop;
    private final Blitter rowSlotsTop;
    private final Blitter rowTextMiddle;
    private final Blitter rowSlotsMiddle;
    private final Blitter rowTextBottom;
    private final Blitter rowSlotsBottom;

    DiskTableWidgets(ScreenStyle style, PatternDiskManagementTermMenu menu, WidgetContainer widgets,
            DiskToolbarWidgets toolbar, Supplier<SortOrder> sortBy, Supplier<SortDir> sortDir,
            BooleanSupplier naturalSort, IntFunction<DiskListPanel.DiskEntry> diskEntryAt,
            BooleanSupplier diskSearchActive, Supplier<DiskEntryFilter.Query> diskSearchQuery,
            Supplier<long[]> tierGroupByUsage, Function<long[], long[]> capCandidates,
            OverlayRenderer overlayRenderer) {
        this.menu = menu;
        this.toolbar = toolbar;
        this.diskEntryAt = diskEntryAt;
        this.diskSearchActive = diskSearchActive;
        this.diskSearchQuery = diskSearchQuery;
        this.tierGroupByUsage = tierGroupByUsage;
        this.capCandidates = capCandidates;
        this.overlayRenderer = overlayRenderer;
        this.patternView = new DiskPatternView(sortBy, sortDir, naturalSort);

        // 表外壳的切片与从它们反推的几何：全部读自样式文档（images 与调色板）。文档缺键会直接抛
        // IllegalStateException、界面开不出来——这是有意口径：静默降级只会画出一半底图。
        this.headerBand = style.getImage("tableHeader");
        this.footerBand = style.getImage("tableFooter");
        this.blankCell = style.getImage("blankCell");
        this.rowTextTop = style.getImage("rowTextTop");
        this.rowSlotsTop = style.getImage("rowSlotsTop");
        this.rowTextMiddle = style.getImage("rowTextMiddle");
        this.rowSlotsMiddle = style.getImage("rowSlotsMiddle");
        this.rowTextBottom = style.getImage("rowTextBottom");
        this.rowSlotsBottom = style.getImage("rowSlotsBottom");
        this.headerHeight = this.headerBand.getSrcHeight();
        this.footerHeight = this.footerBand.getSrcHeight();
        // 六条行带同高（文档里都是 18），取哪一条反推行高都一样。
        this.rowHeight = this.rowTextTop.getSrcHeight();
        this.cellSize = this.blankCell.getSrcWidth();
        this.listWidth = COLUMNS * this.rowHeight;
        this.textColor = style.getColor(PaletteColor.DEFAULT_TEXT_COLOR).toARGB();

        // 滚动条：给表格自己接一条（样式 JSON 的 widgets.tableScrollbar 给了落点，高度每帧按行数设）。
        // 必须换一个 id：父类在自己的构造器里已经用 "scrollbar" 那一个 id 注册过物品网格的滚动条，
        // 同一个 id 再注册一次会直接抛 IllegalStateException（界面开不出来）。
        this.tableScrollbar = widgets.addScrollBar("tableScrollbar", Scrollbar.BIG);
    }

    /** 行数由父类按 style 的 terminalStyle 算好（imageHeight 就是它的产物），这里反推回来用。 */
    void computeVisibleRows(int imageHeight) {
        this.visibleRows = Math.max(1, (imageHeight - this.headerHeight - this.footerHeight) / this.rowHeight);
    }

    /** 内容请求心跳：到点就重报一次当前视口（集合没变也报，见常量注释）。 */
    void tickContentRefresh() {
        if (--contentRefreshCooldown <= 0) {
            contentRefreshCooldown = CONTENT_REFRESH_INTERVAL_TICKS;
            requestVisibleContents(true);
        }
    }

    // ---- 行模型 ----

    /**
     * 依据「父类过滤后的磁盘清单」与「服务端的分组清单」重塑行模型。
     *
     * <p>骨架是**宿主**而不是磁盘：一台支持样板磁盘的机器哪怕一张盘都没插也占一组（能看出它有几个空
     * 槽），这正是这张表与「只列磁盘」的区别。盘仍旧取父类那份过滤后的列表，所以搜索框筛掉谁，表里就
     * 没有谁；搜索生效时没有命中磁盘的组不出现（否则一搜就满屏空机器），没搜索时所有宿主都在。</p>
     */
    void rebuildRows() {
        // 父类过滤后的磁盘（搜索口径），按 serial 索引。
        var visibleDisks = new HashMap<Long, DiskListPanel.DiskEntry>();
        for (int i = 0; diskEntryAt.apply(i) != null; i++) {
            var entry = diskEntryAt.apply(i);
            visibleDisks.put(entry.serial(), entry);
        }

        boolean searched = diskSearchActive.getAsBoolean();
        // 内容搜索（新搜索栏）是否在生效：它决定不匹配的样板/机器/空行要不要真的从表里消失。
        boolean contentSearched = toolbar.contentSearchText() != null && !toolbar.contentSearchText().isEmpty();

        // 盘内顺序缓存跟着当下的盘集合走：被取走、被搜索筛掉的盘不再留条目（连同它那份旧 contents 列表）。
        patternView.retainOnly(visibleDisks.keySet());

        // 机器列表默认按显示名排（大小写不敏感的字符串序；中文名走 Unicode 码点序、不是拼音序——项目里
        // 唯一的拼音能力只有搜索用的 JECH 匹配，取不到拼音串）。同名机器的相对次序沿用服务端原序
        //（同名本就合并成一行，这里排的是不同机器之间）。
        var hosts = new ArrayList<>(menu.getHostList());
        hosts.sort(Comparator.comparing(host -> host.name() == null ? "" : host.name(),
                String.CASE_INSENSITIVE_ORDER));
        // 行的构建（三层过滤、续行、空槽行）已搬去 DiskTableRowBuilder，屏幕只负责把当下状态打包成
        // 输入：哪些盘可见、每张盘命中多少样板（-1 表示内容未下发，构建器不会据此隐藏）。
        var groupInputs = new ArrayList<DiskTableRowBuilder.GroupInput>(hosts.size());
        for (var group : hosts) {
            var diskInputs = new ArrayList<DiskTableRowBuilder.DiskInput>(group.disks().size());
            for (var disk : group.disks()) {
                var visible = visibleDisks.get(disk.serial());
                if (visible != null) {
                    diskInputs.add(new DiskTableRowBuilder.DiskInput(disk.serial(), visible.stack(),
                            disk.patternCount(), matchedPatternCount(disk.serial())));
                }
            }
            groupInputs.add(new DiskTableRowBuilder.GroupInput(group.key(), group.name(), group.icon(),
                    diskInputs, group.emptySlots()));
        }
        var rebuilt = DiskTableRowBuilder.build(groupInputs, searched, contentSearched, toolbar.hideEmptySlots());

        if (!rows.equals(rebuilt)) {
            rows.clear();
            rows.addAll(rebuilt);
            clampScroll();
        }
        requestVisibleContents(false);
    }

    /** 该盘命中内容搜索的样板数；内容未下发时返回 -1（未知，调用方不得据此隐藏）。 */
    private int matchedPatternCount(long serial) {
        if (menu.getDiskContents(serial) == null) {
            return -1;
        }
        return displayOrder(serial).length;
    }

    void clampScroll() {
        int max = Math.max(0, rows.size() - visibleRows);
        scrollOffset = Math.max(0, Math.min(scrollOffset, max));
    }

    /**
     * 写盘顺位候选：选中的那张盘排在最前，其后是同一容器（表格里的同一组）内的其他已插入磁盘；
     * 没有选中盘时退回与编码终端相同的口径——搜索栏有内容才按列表顺序顺位，否则不给目标。
     */
    long[] buildAutoDiskCandidates() {
        var out = new ArrayList<Long>();
        if (toolbar.selectedSerial() != 0) {
            out.add(toolbar.selectedSerial());
            var groupKey = groupKeyOf(toolbar.selectedSerial());
            if (groupKey != null) {
                for (var group : menu.getHostList()) {
                    if (!groupKey.equals(group.key())) {
                        continue;
                    }
                    for (var disk : group.disks()) {
                        if (disk.serial() != toolbar.selectedSerial()) {
                            out.add(disk.serial());
                        }
                    }
                }
            }
        } else if (diskSearchQuery.get().preferOrder()) {
            // 没选中盘、且写了 {@code @order}/{@code @顺位}：按表格行序把一张填满再下一张（与玩家看到的顺序一致）。
            // 默认不写这个修饰词，所以“筛了盘之后写哪张”仍然是按剩余空间挑（与编码终端同口径）。
            for (var row : rows) {
                if (row instanceof DiskRow disk && disk.from() == 0) {
                    out.add(disk.serial());
                }
            }
        } else {
            // 没选中盘（搜索与否都一样）：当前档位那一组盘按存量升序，写最少的那张
            // （与编码终端同口径，见基类 tierGroupByUsage）。本分支原本返回空，而「盘明明对得上、点编写样板
            // 却没反应」正是那个空造成的。选中盘的优先级在本分支之上，已选中的目标不会被这里抢走。
            return tierGroupByUsage.get();
        }
        var serials = new long[out.size()];
        for (int i = 0; i < serials.length; i++) {
            serials[i] = out.get(i);
        }
        return capCandidates.apply(serials);
    }

    /** 这张盘属于哪一组（同一键即为同一容器，与表格的分组口径一致）。 */
    private String groupKeyOf(long serial) {
        for (var group : menu.getHostList()) {
            for (var disk : group.disks()) {
                if (disk.serial() == serial) {
                    return group.key();
                }
            }
        }
        return null;
    }

    /**
     * 上报当前视口（多要一行余量）里的磁盘序列号。
     *
     * <p>只在集合真的变了才发包：滚动一屏发一次，而不是每帧一次。</p>
     */
    private void requestVisibleContents(boolean force) {
        var wanted = new LongOpenHashSet();
        int first = Math.max(0, scrollOffset - CONTENT_MARGIN_ROWS);
        int last = Math.min(rows.size(), scrollOffset + visibleRows + CONTENT_MARGIN_ROWS);
        for (int i = first; i < last; i++) {
            if (rows.get(i) instanceof DiskRow disk) {
                wanted.add(disk.serial());
            }
        }
        if (!force && wanted.equals(requestedContents)) {
            return;
        }
        requestedContents.clear();
        requestedContents.addAll(wanted);
        if (!wanted.isEmpty()) {
            PacketDistributor.sendToServer(new VisibleDisksPayload(List.copyOf(wanted)));
        }
    }

    /**
     * 把行数告诉滚动条，再把滚动条的位置抄回 {@code scrollOffset}。
     *
     * <p>滚动状态只有滚动条一份：滚轮、拖动滑块、上下翻页都改它（{@code wantsAllMouseWheelEvents}
     * 让它接管整屏滚轮，所以本屏不再自己处理 wheel）；行数变少时这里把越界的位置夹回来。</p>
     */
    void syncScrollbar() {
        int maxScroll = Math.max(0, rows.size() - visibleRows);
        this.tableScrollbar.setHeight(Math.max(1, visibleRows * this.rowHeight - 2));
        this.tableScrollbar.setRange(0, maxScroll, Math.max(1, visibleRows / 6));
        this.scrollOffset = this.tableScrollbar.getCurrentScroll();
        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
            this.tableScrollbar.setCurrentScroll(maxScroll);
        }
    }

    // ---- 绘制 ----

    /**
     * 不调 super.drawBG：那张底图是一整块固定高度的贴图，而本表的面板高随终端风格档位变化，所以这里自己拼
     * 面板：表头带 + 可见行带 + 尾饰带（行数见 visibleRows）。跳过它的代价是 AE2 物品网格的 pinned 行覆盖层
     * 与那次手写 searchField.render——本屏不显示那个网格，而搜索框仍由 widget 容器正常渲染。
     */
    void drawBG(GuiGraphics guiGraphics, int offsetX, int offsetY) {
        blit(this.headerBand, guiGraphics, offsetX, offsetY);

        int y = offsetY + this.headerHeight;

        // 行分配同 AE2：每行先铺「文本带」作底，含物品格的行再叠「物品带」；带是整条的（含左右边框）。
        for (int i = 0; i < visibleRows; i++) {
            boolean firstLine = i == 0;
            boolean lastLine = i == visibleRows - 1;
            int rowY = y + i * this.rowHeight;
            boolean slotsRow = rowKindAt(scrollOffset + i) == RowKind.SLOTS;

            blit(selectRowBand(false, firstLine, lastLine), guiGraphics, offsetX, rowY);
            if (slotsRow) {
                blit(selectRowBand(true, firstLine, lastLine), guiGraphics, offsetX, rowY);
            }
            // 磁盘首格再压一层浅绿，位置与 drawFG 的磁盘图标同一点（续行的第 0 格是样板格，不上色）。
            if (scrollOffset + i < rows.size() && rows.get(scrollOffset + i) instanceof DiskRow diskRow
                    && diskRow.from() == 0) {
                int cellX = offsetX + LIST_X + 1;
                guiGraphics.fill(cellX, rowY + CELL_Y_INSET, cellX + 16, rowY + CELL_Y_INSET + 16, DISK_SLOT_TINT);
            }
            // 选中的那张盘：首格格框描一圈高亮。
            if (scrollOffset + i < rows.size() && rows.get(scrollOffset + i) instanceof DiskRow selected
                    && selected.from() == 0 && selected.serial() == toolbar.selectedSerial()) {
                outlineCell(guiGraphics, offsetX + LIST_X, rowY);
            }
        }

        blit(this.footerBand, guiGraphics, offsetX, offsetY + this.headerHeight + visibleRows * this.rowHeight);
    }

    /** 一行在贴图上该用哪条带：纯文本行（主机名）用文本带，含物品格的行用物品带。 */
    private enum RowKind { TEXT, SLOTS }

    private RowKind rowKindAt(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= rows.size()) {
            return RowKind.TEXT;
        }
        return rows.get(rowIndex) instanceof HostRow ? RowKind.TEXT : RowKind.SLOTS;
    }

    /**
     * 行带的选择与 AE2 的 {@code PatternAccessTermScreen#selectRowBackgroundBox} 同口径：可见窗口的首行取 TOP、
     * 末行取 BOTTOM、其余取 MIDDLE。那六条带本身来自样式文档的 images。
     */
    private Blitter selectRowBand(boolean slotsRow, boolean firstLine, boolean lastLine) {
        if (slotsRow) {
            return firstLine ? this.rowSlotsTop : lastLine ? this.rowSlotsBottom : this.rowSlotsMiddle;
        }
        return firstLine ? this.rowTextTop : lastLine ? this.rowTextBottom : this.rowTextMiddle;
    }

    private static void blit(Blitter blitter, GuiGraphics guiGraphics, int destX, int destY) {
        blitter.dest(destX, destY).blit(guiGraphics);
    }

    /**
     * 不调 super.drawFG：AE2 那一层只做四件事——记鼠标位置（喂它那个已无人调用的私有 isHovered）、
     * 给物品网格的钉住行描边、给合成状态按钮标作业数、按物品网格矩形铺离线遮罩。本屏没有物品网格槽
     * （init 里整批移除）、样式也不开自动合成（supportsAutoCrafting=false），前三件都是空转；
     * 第四件盖不住本屏 17 列 304 宽的格子区、还会与本屏那层叠成更深的暗斑，改由
     * renderDisconnectedOverlay 按格子区铺。（AE2 升级时复核它的 drawFG。）
     *
     * <p>renderLabels 的 pose 已由 vanilla 平移到 (leftPos, topPos)，这里必须用裸局部坐标：
     * 再加 offsetX/offsetY 会把整个 GUI 原点算第二遍，表格内容整体右下偏移一格到数格（AE2 自家
     * drawFG 同样用裸坐标，如 VibrationChamberScreen 的 dest(80, 20 + ...)）。</p>
     */
    void drawFG(GuiGraphics guiGraphics, Font font, int offsetX, int offsetY, int mouseX, int mouseY) {
        int baseX = LIST_X;
        int baseY = LIST_Y + this.headerHeight;

        for (int i = 0; i < visibleRows; i++) {
            int rowIndex = scrollOffset + i;
            if (rowIndex >= rows.size()) {
                break;
            }

            int rowY = baseY + i * this.rowHeight;
            switch (rows.get(rowIndex)) {
                case HostRow host -> {
                    if (!host.icon().isEmpty()) {
                        guiGraphics.renderItem(host.icon(), baseX + 1, rowY + ROW_TEXT_Y_INSET - 1);
                    }
                    var label = host.diskCount() > 1
                            ? host.name() + " (" + host.diskCount() + ")"
                            : host.name();
                    guiGraphics.drawString(font, font.plainSubstrByWidth(label, 16 * 18 - 22),
                            baseX + 21, rowY + ROW_TEXT_Y_INSET + 4, textColor, false);
                }
                case DiskRow disk -> drawDiskRow(guiGraphics, font, baseX, rowY, disk);
                case FreeSlotsRow free -> drawFreeSlotsRow(guiGraphics, font, baseX, rowY, free);
            }
        }

        // 断开时只压格子区：17 格 × 18 = 306 宽（含左右格框各 1px；实机确认只盖 304 时框线会露出 1px），
        // 高随可见行数走。
        overlayRenderer.render(guiGraphics, LIST_X, this.headerHeight,
                this.listWidth, this.visibleRows * this.rowHeight);
    }

    /**
     * 磁盘行：首行的第 0 格是磁盘、后面 16 格是盘内样板；续行没有磁盘格，17 格全是样板（内容到达前只画磁盘）。
     */
    private void drawDiskRow(GuiGraphics guiGraphics, Font font, int baseX, int rowY, DiskRow row) {
        int firstColumn = row.from() == 0 ? 1 : 0;
        if (firstColumn == 1) {
            guiGraphics.renderItem(row.disk(), baseX + 1, rowY + CELL_Y_INSET);
            guiGraphics.renderItemDecorations(font, row.disk(), baseX + 1, rowY + CELL_Y_INSET);
        }

        var patterns = menu.getDiskContents(row.serial());
        if (patterns == null) {
            return;
        }
        var order = displayOrder(row.serial());

        // 这一行第 0 格对应的样板位次：首行被磁盘占了第 0 格，所以减 1。
        int patternBase = row.from() - firstColumn;
        var level = Minecraft.getInstance().level;
        for (int column = firstColumn; column < COLUMNS; column++) {
            int position = patternBase + column;
            if (position < 0 || position >= order.length) {
                break;
            }
            var pattern = patterns.get(order[position]);
            if (pattern.isEmpty()) {
                continue;
            }

            int cellX = baseX + column * this.cellSize + 1;
            int cellY = rowY + CELL_Y_INSET;

            // 显示主产物，而不是样板本体：与 AE2 样板访问终端同口径（它的 PatternSlot.getDisplayStack 用
            // EncodedPatternItem#getOutput 换掉槽位显示）。任何类型的产物都直接显示——那个方法对流体等
            // 非物品产出会包一层伪物品；取不到时回退到样板本体。玩家因此不必按住 Shift 才知道样板做什么。
            var icon = DiskPatternView.displayedItem(pattern);
            guiGraphics.renderItem(icon, cellX, cellY);
            guiGraphics.renderItemDecorations(font, icon, cellX, cellY);

            // 解不出来的样板标红：与样板访问终端同一个提示口径，一眼看出哪张盘里有坏样板。
            if (level != null && appeng.api.crafting.PatternDetailsHelper.decodePattern(pattern, level) == null) {
                guiGraphics.fill(cellX, cellY, cellX + 16, cellY + 16, 0x7fff0000);
            }
        }
    }

    /**
     * 剩余槽位行：一行一格，把空槽画成空格；收起时那一行代表整台机器的全部空槽，数字写在格的右上角。
     */
    private void drawFreeSlotsRow(GuiGraphics guiGraphics, Font font, int baseX, int rowY, FreeSlotsRow row) {
        // blankCell 是一个完整的槽框（自带 1px 边框），落点与行带本身的格框同格位——框对框，它内部就自然落在
        // +1，与同排物品格的内容线一致（物品画在 +CELL_Y_INSET）。
        blit(this.blankCell, guiGraphics, baseX, rowY);

        if (row.foldedCount() <= 0) {
            return;
        }
        var count = Integer.toString(row.foldedCount());
        // 右上角：与那格右/上边线各留 1px（格内从框 +1 起）。
        guiGraphics.drawString(font, count, baseX + 1 + 16 - font.width(count), rowY + 2, 0xFF3F3F3F, false);
    }

    /**
     * 磁盘格的信息：名字、已用/容量、标记（原版高级信息下多一行原始 id），以及这一格上不靠常识就能猜到的
     * 那几条手势。
     *
     * <p>与编码终端磁盘列表用同一套用词与语言键（它们说的是同一张盘），只把手势换成管理终端自己的。
     * 左键取盘、Shift+左键进背包不列：那是所有容器都一样的取放，列出来只是噪音。</p>
     */
    void renderDiskTooltip(GuiGraphics guiGraphics, Font font, DiskRow row, int x, int y) {
        var stack = row.disk();
        var lines = new ArrayList<Component>();
        lines.add(Component.literal(stack.getHoverName().getString()));

        if (stack.getItem() instanceof PatternDiskItem disk) {
            var contents = disk.contents(stack);
            lines.add(Component.translatable("ae2_pattern_disk.tooltip.capacity", contents.used(), contents.capacity()));
        }

        var mark = PatternDiskMarks.displayName(stack);
        if (mark != null) {
            lines.add(Component.translatable("ae2_pattern_disk.tooltip.mark", mark));
            if (Minecraft.getInstance().options.advancedItemTooltips) {
                var raw = PatternDiskMarks.rawMark(stack);
                if (raw != null) {
                    lines.add(Component.translatable("ae2_pattern_disk.tooltip.mark.raw", raw)
                            .withStyle(ChatFormatting.DARK_GRAY));
                }
            }
        }

        // 左键取盘、Shift+左键进背包不写：容器里最基础的取放，不需要额外注释。
        lines.add(Component.translatable("gui.ae2_pattern_disk.management_terminal.tooltip.disk.right_click")
                .withStyle(ChatFormatting.GRAY));
        // Shift+右键 打标：管理终端这里确实是 Shift+右键，编码终端却是普通右键，所以用自己这条 key。
        // 别再复用编码终端那条——两终端手势不同，共用一条文案必有一条是错的。
        lines.add(Component.translatable("gui.ae2_pattern_disk.management_terminal.tooltip.disk.shift_right_click")
                .withStyle(ChatFormatting.GRAY));

        guiGraphics.renderComponentTooltip(font, lines, x, y);
    }

    // ---- 命中测试（面板内坐标 → 行列） ----

    int rowIndexAt(double mouseX, double mouseY, int leftPos, int topPos) {
        int relX = (int) mouseX - leftPos - LIST_X;
        int relY = (int) mouseY - topPos - LIST_Y - this.headerHeight;
        if (relX < 0 || relX >= this.listWidth || relY < 0) {
            return -1;
        }
        int slotRow = relY / this.rowHeight;
        if (slotRow < 0 || slotRow >= visibleRows) {
            return -1;
        }
        int rowIndex = scrollOffset + slotRow;
        return rowIndex < rows.size() ? rowIndex : -1;
    }

    int columnAt(double mouseX, int leftPos) {
        return ((int) mouseX - leftPos - LIST_X) / this.cellSize;
    }

    @Nullable
    ItemStack itemAt(int rowIndex, int column) {
        if (rowIndex < 0 || rowIndex >= rows.size() || column < 0 || column >= COLUMNS) {
            return null;
        }
        if (rows.get(rowIndex) instanceof DiskRow disk) {
            int index = patternIndexAt(rowIndex, column);
            if (index >= 0) {
                return menu.getDiskContents(disk.serial()).get(index);
            }
            if (disk.from() == 0 && column == 0) {
                return disk.disk();
            }
        }
        return null;
    }

    /**
     * 把光标下的那一格交给切片浏览器（JEI / EMI / REI）。表格是自绘的，真实槽位在 {@code init()} 里被移除，
     * 所以父类的默认实现（只认 vanilla 槽）在这一屏问不出任何东西。这是 AE2 为「不在普通槽里的 ingredient」
     * 留的钩子（见 {@code AEBaseScreen#getStackUnderMouse} 的注释），AE2 自己的自绘表格也这么做（样板访问
     * 终端、合成 CPU 的 {@code CraftingCPUScreen}）。
     *
     * <p>交出的是格子上画着的那个东西：样板的主产物（与 {@code drawDiskRow} 的图标同一个来源，产物不是物品
     * 时是它自带的伪物品包装，{@code GenericStack.fromItemStack} 会拆开）。R/U 因此交给浏览器自己响应——
     * 本项目不注册键位、也不自己判断按了什么键。</p>
     *
     * <p>只认样板格（磁盘格、空格、组头行不在此列），解不出主产物的坏样板不交（那一格按 R/U 安静地无事发生，
     * 与格子上标红的提示一致）；背包与快捷栏那些真实槽仍走父类。</p>
     *
     * <p>两条前提：JEI 那一侧要装 AE2 的 JEI 集成模组（AE2 19.2.x 本体不带 JEI 支持，本屏的 JEI 转发由那个
     * 模组提供；EMI 与 REI 由 AE2 自带模块转发）。另外，格子 tooltip 说的是样板本体（含输入清单），而这里的
     * R/U 查的是它的主产物——两者口径不同是有意的。</p>
     *
     * @return 命中时给出带范围的栈；没命中返回 {@code null}，由屏幕转去 {@code super.getStackUnderMouse}。
     */
    @Nullable
    StackWithBounds stackUnderMouse(double mouseX, double mouseY, int leftPos, int topPos) {
        int rowIndex = rowIndexAt(mouseX, mouseY, leftPos, topPos);
        int column = columnAt(mouseX, leftPos);
        if (rowIndex >= 0 && column >= 0 && column < COLUMNS && patternIndexAt(rowIndex, column) >= 0) {
            var pattern = itemAt(rowIndex, column);
            var stack = GenericStack.fromItemStack(pattern == null ? ItemStack.EMPTY : DiskPatternView.outputOf(pattern));
            if (stack != null) {
                // 非物品产出（流体等）是经伪物品包装过来的，里面带的量是 0，而浏览器侧只有 JEI 会把 0 夹到 1。
                // 这里统一补到 1，两家口径一致，也免得键匹配不上。
                if (stack.amount() <= 0) {
                    stack = new GenericStack(stack.what(), 1);
                }
                // 坐标与 drawDiskRow 逐字一致（baseX = LIST_X、rowY = 表头下沿 + 可见行号 × 行高、格内再各 +1），
                // 只是把局部坐标换成绝对屏幕坐标；矩形口径与 AE2 的 StackWithBounds.fromSlot 相同（一格内容 16×16）。
                int cellX = leftPos + LIST_X + column * this.cellSize + 1;
                int cellY = topPos + LIST_Y + this.headerHeight + (rowIndex - scrollOffset) * this.rowHeight
                        + CELL_Y_INSET;
                return new StackWithBounds(stack, new Rect2i(cellX, cellY, 16, 16));
            }
        }
        return null;
    }

    /** 表里（当前过滤/显示口径下）有没有这张盘。 */
    boolean containsDisk(long serial) {
        for (var row : rows) {
            if (row instanceof DiskRow disk && disk.from() == 0 && disk.serial() == serial) {
                return true;
            }
        }
        return false;
    }

    /**
     * 命中位置对应的样板序号（盘内内容的平坦序号）；不在样板格上返回 -1。
     *
     * <p>空槽、越界、不在磁盘行上一并算作 -1：取件、tooltip 与点击因此共用同一套判定。</p>
     */
    int patternIndexAt(int rowIndex, int column) {
        if (rowIndex < 0 || rowIndex >= rows.size() || column < 0 || column >= COLUMNS) {
            return -1;
        }
        if (!(rows.get(rowIndex) instanceof DiskRow disk)) {
            return -1;
        }
        int firstColumn = disk.from() == 0 ? 1 : 0;
        if (column < firstColumn) {
            return -1; // 首行第 0 格是磁盘本身
        }
        var patterns = menu.getDiskContents(disk.serial());
        var order = displayOrder(disk.serial());
        int position = disk.from() - firstColumn + column;
        if (patterns == null || position < 0 || position >= order.length) {
            return -1;
        }
        // 回到存储序号：取件、换槽都是按它与服务端对话的，所以命中的是排好的那一位、拿到的是盘里的那一位。
        int index = order[position];
        if (index < 0 || index >= patterns.size() || patterns.get(index).isEmpty()) {
            return -1;
        }
        return index;
    }

    /** 盘内样板的显示顺序（显示位次 → 存储序号）；口径与缓存都在 {@link DiskPatternView}。 */
    private int[] displayOrder(long serial) {
        return patternView.orderOf(serial, menu.getDiskContents(serial), toolbar.contentSearchText(),
                toolbar.searchScope());
    }

    /** 给某个格位（一个槽框）描一圈高亮：画在框线上，不盖住格内内容。 */
    private void outlineCell(GuiGraphics guiGraphics, int cellX, int rowY) {
        int size = this.cellSize;
        guiGraphics.fill(cellX, rowY, cellX + size, rowY + 1, DISK_SELECTED_TINT);
        guiGraphics.fill(cellX, rowY + size - 1, cellX + size, rowY + size, DISK_SELECTED_TINT);
        guiGraphics.fill(cellX, rowY + 1, cellX + 1, rowY + size - 1, DISK_SELECTED_TINT);
        guiGraphics.fill(cellX + size - 1, rowY + 1, cellX + size, rowY + size - 1,
                DISK_SELECTED_TINT);
    }

    /** 行索引已由 {@link #rowIndexAt} 校验过，这里直接取（与原屏幕 {@code rows.get(rowIndex)} 同口径）。 */
    Row rowAt(int rowIndex) {
        return rows.get(rowIndex);
    }
}
