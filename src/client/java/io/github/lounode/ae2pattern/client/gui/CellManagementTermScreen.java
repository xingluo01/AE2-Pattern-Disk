package io.github.lounode.ae2pattern.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.ChatFormatting;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import appeng.client.gui.me.common.MEStorageScreen;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.PaletteColor;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.ActionButton;
import appeng.client.gui.widgets.Scrollbar;
import appeng.client.gui.widgets.ToggleButton;
import appeng.client.gui.Icon;
import appeng.client.Point;

import org.anti_ad.mc.ipn.api.IPNPlayerSideOnly;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.config.AEPDConfig;
import appeng.core.localization.GuiText;
import appeng.api.config.ActionItems;
import appeng.api.config.CopyMode;
import appeng.api.config.Settings;
import appeng.api.config.SortDir;

import io.github.lounode.ae2pattern.common.menu.CellDriveScanner;
import io.github.lounode.ae2pattern.common.menu.CellManagementTermMenu;
import io.github.lounode.ae2pattern.client.integration.bookmarks.BookmarkReader;
import io.github.lounode.ae2pattern.client.integration.megacells.MegaCutoffButtonFactory;
import io.github.lounode.ae2pattern.integration.extendedae.VoidCellCompat;
import io.github.lounode.ae2pattern.integration.megacells.MegaCellsCompat;
import io.github.lounode.ae2pattern.client.render.DriveHighlight;
import io.github.lounode.ae2pattern.network.CellHostListPayload;

/**
 * 元件管理终端的屏幕：一张自绘表格（存储优先级标题栏 + 驱动器行），右下角是元件编码槽、升级槽与元件标记区。
 *
 * <p>表格的组织方式与样板磁盘管理终端同源：面板由样式文档里的九条切片拼出（表头带 + 可见行带 + 尾饰带），
 * 行高与列宽都从切片反推，行数由面板总高反算。这里不调 {@code super.drawBG}——AE2 那张底图是固定高度的
 * 一整块贴图，而本表的面板高随终端风格档位变化。</p>
 *
 * <p><b>占用条画在图标内侧底部</b>：一个格子只有 16 像素，行高 18，图标外的余量放不下两根条；而「元件
 * 下方两条」本来就是照 AE2 元件的耐久条口径来的，那条也画在图标内侧底部，所以两处一致。上行是存储
 * 种类占用、下行是存储数量占用。</p>
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

    private final Blitter headerBand;
    private final Blitter footerBand;
    private final Blitter rowTextTop;
    private final Blitter rowSlotsTop;
    private final Blitter rowTextMiddle;
    private final Blitter rowSlotsMiddle;
    private final Blitter rowTextBottom;
    private final Blitter rowSlotsBottom;
    private final Blitter blankCell;

    /**
     * 四张覆盖层。它们来自本模组自己的 states.png（与 AE2 同款的几格，坐标已按本模组那份复制过来），
     * 与贴图上画好的槽框同位置：元件存储/编码槽、元件升级槽、元件标记槽各一张，加一张「放不进去的位置」。
     */
    private static final Blitter CELL_SLOT_OVERLAY = states(240, 80);
    private static final Blitter UPGRADE_SLOT_OVERLAY = states(240, 96);
    private static final Blitter MARKER_SLOT_OVERLAY = states(240, 112);
    /** 空白槽位覆盖层：盖在段尾那些放不进去的列上（这台/这个主机没有这么多格）。 */
    private static final Blitter EMPTY_SLOT_OVERLAY = states(240, 128);

    /** 「标记收藏」的图标（states.png 16,48）。 */
    private static final Blitter ICON_MARK_BOOKMARKS = states(16, 48);

    /** 「物质聚合模式」三档的图标，从左到右：虚空 / 物质球 / 奇点（states.png 一条 48x16）。序号就是模式的枚举序号。
     * 固定三格：EAE 将来加第四档时图标会重复最后一格（提示语仍按枚举名取键，不会指错人）——那时补一格贴图即可。 */
    private static final Blitter[] ICON_VOID_MODES = { states(0, 80), states(16, 80), states(32, 80) };

    /** 工具栏按钮底（18x20，与 AE2 自家那几枚同款），常态 / 光标选中。 */
    private static final ResourceLocation STATES = ResourceLocation
            .parse("ae2_pattern_disk:textures/guis/states.png");
    private static final Blitter BUTTON_BG_NORMAL = Blitter.texture(STATES).src(208, 224, 18, 20);
    private static final Blitter BUTTON_BG_HOVER = Blitter.texture(STATES).src(226, 224, 18, 20);

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

    /** 元件工作台那三个按钮：分区存储 / 清除 / 复制模式。 */
    private ActionButton partitionButton;
    private ActionButton clearButton;
    private ToggleButton copyModeButton;
    /** MEGA Cells 的大宗压缩截断钮；那个模组不在场时为 null（工厂返回 null）。 */
    @Nullable
    private Button megaCutoffButton;
    /** 「标记收藏」与「物质聚合模式」；后者只在编码槽里是 EAE 虚空元件时出现。 */
    private StatesIconButton markBookmarksButton;
    private StatesIconButton voidModeButton;
    /** 上一次给模式按钮设的提示对应哪一档（-1 = 还没设过）；只在它变了时重设，省得每帧造 Component。 */
    private int lastVoidMode = -1;
    /**
     * 上一次算过「截断状态」的编码槽内容快照；null = 还没算过。
     *
     * <p>截断状态只随这张元件变，而元件的任何变化（换元件、我们自己按了按钮被服务端写回、别处改了这个元件）
     * 都会让客户端拿到一份内容不同的栈，所以按**内容**做失效判据就够。不按引用判是因为两者都不可靠：
     * 引用可能被就地改写（那样快照内容会变、引用不会），而快照比较本身很便宜（元件就那几个组件）。</p>
     */
    private ItemStack lastMegaCell;

    private int visibleRows = 6;
    private int scrollOffset;
    private final List<CellTableRows.Row> rows = new ArrayList<>();

    /** 选中的那一个首格单（一台 AE2 / EAE 驱动器，或一个 ECO 主机）的键；空串表示没选。 */
    private String selectedLeaderKey = "";

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

        // 三个按钮在构造器里就建好并上左侧工具栏：AE2 的左工具栏是在开屏时按已注册的按钮排一次位的，
        // 放到 init() 里加会没有按钮底与图标（而且每 resize 一次会再添一套）。与 AE2 元件工作台同一个建法。
        this.partitionButton = new ActionButton(ActionItems.COG, item -> getMenu().partition());
        this.clearButton = new ActionButton(ActionItems.CLOSE, item -> getMenu().clearMarkerArea());
        this.copyModeButton = new ToggleButton(Icon.COPY_MODE_ON, Icon.COPY_MODE_OFF,
                GuiText.CopyMode.text(), GuiText.CopyModeDesc.text(),
                enabled -> getMenu().cycleCopyMode());
        addToLeftToolbar(this.partitionButton);
        addToLeftToolbar(this.clearButton);
        addToLeftToolbar(this.copyModeButton);

        // MEGA Cells 的大宗压缩截断钮：那个模组不在场时工厂返回 null，这一枚就不存在。
        // 与上面三枚同一时机建（工具栏只在开屏时按已注册的按钮排一次位，补在 init() 里不会有按钮底与图标）。
        this.megaCutoffButton = MegaCutoffButtonFactory.create(this::onMegaCutoffPressed);
        if (this.megaCutoffButton != null) {
            addToLeftToolbar(this.megaCutoffButton);
        }

        // 两枚项目自己的图标按钮（states.png）。都做成“图标每帧现取”的那种：模式那枚的图标要跟着当前模式走。
        this.markBookmarksButton = new StatesIconButton(() -> ICON_MARK_BOOKMARKS, this::onMarkBookmarksPressed);
        this.markBookmarksButton.setBackground(BUTTON_BG_NORMAL, BUTTON_BG_HOVER);
        this.markBookmarksButton.setTooltip(List.of(
                Component.translatable("gui.ae2_pattern_disk.cell_management_terminal.mark_bookmarks")));
        addToLeftToolbar(this.markBookmarksButton);

        this.voidModeButton = new StatesIconButton(this::voidModeIcon, this::onVoidModePressed);
        this.voidModeButton.setBackground(BUTTON_BG_NORMAL, BUTTON_BG_HOVER);
        addToLeftToolbar(this.voidModeButton);
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
     */
    private static final List<String> TOOLBAR = List.of(
            "guide", "sortOrder", "terminalStyle", "partition", "clear", "copyMode",
            "markBookmarks", "voidMode", "terminalSwitch", "frequencyCard", "frequencyAutoConnect", "megaCutoff");

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
        if (button == this.partitionButton) {
            return "partition";
        }
        if (button == this.clearButton) {
            return "clear";
        }
        if (button == this.copyModeButton) {
            return "copyMode";
        }
        if (button == this.markBookmarksButton) {
            return "markBookmarks";
        }
        if (button == this.voidModeButton) {
            return "voidMode";
        }
        if (button == this.megaCutoffButton) {
            return "megaCutoff";
        }
        // 「切换终端」那枚由基类按类名认（无线屏挂上来的 AE2WTLib 按钮），这里不必再重复。
        return null;
    }

    /**
     * MEGA 那枚按钮按下：把它看到的元件与「是否反向」报给服务端。
     *
     * <p>反向就是右键（AE2 那套：右键当左键转发）。按钮自己的 {@code isHandlingRightClick()} 是受保护的、
     * 拿不到，所以退一步用 Shift／右键的常规语义取。</p>
     */
    private void onMegaCutoffPressed(Button button) {
        var stack = getMenu().getCellHost().getEncodeCellInventory().getStackInSlot(0);
        if (stack.isEmpty()) {
            return;
        }
        boolean reverse = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        getMenu().cycleCompressionCutoff(reverse);
    }

    /**
     * 「标记收藏」：把 JEI / EMI 收藏夹里的物品追加到编码槽那张元件的标记里。
     *
     * <p>收藏夹只在客户端有，所以读在这儿、写在那边的服务端（见菜单的 {@code markBookmarks}）。</p>
     */
    private void onMarkBookmarksPressed(Button button) {
        var bookmarks = BookmarkReader.read();
        getMenu().markBookmarks(bookmarks.items(), bookmarks.nonItems());
    }

    /** 「物质聚合模式」：往前循环，Shift 往回（与 MEGA 那枚同一手势）。 */
    private void onVoidModePressed(Button button) {
        getMenu().cycleVoidMode(Screen.hasShiftDown());
    }

    /** 模式按钮当前该画哪一档；EAE 不在场或取不到时回到第一档，至少不会画空。 */
    private Blitter voidModeIcon() {
        var stack = getMenu().getCellHost().getEncodeCellInventory().getStackInSlot(0);
        int mode = VoidCellCompat.modeOrdinal(stack);
        return ICON_VOID_MODES[Math.clamp(mode, 0, ICON_VOID_MODES.length - 1)];
    }

    /**
     * 「标记收藏」的显隐：编码槽里得是张带标记区的元件（与「分区存储」「清除」同一个前提），而且真有个
     * 收藏夹模组在读——两个都没有时按下什么都不会发生，不如不显示。
     */
    private void syncMarkBookmarksButton() {
        this.markBookmarksButton.setVisibility(getMenu().isMarkerAreaEditable() && BookmarkReader.available());
    }

    /**
     * 「物质聚合模式」的显隐与提示：只在编码槽里是 EAE 的 ME 虚空元件时出现（模式本身在元件栈的组件上，
     * 不在按钮上，所以这里只负责显隐与把当前档写进提示）。
     */
    private void syncVoidModeButton() {
        var stack = getMenu().getCellHost().getEncodeCellInventory().getStackInSlot(0);
        boolean usable = VoidCellCompat.isVoidCell(stack);
        this.voidModeButton.setVisibility(usable);
        if (!usable) {
            this.lastVoidMode = -1;
            return;
        }
        int mode = VoidCellCompat.modeOrdinal(stack);
        if (mode == this.lastVoidMode) {
            return;
        }
        this.lastVoidMode = mode;
        this.voidModeButton.setTooltip(List.of(
                Component.translatable("gui.ae2_pattern_disk.cell_management_terminal.void_mode"),
                Component.translatable("gui.ae2_pattern_disk.cell_management_terminal.void_mode."
                        + VoidCellCompat.modeKey(mode))));
    }

    /**
     * 每帧把 MEGA 那枚按钮对到编码槽里那张元件上：不是大宗元件、或它没接压缩链时藏起来。
     *
     * <p>与 MEGA 自己在元件工作台上做的一样（它也是每帧读元件再决定显隐与图标），只是那一边读的是元件工作台
     * 的元件槽，这边读的是本终端的元件编码槽。</p>
     */
    private void syncMegaCutoffButton() {
        if (this.megaCutoffButton == null) {
            return;
        }
        var stack = getMenu().getCellHost().getEncodeCellInventory().getStackInSlot(0);
        // 快照没变 = 状态没变。省下每帧那一次 bulkInventoryOf：它会 new 一个 BulkCellInventory
        // （构造里就要读元件组件）再走几次反射。本屏另有每帧工作（行模型重建等），这里只动这一处。
        if (this.lastMegaCell != null && ItemStack.matches(this.lastMegaCell, stack)) {
            return;
        }
        this.lastMegaCell = stack.copy();
        var bulk = MegaCellsCompat.bulkInventoryOf(stack);
        boolean usable = bulk != null && MegaCellsCompat.hasCompressionChain(bulk);
        MegaCutoffButtonFactory.setVisible(this.megaCutoffButton, usable);
        if (usable) {
            MegaCutoffButtonFactory.setItem(this.megaCutoffButton, MegaCellsCompat.cutoffItem(bulk));
        }
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        // 复制模式回显：与元件工作台同一个图标、同一个极性（亮 = 取出元件时清空配置格，即 CLEAR_ON_REMOVE）。
        this.copyModeButton.setState(getMenu().copyMode == CopyMode.CLEAR_ON_REMOVE);
        // MEGA 那枚（装了才有）每帧对一次：显隐与图标跟着编码槽那张元件走。
        syncMegaCutoffButton();
        syncMarkBookmarksButton();
        syncVoidModeButton();
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
        var rebuilt = CellTableRows.build(getMenu().getDriveList(), priorityDescending());
        if (!rows.equals(rebuilt)) {
            rows.clear();
            rows.addAll(rebuilt);
        }
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

    @Override
    public void drawBG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY,
            float partialTicks) {
        // 不调 super.drawBG：理由见类注释（底图是固定高度整块贴图）。
        blit(this.headerBand, guiGraphics, offsetX, offsetY);

        int y = offsetY + this.headerHeight;
        for (int i = 0; i < visibleRows; i++) {
            int rowIndex = scrollOffset + i;
            boolean firstLine = i == 0;
            boolean lastLine = i == visibleRows - 1;
            int rowY = y + i * this.rowHeight;
            boolean slotsRow = rowKindAt(rowIndex) == RowKind.SLOTS;

            blit(selectRowBand(false, firstLine, lastLine), guiGraphics, offsetX, rowY);
            if (slotsRow) {
                blit(selectRowBand(true, firstLine, lastLine), guiGraphics, offsetX, rowY);
            }
            if (rowIndex < rows.size() && rows.get(rowIndex) instanceof CellTableRows.DriveRow driveRow) {
                // 每台 AE2 / EAE 驱动器、每个 ECO 主机都有自己的首格：逐格压一层绿底，选中的那一个描一圈。
                for (int column = 0; column < driveRow.cells().size(); column++) {
                    var entry = driveRow.cells().get(column);
                    if (!entry.isLeader()) {
                        continue;
                    }
                    int frameX = offsetX + LIST_X + column * this.cellSize;
                    guiGraphics.fill(frameX + CELL_INSET, rowY + CELL_INSET,
                            frameX + CELL_INSET + this.contentSize, rowY + CELL_INSET + this.contentSize,
                            DRIVE_SLOT_TINT);
                    // 两个状态的描边颜色不同：右键选中的黄，左键钉住的虹色（世界里只有钉住会画）。
                    if (entry.leaderKey().equals(selectedLeaderKey)) {
                        outlineCell(guiGraphics, frameX, rowY, DRIVE_SELECTED_TINT);
                    } else if (isPinned(entry.leaderKey())) {
                        outlineCell(guiGraphics, frameX, rowY, rainbowTint());
                    }
                }
            }
        }

        blit(this.footerBand, guiGraphics, offsetX,
                offsetY + this.headerHeight + visibleRows * this.rowHeight);
    }

    /** 一行在贴图上该用哪条带：标题栏用文本带，驱动器行用物品带。 */
    private enum RowKind { TEXT, SLOTS }

    private RowKind rowKindAt(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= rows.size()) {
            return RowKind.TEXT;
        }
        return rows.get(rowIndex) instanceof CellTableRows.HeaderRow ? RowKind.TEXT : RowKind.SLOTS;
    }

    private Blitter selectRowBand(boolean slotsRow, boolean firstLine, boolean lastLine) {
        if (slotsRow) {
            return firstLine ? this.rowSlotsTop : lastLine ? this.rowSlotsBottom : this.rowSlotsMiddle;
        }
        return firstLine ? this.rowTextTop : lastLine ? this.rowTextBottom : this.rowTextMiddle;
    }

    /** 本模组那份 states.png 里的一格：16x16，坐标即 UV。 */
    private static Blitter states(int u, int v) {        return Blitter.texture(net.minecraft.resources.ResourceLocation
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
        int y = topRow ? rowY + CELL_INSET : rowY;
        int width = column == CellTableRows.COLUMNS - 1
                ? this.cellSize - EMPTY_CELL_TRIM_RIGHT
                : this.cellSize;
        int height = topRow || bottomRow ? this.cellSize - CELL_INSET : this.cellSize;
        EMPTY_SLOT_OVERLAY.dest(x, y, width, height).blit(guiGraphics);
    }

    /** 把一格的边框描出来（选中/钉住用）：画在框线上，不盖住格内内容，与样板磁盘管理终端同规格。 */
    private void outlineCell(GuiGraphics guiGraphics, int cellX, int rowY, int tint) {
        int size = this.cellSize;
        guiGraphics.fill(cellX, rowY, cellX + size, rowY + 1, tint);
        guiGraphics.fill(cellX, rowY + size - 1, cellX + size, rowY + size, tint);
        guiGraphics.fill(cellX, rowY + 1, cellX + 1, rowY + size - 1, tint);
        guiGraphics.fill(cellX + size - 1, rowY + 1, cellX + size, rowY + size - 1, tint);
    }

    /** 这一台钉着没有（表格描边与世界里那圈框同一个口径）。 */
    private boolean isPinned(String leaderKey) {
        var location = locationOf(leaderKey);
        return location != null && DriveHighlight.isPinned(location.dimension(), location.pos());
    }

    /**
     * 左键：钉住这一台（再左键一次取消）。
     *
     * <p>钉住与右键选中的分工是：右键只是 GUI 里的目标（关屏就没了，世界里不画），钉住才是「在世界里把它
     * 显示出来」——虹色的负尺寸方框 + 一条从眼睛过去的虹色直线，关屏也还在。</p>
     */
    private void togglePinnedDrive(String leaderKey) {
        var location = locationOf(leaderKey);
        if (location == null) {
            return;
        }
        boolean pinned = DriveHighlight.togglePinned(location.dimension(), location.pos());
        reportDrive(location, pinned ? "drive_pinned" : "drive_unpinned");
    }

    /** 在聊天栏报一次「哪一台、在哪、什么状态」。开关在客户端配置里（本地显示的一句话）。 */
    private void reportDrive(HostLocation location, String messageKey) {
        var player = Minecraft.getInstance().player;
        var key = "gui.ae2_pattern_disk.cell_management_terminal." + messageKey;
        if (player != null && AEPDConfig.isMessageShown(key)) {
            player.displayClientMessage(Component.translatable(key,
                    location.dimension().toString(), location.pos().toShortString()), false);
        }
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
    private record HostLocation(ResourceLocation dimension, BlockPos pos) {}

    /** 这一台在哪：坐标串；键不是位置时原样给出键，好让人看出是协议对不上。 */
    private static String describePosition(String hostKey) {
        var location = locationOf(hostKey);
        return location == null ? hostKey : location.pos().toShortString();
    }

    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        super.drawFG(guiGraphics, offsetX, offsetY, mouseX, mouseY);

        // renderLabels 的 pose 已平移到 (leftPos, topPos)，这里必须用裸局部坐标。
        int baseX = LIST_X;
        int baseY = LIST_Y + this.headerHeight;
        var drives = driveLookup();

        for (int i = 0; i < visibleRows; i++) {
            int rowIndex = scrollOffset + i;
            if (rowIndex < 0 || rowIndex >= rows.size()) {
                continue;
            }
            int rowY = baseY + i * this.rowHeight;
            boolean topRow = i == 0;
            boolean bottomRow = i == visibleRows - 1;
            switch (rows.get(rowIndex)) {
                case CellTableRows.HeaderRow header -> drawHeaderRow(guiGraphics, baseX, rowY, header, drives);
                case CellTableRows.DriveRow driveRow -> drawDriveRow(guiGraphics, baseX, rowY, driveRow, drives,
                        topRow, bottomRow);
            }
        }

        drawSlotOverlays(guiGraphics);
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
    private void drawSlotOverlays(GuiGraphics guiGraphics) {
        for (var slot : getMenu().slotsOf(io.github.lounode.ae2pattern.AEPatternRegistries.CELL_ENCODE)) {
            blitSlotOverlay(guiGraphics, slot.x, slot.y, CELL_SLOT_OVERLAY, 1f);
        }
        for (var slot : getMenu().slotsOf(io.github.lounode.ae2pattern.AEPatternRegistries.CELL_UPGRADE)) {
            blitSlotOverlay(guiGraphics, slot.x, slot.y, UPGRADE_SLOT_OVERLAY, 1f);
        }
        // 标记槽：有元件且可编辑时实画，没元件（或在 CLEAR_ON_REMOVE 下元件装不下）时淡到 0.2——
        // 与 AE2 元件工作台同一套显隐口径，槽底不消失，只变淡。
        // 元件自己给上限的那些（格数比 63 小）多出来的格连槽底都不画：那些格永远收不下东西。
        float markerAlpha = getMenu().isMarkerAreaEditable() ? 1f : DISABLED_SLOT_ALPHA;
        int markerSlots = getMenu().markerSlotCount();
        for (int column = 0; column < io.github.lounode.ae2pattern.AEPatternRegistries.CELL_MARKER_COLUMN.length; column++) {
            for (var slot : getMenu().slotsOf(io.github.lounode.ae2pattern.AEPatternRegistries.CELL_MARKER_COLUMN[column])) {
                if (markerIndex(slot) >= markerSlots) {
                    continue;
                }
                blitSlotOverlay(guiGraphics, slot.x, slot.y, MARKER_SLOT_OVERLAY, markerAlpha);
            }
        }
    }

    /** 这一格标记槽在整片 63 格里的序号：窗口内序号 + 已滚过的格数。 */
    private int markerIndex(Slot slot) {
        return slot.getContainerSlot()
                + getMenu().getMarkerRowOffset() * io.github.lounode.ae2pattern.AEPatternRegistries.CELL_MARKER_COLUMN.length;
    }

    /** 标题栏：驱动器物品图标 + 优先级文本 + 这一组有几个元件。 */
    private void drawHeaderRow(GuiGraphics guiGraphics, int baseX, int rowY, CellTableRows.HeaderRow header,
            java.util.Map<String, CellHostListPayload.HostGroup> drives) {
        var group = drives.get(header.driveKey());
        int textX = baseX + 4;
        if (group != null && !group.icon().isEmpty()) {
            // 与样板磁盘终端的标题栏同口径：图标落点 baseX+1、基线 rowY+ROW_TEXT_Y_INSET，文字从图标右边 4 像素起。
            guiGraphics.renderItem(group.icon(), baseX + 1, rowY + ROW_TEXT_Y_INSET);
            textX = baseX + 21;
        }
        String label = Component.translatable("gui.ae2_pattern_disk.cell_management_terminal.drive_header",
                header.name(), header.priority(), header.cellCount()).getString();
        guiGraphics.drawString(font, font.plainSubstrByWidth(label, this.listWidth - (textX - baseX) - 4),
                textX, rowY + ROW_TEXT_Y_INSET + 4, this.textColor, false);
    }

    /**
     * 元件行：首格画那一段的图标（一台驱动器或一个主机），元件格里插了元件的画物品与两根占用条，
     * 空着的真格按 16×16 盖元件存储槽那一层，段尾超出格数的列盖空白槽位。
     *
     * <p>两种「空」是不同的东西：前者是那台上真实存在、可以放元件的格（盖元件槽底），后者是那一台根本没有的
     * 列（盖空白槽位，不接任何点击）。</p>
     */
    private void drawDriveRow(GuiGraphics guiGraphics, int baseX, int rowY, CellTableRows.DriveRow row,
            java.util.Map<String, CellHostListPayload.HostGroup> drives, boolean topRow, boolean bottomRow) {
        var group = drives.get(row.driveKey());
        if (group == null) {
            return;
        }
        for (int column = 0; column < CellTableRows.COLUMNS; column++) {
            int cellX = baseX + column * this.cellSize + CELL_INSET;
            int cellY = rowY + CELL_INSET;
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
                // 真实存在、还能放元件的空格：元件存储槽的槽底，按切片原尺寸 16×16 铺（不拉伸）
                blitSlotOverlay(guiGraphics, cellX, cellY, CELL_SLOT_OVERLAY, 1f);
                continue;
            }
            guiGraphics.renderItem(cell.stack(), cellX, cellY);
            guiGraphics.renderItemDecorations(font, cell.stack(), cellX, cellY);
            drawOccupancyBars(guiGraphics, cellX, cellY, cell);
        }
    }

    /**
     * 图标内侧底部的两根占用条：上行种类、下行数量。
     *
     * <p>底轨先铺满再叠填充，这样空元件也看得见「这两根条在这儿」；两条都与图标同宽，画在物品下缘内侧。</p>
     */
    private void drawOccupancyBars(GuiGraphics guiGraphics, int cellX, int cellY, CellHostListPayload.CellSlot cell) {
        int barY = cellY + this.contentSize - 2;
        guiGraphics.fill(cellX, barY, cellX + this.contentSize, barY + 1, BAR_TRACK_COLOR);
        guiGraphics.fill(cellX, barY + 1, cellX + this.contentSize, barY + 2, BAR_TRACK_COLOR);
        int typeWidth = Math.round(this.contentSize * Math.clamp(cell.typeFill(), 0f, 1f));
        int byteWidth = Math.round(this.contentSize * Math.clamp(cell.byteFill(), 0f, 1f));
        if (typeWidth > 0) {
            guiGraphics.fill(cellX, barY, cellX + typeWidth, barY + 1, TYPE_BAR_COLOR);
        }
        if (byteWidth > 0) {
            guiGraphics.fill(cellX, barY + 1, cellX + byteWidth, barY + 2, BYTE_BAR_COLOR);
        }
    }

    /** 鼠标所在列对应的那一格；超出本行格子数（行尾余量）时返回 null。 */
    @Nullable
    private static CellTableRows.TableCell entryAt(CellTableRows.DriveRow row, int column) {
        return column >= 0 && column < row.cells().size() ? row.cells().get(column) : null;
    }

    private java.util.Map<String, CellHostListPayload.HostGroup> driveLookup() {
        var map = new java.util.HashMap<String, CellHostListPayload.HostGroup>();
        for (var group : getMenu().getDriveList()) {
            map.put(group.key(), group);
        }
        return map;
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
            var entry = entryAt(row, columnAt(mouseX));
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
            togglePinnedDrive(entry.leaderKey());
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
                lines.add(hint("gui.ae2_pattern_disk.cell_management_terminal.upgrade_tooltip.none"));
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
        var entry = entryAt(driveRow, columnAt(x));
        if (entry == null) {
            super.renderTooltip(guiGraphics, x, y);
            return;
        }
        if (entry.isLeader()) {
            guiGraphics.renderComponentTooltip(font, leaderTooltip(driveRow, entry), x, y);
            return;
        }
        ItemStack stack = entry.cell().stack();
        if (!stack.isEmpty()) {
            guiGraphics.renderTooltip(font, cellTooltip(stack), stack.getTooltipImage(), x, y);
        }
        // 空槽不再给提示：空槽与「放不进去的位置」在画面上已经分得开（前者盖元件存储槽底、后者盖空白槽位），
        // 再说一句反而像在解释看得出来的事。
    }

    /** 首格（一个首格单）的提示：哪家的、在哪、几个元件，然后一条手势一行。 */
    private List<Component> leaderTooltip(CellTableRows.DriveRow row, CellTableRows.TableCell entry) {
        var group = driveLookup().get(row.driveKey());
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
        if (entry.leaderKey().equals(selectedLeaderKey)) {
            lines.add(hint("gui.ae2_pattern_disk.cell_management_terminal.drive_tooltip.selected"));
        }
        if (isPinned(entry.leaderKey())) {
            lines.add(hint("gui.ae2_pattern_disk.cell_management_terminal.drive_tooltip.pinned"));
        }
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
     * 元件格的提示：物品自己的提示照旧在前（含 AE2 给存储元件加的自定义组件），本格的四种手势接在后。
     *
     * <p>用 {@code getTooltipFromItem} 取物品提示而不是自己拼名字：元件的信息比一个名字多得多。</p>
     */
    private List<Component> cellTooltip(ItemStack stack) {
        var lines = new ArrayList<Component>(Screen.getTooltipFromItem(Minecraft.getInstance(), stack));
        lines.add(hint("gui.ae2_pattern_disk.cell_management_terminal.cell_tooltip.take"));
        lines.add(hint("gui.ae2_pattern_disk.cell_management_terminal.cell_tooltip.put"));
        lines.add(hint("gui.ae2_pattern_disk.cell_management_terminal.cell_tooltip.quick_move"));
        lines.add(hint("gui.ae2_pattern_disk.cell_management_terminal.cell_tooltip.to_encode"));
        return lines;
    }

    /** 手势提示行：统一灰色，与物品自身的提示区分开。 */
    private static Component hint(String key) {
        return Component.translatable(key).withStyle(ChatFormatting.GRAY);
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
     * 背包里 Shift+左键**存储元件**：存进「右键选中那台驱动器」的第一个空位。
     *
     * <p>玩家侧 Shift+左键的整体口径（不进 ME 网络、没去处就提示）在基类
     * {@link AbstractPatternDiskTermScreen} 里，这里只回答「元件有没有去处」。不是元件就交回基类。
     */
    @Override
    protected boolean onPlayerInventoryQuickMove(Slot slot) {
        if (!CellDriveScanner.isStorageCell(slot.getItem())) {
            return false; // 不是存储元件：本屏没它的去处，交给基类提示
        }
        if (selectedLeaderKey.isEmpty()) {
            showLocalNotice("gui.ae2_pattern_disk.cell_management_terminal.notice.select_first");
            return true;
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
            if (selectedLeaderKey.isEmpty()) {
                showLocalNotice("gui.ae2_pattern_disk.cell_management_terminal.notice.select_first");
            } else {
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
            return !getMenu().isMarkerAreaEditable() || markerIndex(slot) >= getMenu().markerSlotCount();
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
