package io.github.lounode.ae2pattern.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import appeng.client.gui.me.common.MEStorageScreen;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.PaletteColor;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.Scrollbar;
import appeng.client.Point;

import org.anti_ad.mc.ipn.api.IPNPlayerSideOnly;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import appeng.api.config.CopyMode;
import appeng.api.config.SortDir;

import io.github.lounode.ae2pattern.common.menu.cell.CellManagementTermMenu;
import io.github.lounode.ae2pattern.network.CellHostListPayload;
import io.github.lounode.ae2pattern.common.menu.cell.CellFormats;

/**
 * 元件管理终端的屏幕：一张自绘表格（存储优先级标题栏 + 驱动器行），右下角是元件编码槽、升级槽与元件标记区。
 *
 * <p>表格的组织方式与样板磁盘管理终端同源：面板由样式文档里的九条切片拼出（表头带 + 可见行带 + 尾饰带），
 * 行高与列宽都从切片反推，行数由面板总高反算。这里不调 {@code super.drawBG}——AE2 那张底图是固定高度的
 * 一整块贴图，而本表的面板高随终端风格档位变化。</p>
 *
 * <p><b>占用条在物品槽内贴左缘竖画</b>：两根各 1px 宽，占 16×16 物品槽的最左两列，左列是存储种类占用
 * （蓝）、右列是存储数量占用（绿），自下而上填充。原先横着画在图标内侧底部，与存储元件物品自带的占用显示
 * 叠在同一处被压住；槽外左右各只剩 1px 余量，条不能画到那一带，否则会串到相邻格。</p>
 *
 * <p><b>占用条必须晚于物品绘制</b>：{@code renderItem} 与 {@code fill} 都只往 GuiGraphics 的 bufferSource
 * 提交顶点，managed 状态下不会立即刷出，整帧末尾按 RenderType 统一 endBatch 时物品那批会盖住
 * {@code RenderType.gui()} 的矩形。表格在画完全部物品后显式 {@code flush()} 一次，再补画所有占用条；
 * 这与条画在哪个坐标无关，前两次换位都解决不了遮挡就是因为这个。</p>
 *
 * <p><b>首格认的是「一个首格单」</b>：一台 AE2 / EAE 驱动器就是一个首格单，一个 ECO 主控制器也是一个（它
 * 旗下最多 33 个存储矩阵都算它的格）。每段都从首列起排，点首格的三个手势只针对那一段：左键 = 钉住/取消（把
 * 它在世界里显示出来：虹色负尺寸方框 + 一条从眼睛过去的虹色直线，关掉终端也还在），并把维度坐标打到聊天栏；
 * 右键 = 选中/取消（选中的那一段是「存元件进去」与「把元件移过去」的目标，世界里不画，提示就是表格里那圈虹
 * 色描边）；中键 = 配它的存储优先级。</p>
 *
 * <p><b>选中态是屏幕本地状态</b>：它只影响那一格的高亮描边（黄色；左键钉住的那格是虹色），不参与任何服务端
 * 判定；服务端拿到的只是一个宿主键，它得再核一次那一台还在不在网里。</p>
 */
@IPNPlayerSideOnly
public class CellManagementTermScreen extends AbstractPatternDiskTermScreen<CellManagementTermMenu> {

    // 表格几何：与样板终端同口径（物品落点 = 格原点 +1，行高与列宽从切片反推）。
    private static final int LIST_X = 7;
    private static final int LIST_Y = 0;
    private static final int CELL_INSET = 1;
    private static final int ROW_TEXT_Y_INSET = 2;

    private final Blitter headerBand;
    private final Blitter footerBand;
    private final Blitter rowTextTop;
    private final Blitter rowSlotsTop;
    private final Blitter rowTextMiddle;
    private final Blitter rowSlotsMiddle;
    private final Blitter rowTextBottom;
    private final Blitter rowSlotsBottom;
    private final Blitter blankCell;

    private final int headerHeight;
    private final int footerHeight;
    private final int rowHeight;
    private final int cellSize;
    /** 一格里物品/槽底的边长：格距减掉槽框左（上）右（下）各留的那一像素，不写死 16。 */
    private final int contentSize;
    private final int listWidth;
    private final int textColor;

    private final Scrollbar tableScrollbar;
    private final Scrollbar markerScrollbar;

    /** 滚动条落点基准（init 时从样式文档给的原始落点记下）：横向固定，表格那条纵向固定、标记区那条按面板底边算。 */
    private int tableBarX;
    private int tableBarTop;
    private int markerBarX;
    private int markerBarFromBottom;

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.cell_management_terminal");

    private int visibleRows = 6;
    private int scrollOffset;
    private final List<CellTableRows.Row> rows = new ArrayList<>();
    private List<CellHostListPayload.HostGroup> renderedDriveList;
    private boolean renderedDescending;
    private java.util.Map<String, CellHostListPayload.HostGroup> renderedDriveLookup = java.util.Map.of();

    /** 选中的那一个首格单（一台 AE2 / EAE 驱动器，或一个 ECO 主机）的键；空串表示没选。 */
    private String selectedLeaderKey = "";

    /** 左侧工具栏整组：按钮 + 它们的显隐/选中状态 + 按下动作。构造期连窄依赖一起注入。 */
    private final CellToolbar toolbar;

    /** 表格呈现层：把本屏每帧的状态快照画成像素与提示，几何与切片在构造期注入。 */
    private final CellTablePainter painter;

    public CellManagementTermScreen(CellManagementTermMenu menu, Inventory playerInventory, Component title,
            ScreenStyle style) {
        super(menu, playerInventory, title, style);

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
        this.rowHeight = this.rowTextTop.getSrcHeight();
        this.cellSize = this.blankCell.getSrcWidth();
        this.contentSize = this.cellSize - 2 * CELL_INSET;
        this.listWidth = CellTableRows.COLUMNS * this.cellSize;
        this.textColor = style.getColor(PaletteColor.DEFAULT_TEXT_COLOR).toARGB();

        this.tableScrollbar = widgets.addScrollBar("tableScrollbar", Scrollbar.BIG);
        this.markerScrollbar = widgets.addScrollBar("cellMarkerScrollbar", Scrollbar.BIG);

        // 工具栏整组在构造期连窄依赖（菜单动作 + 屏幕特有服务）一起注入；按钮按建好的顺序上左侧工具栏，
        // 顺序影响渲染层级与输入命中，必须与原来逐个 addToLeftToolbar 的次序一致。
        this.toolbar = new CellToolbar(new CellToolbar.Context(
                getMenu()::partition,
                getMenu()::clearMarkerArea,
                getMenu()::cycleCopyMode,
                getMenu()::cycleCompressionCutoff,
                getMenu()::cycleEcoBulkCutoff,
                getMenu()::markBookmarks,
                getMenu()::quickMark,
                getMenu()::cycleVoidMode,
                getMenu()::consolidateEcoBulkMarkers,
                () -> getMenu().getCellHost().getEncodeCellInventory().getStackInSlot(0),
                getMenu()::isMarkerAreaEditable,
                this::isHandlingRightClick));
        for (var button : this.toolbar.buttons()) {
            addToLeftToolbar(button);
        }

        // 呈现层的几何与切片构造期一次注入，之后每帧只把变化的表格状态打包成 Frame / SlotFrame 传进去。
        this.painter = new CellTablePainter(
                new CellTablePainter.Geometry(LIST_X, LIST_Y, CELL_INSET, ROW_TEXT_Y_INSET,
                        this.headerHeight, this.footerHeight, this.rowHeight, this.cellSize,
                        this.contentSize, this.listWidth, this.textColor),
                new CellTablePainter.Bands(this.headerBand, this.footerBand,
                        this.rowTextTop, this.rowSlotsTop, this.rowTextMiddle, this.rowSlotsMiddle,
                        this.rowTextBottom, this.rowSlotsBottom));
    }

    @Override
    public void init() {
        super.init();
        // AE2 的物品网格本屏不显示：它的槽位留着会抢点击与提示（表格画在同一个位置）。
        this.menu.slots.removeIf(slot -> slot instanceof appeng.client.gui.me.common.RepoSlot);
        // 可见行数由面板高反推：表头 + 行×行高 + 尾饰 = 面板高。
        int imageHeight = this.imageHeight;
        this.visibleRows = Math.max(1, (imageHeight - this.headerHeight - this.footerHeight) / this.rowHeight);

        // 两条滚动条的落点基准只在这里记一次（此时还没人 setPosition 过，getBounds 就是样式文档给的原始落点）：
        // 表格那条贴表头顶部、标记区那条贴面板底边。终端高度随风格档位变，后者的纵坐标每帧要按底边重算，
        // 写死在样式里会在面板变高/变矮后错位。横向两条轨道不在同一个 x，各自按自己的基准走。
        var tableBounds = this.tableScrollbar.getBounds();
        this.tableBarX = tableBounds.getX();
        this.tableBarTop = tableBounds.getY();
        var markerBounds = this.markerScrollbar.getBounds();
        this.markerBarX = markerBounds.getX();
        // 样式用 bottom 锚（y = imageHeight - bottom），这里把「距底边多少」记下来，每帧乘回当前面板高。
        this.markerBarFromBottom = imageHeight - markerBounds.getY();

        // 关掉 AE2 物品网格那枚滚动条的滞轮捕获：它在 widget 表里排得更前、又默认 captureMouseWheel，
        // 一旦有 range 就会把整屏滞轮吃掉（而它 park 在屏外，玩家看不到任何反应）。照样板屏的做法反射取。
        var gridScrollbar = parentScrollbar(this);
        if (gridScrollbar != null) {
            gridScrollbar.setCaptureMouseWheel(false);
        }
    }

    /**
     * 表格按优先级排的方向：true = 从大到小（高优先级在前），false = 从小到大。
     *
     * <p>映射与直觉相反是有意的：AE2 那枚「排序顺序」的两档是 ASCENDING（箭头向上）与 DESCENDING
     * （箭头向下），而这里排的是「优先级」——优先级高的先被存，所以「顺序」那一档对应的是优先级从大到小。
     * 这也正是本表在长出这枚按钮之前写死的那一档：玩家不碰按钮时看到的东西与以前一模一样。</p>
     */
    private boolean priorityDescending() {
        return getSortDir() == SortDir.ASCENDING;
    }

    /**
     * 左侧工具栏的清单：顺序即清单顺序，不在清单里的一律隐藏（含 AE2 那枚「终端设置」）。
     *
     * <p>闪电科技那两枚（频率卡配置、自动连接开关）由上游自己对每个通用终端能力屏追加（频率卡在场时才真出现）：
     * 按 tooltip 定身份（见基类的 {@code isFrequencyCardButton} / {@code isFrequencyAutoConnectButton}）。</p>
     *
     * <p>三枚截断钮排在末尾、彼此相邻：MEGA 一枚，ECO 大宗元件两枚（截断物与切换选中）。它们都是「编码槽里
     * 那张元件决定显隐」的按钮，同一时间最多出现一组。</p>
     */
    private static final List<String> TOOLBAR = List.of(
            "guide", "sortOrder", "terminalStyle", "partition", "clear", "copyMode",
            "markBookmarks", "quickMark", "voidMode", "terminalSwitch", "frequencyCard", "frequencyAutoConnect",
            "megaCutoff", "ecoBulkCutoff", "ecoBulkCutoffSwitch", "ecoBulkConsolidate");

    @Override
    protected List<String> toolbarPlan() {
        return TOOLBAR;
    }

    /**
     * 本屏的按钮身份：清单里的名字只在这里给出。
     *
     * <p>AE2 那几枚按身份认：指南走类，排序顺序与终端风格走它们自己带的设置档（{@code Settings}）。
     * 「终端设置」不在这里——它和本模组自己的按钮同是 {@code ActionButton}，只能按 tooltip 认，用基类那个助手。</p>
     */
    @Override
    protected String toolbarSlot(Button button) {
        // 基类先认 AE2 那几枚（指南 / 排序按 / 排序顺序 / 模式轮换 / 终端设置…）。
        var fromBase = super.toolbarSlot(button);
        if (fromBase != null) {
            return fromBase;
        }
        // 「切换终端」那枚由基类按类名认（无线屏挂上来的 AE2WTLib 按钮），这里不必再重复；
        // 剩下自家这几枚交给工具栏按引用认（清单里的名字只在那里给出）。
        return this.toolbar.slotOf(button);
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        this.toolbar.syncAll(getMenu().copyMode == CopyMode.CLEAR_ON_REMOVE);
        syncScrollbar();
        rebuildRows();
        // 行模型重建后行数可能变了，滚动条的范围得跟着重喂一次（两次调用是有意的，不是冗余）。
        syncScrollbar();
        syncMarkerScrollbar();
    }

    /** 当前可见行数（无线屏要拿它给升级卡面板定行数）。 */
    protected int getVisibleRows() {
        return this.visibleRows;
    }

    /** 表格行来自服务端推的驱动器清单；清单没变时不重建，免得每帧丢 List。 */
    private void rebuildRows() {
        var drives = getMenu().getDriveList();
        boolean descending = priorityDescending();
        // 菜单只在全表/差量包到达时更换不可变列表；排序按钮变化也必须让缓存失效。
        if (renderedDriveList == drives && renderedDescending == descending) return;
        renderedDriveList = drives;
        renderedDescending = descending;
        rows.clear();
        rows.addAll(CellTableRows.build(drives, descending));
        var lookup = new java.util.HashMap<String, CellHostListPayload.HostGroup>();
        for (var group : drives) lookup.put(group.key(), group);
        renderedDriveLookup = java.util.Map.copyOf(lookup);
        if (!selectedLeaderKey.isEmpty() && !blockPresent(selectedLeaderKey)) {
            selectedLeaderKey = "";
        }
    }

    /**
     * 选中的那一个首格单还在不在表里。
     *
     * <p>被拆掉、断电、移出网都会让它从下一次推送里消失；选中态得跟着收掉，否则「存进选中的那一台」
     * 会一直往一个已经不存在的键上报，服务端只能回一句找不到。</p>
     */
    private boolean blockPresent(String leaderKey) {
        for (var group : getMenu().getDriveList()) {
            for (var cell : group.cells()) {
                if (cell.leaderKey().equals(leaderKey)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 把行数告诉滚动条，再把位置抄回来；行数变少时把越界位置夹回来。 */
    private void syncScrollbar() {
        int maxScroll = Math.max(0, rows.size() - visibleRows);
        // 高度 = 可见行占的那段（6 行 = 108）：贴图轨道正好那么高，滑块才能从头跑到尾。
        this.tableScrollbar.setPosition(new Point(this.tableBarX, this.tableBarTop));
        this.tableScrollbar.setHeight(Math.max(1, visibleRows * this.rowHeight));
        this.tableScrollbar.setRange(0, maxScroll, Math.max(1, visibleRows / 6));
        this.scrollOffset = this.tableScrollbar.getCurrentScroll();
        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
            this.tableScrollbar.setCurrentScroll(maxScroll);
        }
    }

    /** 标记区三行窗口在九行里滚动：滚动条动的只是窗口起点。能滚到多远由元件的实际格数决定。 */
    private void syncMarkerScrollbar() {
        int maxScroll = getMenu().maxMarkerRowOffset();
        // 纵向每帧按面板底边重算：终端高度（风格档位）一变，标记区整体上下移，写死的 top 会错位。
        this.markerScrollbar.setPosition(new Point(this.markerBarX, this.imageHeight - this.markerBarFromBottom));
        this.markerScrollbar.setHeight(Math.max(1,
                CellManagementTermMenu.VISIBLE_MARKER_ROWS * this.rowHeight));
        this.markerScrollbar.setRange(0, Math.max(0, maxScroll), 1);
        int wanted = this.markerScrollbar.getCurrentScroll();
        if (wanted != getMenu().getMarkerRowOffset()) {
            getMenu().setMarkerRowOffset(wanted);
        }
    }

    /**
     * 取父类那枚物品网格滚动条（字段是 private：AE2 没给读法，ModAccessor 又只在运行时改名、编译期看不见，
     * 所以只能反射）。取不到就作罢：最坏是表格滞轮不灵，不该因此把界面弄坏。
     */
    @Nullable
    private static Scrollbar parentScrollbar(MEStorageScreen<?> screen) {
        try {
            var field = MEStorageScreen.class.getDeclaredField("scrollbar");
            field.setAccessible(true);
            return field.get(screen) instanceof Scrollbar bar ? bar : null;
        } catch (Throwable e) {
            LOGGER.debug("Could not reach AE2's grid scrollbar; table scrolling may ignore the wheel", e);
            return null;
        }
    }

    // ---- 绘制 ----

    /** 每帧把本屏的表格状态打包成呈现层要的快照（行模型、滚动、选中键、字体、驱动器查表）。 */
    private CellTablePainter.Frame tableFrame() {
        return new CellTablePainter.Frame(this.rows, this.scrollOffset, this.visibleRows,
                this.selectedLeaderKey, driveLookup(), this.font);
    }

    /** 每帧把三类真槽位的实坐标与标记区显隐判据打包给呈现层画覆盖层。 */
    private CellTablePainter.SlotFrame slotFrame() {
        var markerSlots = new ArrayList<Slot>();
        for (var column : AEPatternRegistries.CELL_MARKER_COLUMN) {
            markerSlots.addAll(getMenu().slotsOf(column));
        }
        return new CellTablePainter.SlotFrame(
                getMenu().slotsOf(AEPatternRegistries.CELL_ENCODE),
                getMenu().slotsOf(AEPatternRegistries.CELL_UPGRADE),
                markerSlots,
                getMenu().isMarkerAreaEditable(),
                getMenu().markerSlotCount(),
                getMenu().getMarkerRowOffset());
    }

    @Override
    public void drawBG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY,
            float partialTicks) {
        // 不调 super.drawBG：理由见类注释（底图是固定高度整块贴图）。
        this.painter.drawBackground(guiGraphics, offsetX, offsetY, tableFrame());
    }

    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        // 不调 super.drawFG：AE2 那一层只做四件事——记鼠标位置（喂它那个已无人调用的私有 isHovered）、
        // 给物品网格的钉住行描边、给合成状态按钮标作业数、按物品网格矩形铺离线遮罩。本屏没有物品网格槽
        // （init 里整批移除）、样式也不开自动合成（supportsAutoCrafting=false），前三件都是空转；
        // 第四件盖不住本屏 17 列 304 宽的格子区、还会与本屏那层叠成更深的暗斑，改由
        // renderDisconnectedOverlay 按格子区铺。（AE2 升级时复核它的 drawFG。）

        this.painter.drawForegroundRows(guiGraphics, tableFrame());
        this.painter.drawSlotOverlays(guiGraphics, slotFrame());

        // 断开时只压格子区：17 格 × 18 = 306 宽（含左右格框各 1px；实机确认只盖 304 时框线会露出 1px），
        // 高随可见行数走（两者都跟着终端尺寸变）。
        renderDisconnectedOverlay(guiGraphics, LIST_X, this.headerHeight,
                this.listWidth, this.visibleRows * this.rowHeight);
    }

    private java.util.Map<String, CellHostListPayload.HostGroup> driveLookup() {
        return renderedDriveLookup;
    }

    // ---- 交互 ----

    /** 鼠标落在第几行；表格外返回 -1。 */
    private int rowIndexAt(double mouseX, double mouseY) {
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

    private int columnAt(double mouseX) {
        return ((int) mouseX - leftPos - LIST_X) / this.cellSize;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int rowIndex = rowIndexAt(mouseX, mouseY);
        if (rowIndex < 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (rows.get(rowIndex) instanceof CellTableRows.DriveRow row) {
            var entry = CellTablePainter.entryAt(row, columnAt(mouseX));
            if (entry != null) {
                if (entry.isLeader()) {
                    onLeaderClick(entry, button);
                } else {
                    onCellClick(entry, button);
                }
            }
            // 行尾的余量与格里的空白：一律吃掉这一下——下面压着的是 AE2 的物品网格槽位，穿透过去会误触。
            return true;
        }
        return true;
    }

    /**
     * 首格（一个首格单）的四种手势：左键钉住、右键选中、中键改优先级。
     *
     * <p>左键与右键的分工：左键是「钉住」——世界里画虹色负尺寸方框 + 虹色直线，用于找到它，关掉终端也还在，
     * 再左键一次取消；右键是「选中」——存元件、移元件的目标，世界里不画，提示就是表格那圈黄色描边，
     * 关屏即忘。</p>
     */
    private void onLeaderClick(CellTableRows.TableCell entry, int button) {
        if (button == 0) {
            // 左键：钉住/取消这一台（世界里画虹色方框与连线，关掉界面也还在），并在聊天栏报一次维度与坐标。
            CellTablePainter.togglePinnedDrive(entry.leaderKey());
            return;
        }
        if (button == 2) {
            // 中键：调这一台自己的存储优先级（不收支持的，服务端会回一句）。左键那段负责圈位置、
            // 右键负责选中，优先级不抢这两个键。
            getMenu().openPriorityGui(entry.leaderKey());
            return;
        }
        if (button != 1) {
            return;
        }
        // 右键：选中/取消这一台——选中的那一台是「存元件进去」与「把元件移过去」的目标。
        if (selectedLeaderKey.equals(entry.leaderKey())) {
            selectedLeaderKey = "";
        } else {
            selectedLeaderKey = entry.leaderKey();
        }
    }

    /** 元件格的取放：左键取到光标 / 空格放入 / Shift 收背包，右键收进编码槽或移到选中的那台。 */
    private void onCellClick(CellTableRows.TableCell entry, int button) {
        var cell = entry.cell();
        if (button == 1) {
            if (cell.stack().isEmpty()) {
                return;
            }
            if (!selectedLeaderKey.isEmpty() && !selectedLeaderKey.equals(entry.leaderKey())) {
                // 选中了别台：右键＝把这张元件移过去（集元件的手势）。
                getMenu().moveCellToSelectedDrive(cell.hostKey(), cell.hostSlot(), selectedLeaderKey);
            } else {
                // 没选别的（或就选的它自己）：右键＝收进元件编码槽（原地整理的手势）。
                getMenu().moveCellToEncodeSlot(cell.hostKey(), cell.hostSlot());
            }
            return;
        }
        if (button != 0) {
            return;
        }
        if (!cell.stack().isEmpty()) {
            // 有元件：Shift 收背包，否则取到光标（光标上已经拿着东西时不取，免得顶掉手上的）。
            if (hasShiftDown()) {
                getMenu().quickMoveCell(cell.hostKey(), cell.hostSlot());
            } else if (getMenu().getCarried().isEmpty()) {
                getMenu().takeCell(cell.hostKey(), cell.hostSlot());
            }
            return;
        }
        // 空格：只有光标上正好拿着元件时才当放回去的目标。
        if (!getMenu().getCarried().isEmpty()) {
            getMenu().putCell(cell.hostKey(), cell.hostSlot());
        }
    }

    @Override
    protected void renderTooltip(GuiGraphics guiGraphics, int x, int y) {
        // 元件升级槽：空槽给出「这张元件能插哪些卡」的清单（AE2 的升级槽就是这个用法）；
        // 槽里有卡时交回原版，显示卡自己的提示。
        var upgradeSlot = upgradeSlotAt(x, y);
        if (upgradeSlot != null) {
            if (!getMenu().hasWorkbenchCell()) {
                return; // 编码槽里没元件：升级格不接受操作，也就不给提示（与 AE2 元件工作台同一口径）
            }
            if (upgradeSlot.hasItem()) {
                super.renderTooltip(guiGraphics, x, y);
                return;
            }
            var lines = new ArrayList<Component>();
            lines.add(Component.translatable(
                    "gui.ae2_pattern_disk.cell_management_terminal.upgrade_tooltip.title"));
            var cards = getMenu().getEncodeCellUpgrades().insertableCards();
            if (cards.isEmpty()) {
                lines.add(CellTablePainter.hint("gui.ae2_pattern_disk.cell_management_terminal.upgrade_tooltip.none"));
            } else {
                lines.addAll(cards);
            }
            guiGraphics.renderComponentTooltip(font, lines, x, y);
            return;
        }
        int rowIndex = rowIndexAt(x, y);
        if (rowIndex < 0) {
            super.renderTooltip(guiGraphics, x, y);
            return;
        }
        var row = rows.get(rowIndex);
        if (row instanceof CellTableRows.HeaderRow header) {
            var group = driveLookup().get(header.driveKey());
            guiGraphics.renderComponentTooltip(font, List.of(
                    Component.literal(header.name()),
                    Component.translatable("gui.ae2_pattern_disk.cell_management_terminal.drive_tooltip.priority",
                            header.priority()),
                    Component.translatable("gui.ae2_pattern_disk.cell_management_terminal.drive_tooltip.cells",
                            header.cellCount(), group != null ? group.cells().size() : 0)),
                    x, y);
            return;
        }
        var driveRow = (CellTableRows.DriveRow) row;
        var entry = CellTablePainter.entryAt(driveRow, columnAt(x));
        if (entry == null) {
            super.renderTooltip(guiGraphics, x, y);
            return;
        }
        if (entry.isLeader()) {
            guiGraphics.renderComponentTooltip(font,
                    CellTablePainter.leaderTooltip(driveLookup(), driveRow, entry), x, y);
            return;
        }
        ItemStack stack = entry.cell().stack();
        if (!stack.isEmpty()) {
            guiGraphics.renderTooltip(font, CellTablePainter.cellTooltip(stack), stack.getTooltipImage(), x, y);
        }
        // 空槽不再给提示：空槽与「放不进去的位置」在画面上已经分得开（前者盖元件存储槽底、后者盖空白槽位），
        // 再说一句反而像在解释看得出来的事。
    }

    /** 鼠标下的元件升级槽（不在其上则 null）。 */
    private Slot upgradeSlotAt(double mouseX, double mouseY) {
        int localX = (int) mouseX - this.leftPos;
        int localY = (int) mouseY - this.topPos;
        for (var slot : getMenu().slotsOf(AEPatternRegistries.CELL_UPGRADE)) {
            // 命中区跟编码槽那边同一口径：槽框占 x-1..x+17（槽位坐标是 18x18 框里的内容区左上）。
            if (localX >= slot.x - 1 && localX < slot.x + 17 && localY >= slot.y - 1 && localY < slot.y + 17) {
                return slot;
            }
        }
        return null;
    }

    /**
     * 背包里 Shift+左键**存储元件**：选中了驱动器就存进它第一个空位；没选中就交回基类，由菜单按槽位语义
     * 把元件放进元件编码槽（编码槽里的元件 Shift 则回背包）。
     *
     * <p>玩家侧 Shift+左键的整体口径（不进 ME 网络、没去处就提示）在基类
     * {@link AbstractPatternDiskTermScreen} 里，这里只回答「元件有没有自己的去处」。不是元件就交回基类。</p>
     */
    @Override
    protected boolean onPlayerInventoryQuickMove(Slot slot) {
        if (!CellFormats.isStorageCell(slot.getItem())) {
            return false; // 不是存储元件：本屏没它的去处，交给基类
        }
        if (selectedLeaderKey.isEmpty()) {
            // 没有选中的驱动器：不再把这一下静默吞掉。交回基类走标准快捷移动——
            // 背包里的元件进元件编码槽，编码槽里的元件 Shift 回背包。
            return false;
        }
        getMenu().storeCellIntoDrive(selectedLeaderKey, slot.getContainerSlot());
        return true;
    }

    @Override
    protected void slotClicked(@Nullable Slot slot, int slotIdx, int mouseButton, ClickType clickType) {
        // 编码槽里没有元件时，标记格与升级格跟 AE2 元件工作台一样不接受操作（点不出、拖不进）。
        if (slot != null && isSlotInactive(slot)) {
            return;
        }
        // 编码槽里的元件：右键上传到选中的那台驱动器（左键的放入/取出仍照常走槽位那套）。
        if (clickType == ClickType.PICKUP && mouseButton == 1 && slot != null && getMenu().isEncodingSlot(slot)) {
            if (!selectedLeaderKey.isEmpty()) {
                getMenu().storeEncodeCellIntoDrive(selectedLeaderKey);
            }
            return;
        }
        super.slotClicked(slot, slotIdx, mouseButton, clickType);
    }

    /**
     * 这一格当前是不是「看得见但碰不得」：编码槽里没有元件工作台元件时的标记格与升级格。
     *
     * <p>标记格还多两条：元件本身装不下分区（CLEAR_ON_REMOVE）时同样按不可操作算；元件自己给的上限以外的
     * 格（格数比 63 小）也不可操作——那些格根本不会被画，但交互判定与绘制用同一个序号，免得出现
     * 「画不出来却能点」。 </p>
     */
    private boolean isSlotInactive(Slot slot) {
        if (getMenu().isMarkerSlot(slot)) {
            return !getMenu().isMarkerAreaEditable()
                    || CellTablePainter.markerIndex(slot, getMenu().getMarkerRowOffset()) >= getMenu().markerSlotCount();
        }
        return getMenu().isCellUpgradeSlot(slot) && !getMenu().hasWorkbenchCell();
    }

    /**
     * 不可操作的槽不画悬停高亮。
     *
     * <p>AE2 那层高亮的意思是「这一格收得下你手上的东西」；这里点不动、也放不进，亮起来反倒像在骗人。
     * 槽底本身照旧画（与样式文档里那几格对得上），只是不跟着光标亮。</p>
     */
    @Override
    protected void renderSlotHighlight(GuiGraphics guiGraphics, Slot slot, int mouseX, int mouseY, float partialTick) {
        if (isSlotInactive(slot)) {
            return;
        }
        super.renderSlotHighlight(guiGraphics, slot, mouseX, mouseY, partialTick);
    }
}
