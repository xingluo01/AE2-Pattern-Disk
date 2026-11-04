package io.github.lounode.ae2pattern.common.menu.cell;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.CopyMode;
import appeng.core.network.ClientboundPacket;
import appeng.api.networking.IGrid;
import appeng.api.storage.ITerminalHost;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.menu.SlotSemantic;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.slot.AppEngSlot;
import appeng.menu.slot.FakeSlot;
import appeng.util.ConfigInventory;

import org.jetbrains.annotations.Nullable;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.menu.slot.StorageCellSlot;
import io.github.lounode.ae2pattern.common.part.CellManagementTerminalPart;
import io.github.lounode.ae2pattern.network.CellHostListPayload;
import io.github.lounode.ae2pattern.network.CellNoticePayload;
import io.github.lounode.ae2pattern.common.menu.AbstractPatternDiskTermMenu;

/**
 * 元件管理终端的菜单：列出网络里各驱动器的存储元件，右侧标记区编辑编码槽里那个元件的分区。
 *
 * <p><b>两个管理域</b>：{@link CellBulkDomain} 是<b>压缩管理域</b>（只收 ECO 大宗压缩矩阵，按压缩链聚合
 * 去重）；{@link CellMarkDomain} 是<b>标记管理域</b>（收两款奇点元件，按单个键判重）。两域共用同一道准入闸
 * 与同一套取放写回，但集合与判重单位不同，别合并。</p>
 *
 * <p><b>职责边界</b>：本类只管槽位、同步与动作分流。搬运在 {@link CellTermMoves}，分区编辑在
 * {@link EncodeCellEditor}，两个管理域各自在自己的类里，收藏夹在 {@link CellBookmarks}。每个动作的
 * 「客户端发 / 服务端做」分流都留在本类，因为只有这里拿得到 {@code sendClientAction}。</p>
 *
 * <p><b>为什么不继承两个样板终端的菜单</b>：它们的槽位全在构造器里一次性 {@code addSlot}（合成网格、
 * 处理输入输出……），没有可覆写的分槽钩子；而本终端的右下角是元件编码槽，与样板编码区不共存。继承过来只能
 * 靠「建了再删」绕，那会把不存在的槽位留在 {@code menu.slots} 里。所以直接建在 AE2 的 {@code MEStorageMenu} 上。</p>
 *
 * <p><b>升级槽是元件的，不是终端的。</b>宿主（{@link ICellManagementHost}）的 {@code getUpgrades()} 交空，
 * 父类因此一格 {@code UPGRADE} 都不建；面板上那六格是挂在编码槽里那张元件上的 {@link EncodeCellUpgrades}
 * （语义 {@code ae2_pattern_disk:cell_upgrade}）。卡插的是元件本身，元件从编码槽拿走时卡跟着走。</p>
 */
public class CellManagementTermMenu extends AbstractPatternDiskTermMenu {

    public static final MenuType<CellManagementTermMenu> TYPE = MenuTypeBuilder
            .create((id, ip, host) -> new CellManagementTermMenu(id, ip, host), CellManagementTerminalPart.class)
            .buildUnregistered(ResourceLocation.parse("ae2_pattern_disk:cell_management_terminal"));

    /** 标记区每页的行数：贴图上能画下 3 行。 */
    public static final int VISIBLE_MARKER_ROWS = 3;

    /** 标记区总行数。63 = 7 列 &times; 9 行，正好是 AE2 元件的分区上限。 */
    public static final int TOTAL_MARKER_ROWS = 9;

    /** 反馈文案的语言键前缀（这些串只在服务端拼键，未尾化由客户端做）。 */
    static final String NOTICE = "gui.ae2_pattern_disk.cell_management_terminal.notice.";

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory
            .getLogger(CellManagementTermMenu.class);

    private static final String ACTION_SET_MARKER_ROW = "setMarkerRow";
    private static final String ACTION_PARTITION = "partition";
    private static final String ACTION_CLEAR = "clear";
    private static final String ACTION_CYCLE_COPY_MODE = "nextCopyMode";
    private static final String ACTION_MOVE_CELL = "moveCell";
    private static final String ACTION_TAKE_CELL = "takeCell";
    private static final String ACTION_PUT_CELL = "putCell";
    private static final String ACTION_QUICK_MOVE_CELL = "quickMoveCell";
    private static final String ACTION_STORE_CELL = "storeCell";
    private static final String ACTION_MOVE_TO_SELECTED = "moveCellToSelected";
    private static final String ACTION_STORE_ENCODE_CELL = "storeEncodeCell";
    private static final String ACTION_OPEN_PRIORITY = "openPriority";
    private static final String ACTION_CYCLE_COMPRESSION_CUTOFF = "cycleCompressionCutoff";
    private static final String ACTION_CYCLE_ECO_BULK_CUTOFF = "cycleEcoBulkCutoff";
    private static final String ACTION_CONSOLIDATE_ECO_BULK = "consolidateEcoBulkMarkers";
    private static final String ACTION_QUICK_MARK = "quickMark";
    private static final String ACTION_MARK_BOOKMARKS = "markBookmarks";
    private static final String ACTION_CYCLE_VOID_MODE = "cycleVoidMode";

    private final ICellManagementHost host;

    /** 标记窗：21 格槽位挂在它上面，滚动只撼它的起点。 */
    private final SlidingInventoryWindow markerWindow;

    /** 标记区的数据源：编码槽里那张元件的分区（不是部件自己的库存）。 */
    private final EncodeCellPartitions markerPartitions;

    /** 元件升级槽的库存：跟着编码槽里那张元件走，不是跟着终端走。 */
    private final EncodeCellUpgrades encodeCellUpgrades;

    private final EncodeCellEditor editor;
    private final CellTermMoves moves;
    private final CellBulkDomain bulkDomain;
    private final CellQuickMark quickMarker;
    private final CellBookmarks bookmarks;

    /**
     * 标记窗起点（行）：0..{@code TOTAL_MARKER_ROWS - VISIBLE_MARKER_ROWS}。
     *
     * <p>同步给客户端：滚动条位置与滚轮都要有服务端认可的当前值，否则两侧各滚各的。</p>
     */
    @GuiSync(301)
    public int markerRowOffset;

    /**
     * 复制模式，语义与 AE2 元件工作台完全一致。
     *
     * <p>{@code CLEAR_ON_REMOVE}：把元件从编码槽里拿走时它的分区跟着清掉；
     * {@code KEEP_ON_REMOVE}：分区留在元件上，换一张元件继续编辑。</p>
     */
    @GuiSync(302)
    public CopyMode copyMode = CopyMode.CLEAR_ON_REMOVE;

    /** 驱动器清单的收发（屏幕每帧读的那份数据）。 */
    private final CellDriveListSync driveList;

    public CellManagementTermMenu(int id, Inventory playerInventory, CellManagementTerminalPart host) {
        this(TYPE, id, playerInventory, host);
    }

    protected CellManagementTermMenu(MenuType<?> menuType, int id, Inventory playerInventory,
            ICellManagementHost host) {
        super(menuType, id, playerInventory, (ITerminalHost) host);
        this.host = host;
        this.encodeCellUpgrades = new EncodeCellUpgrades(host);
        this.markerPartitions = new EncodeCellPartitions(host);
        this.editor = new EncodeCellEditor(this, markerPartitions);
        this.moves = new CellTermMoves(this);
        this.bulkDomain = new CellBulkDomain(this);
        this.quickMarker = new CellQuickMark(this);
        this.bookmarks = new CellBookmarks(this);
        this.driveList = new CellDriveListSync(this);

        registerClientAction(ACTION_SET_MARKER_ROW, Integer.class, this::setMarkerRowOffset);
        registerClientAction(ACTION_MOVE_CELL, String.class, moves::moveToEncodeSlotEncoded);
        registerClientAction(ACTION_TAKE_CELL, String.class, moves::takeCellEncoded);
        registerClientAction(ACTION_PUT_CELL, String.class, moves::putCellEncoded);
        registerClientAction(ACTION_QUICK_MOVE_CELL, String.class, moves::quickMoveCellEncoded);
        registerClientAction(ACTION_STORE_CELL, String.class, moves::storeCellIntoDriveEncoded);
        registerClientAction(ACTION_MOVE_TO_SELECTED, String.class, moves::moveToSelectedEncoded);
        registerClientAction(ACTION_STORE_ENCODE_CELL, String.class, this::storeEncodeCellIntoDrive);
        registerClientAction(ACTION_OPEN_PRIORITY, String.class, this::openPriorityGui);
        registerClientAction(ACTION_PARTITION, this::partition);
        registerClientAction(ACTION_CLEAR, this::clearMarkerArea);
        registerClientAction(ACTION_CYCLE_COPY_MODE, this::cycleCopyMode);
        registerClientAction(ACTION_CYCLE_COMPRESSION_CUTOFF, Boolean.class, this::cycleCompressionCutoff);
        registerClientAction(ACTION_CYCLE_ECO_BULK_CUTOFF, EcoBulkCutoffRequest.class,
                request -> cycleEcoBulkCutoff(request.slot, request.delta));
        registerClientAction(ACTION_CONSOLIDATE_ECO_BULK, this::consolidateEcoBulkMarkers);
        registerClientAction(ACTION_QUICK_MARK, this::quickMark);
        registerClientAction(ACTION_MARK_BOOKMARKS, String.class, this::applyBookmarks);
        registerClientAction(ACTION_CYCLE_VOID_MODE, Boolean.class, this::cycleVoidMode);

        // 元件编码槽：一格，正在被编辑的那个元件。标记区改的就是它的分区。只收存储元件与存储矩阵。
        this.addSlot(new StorageCellSlot(host.getEncodeCellInventory(), 0), AEPatternRegistries.CELL_ENCODE);

        // 元件升级槽：跟着编码槽里那张元件走，用元件自己的 getUpgrades 实现。
        for (int i = 0; i < EncodeCellUpgrades.SIZE; i++) {
            this.addSlot(new AppEngSlot(encodeCellUpgrades, i), AEPatternRegistries.CELL_UPGRADE);
        }
        // 标记窗：7 列各一语义、每列 3 格，摆出 7x3 的可见窗口。后备 63 格 = 编码槽里那张元件的分区。
        // 槽位用幽灵槽：分区是「标记」，放进去只是记下这个类型，不是把实物扣下。
        this.markerWindow = new SlidingInventoryWindow(markerPartitions,
                AEPatternRegistries.CELL_MARKER_COLUMN.length * VISIBLE_MARKER_ROWS);
        for (int column = 0; column < AEPatternRegistries.CELL_MARKER_COLUMN.length; column++) {
            var semantic = AEPatternRegistries.CELL_MARKER_COLUMN[column];
            for (int row = 0; row < VISIBLE_MARKER_ROWS; row++) {
                this.addSlot(new FakeSlot(markerWindow, column * VISIBLE_MARKER_ROWS + row), semantic);
            }
        }
    }

    /**
     * 不显示 AE2 的 view cell 面板。
     *
     * <p>宿主是 {@code AbstractTerminalPart}，实现 {@code IViewCellStorage}，于是 {@code MEStorageMenu} 会建
     * VIEW_CELL 槽、{@code MEStorageScreen} 会去取名为 {@code viewCells} 的 widget——缺节点时是抛异常，不是
     * 不画。本终端右上角是表格，没有它的位置，所以直接关掉。</p>
     */
    @Override
    protected boolean hideViewCells() {
        return true;
    }

    /** 元件终端没有可合成物品网格，不让父类每拍收集全网配方。 */
    @Override
    protected boolean showsCraftables() {
        return false;
    }

    /**
     * 本屏没有物品网格，所以要掉「配置可见类型」那枚按钮。
     *
     * <p>它出现与否由 AE2 按「菜单主机是不是 {@code KeyTypeSelectionHost}」决定，本终端的主机不是，所以现在
     * 压根不会出现——这一句防着以后主机多实现了那个接口。</p>
     */
    @Override
    public boolean canConfigureTypeFilter() {
        return false;
    }

    // ---- 标记区：滚动与几何 ----

    public int getMarkerRowOffset() {
        return markerRowOffset;
    }

    /**
     * 把标记区滚到某一行。
     *
     * <p>客户端调它时只把意图发上去，不自己改本地值：偏移量是两侧共用的状态，由服务端改完随同步回来。</p>
     */
    public void setMarkerRowOffset(int row) {
        if (!onServerSide()) {
            sendClientAction(ACTION_SET_MARKER_ROW, row);
            return;
        }
        int clamped = Math.max(0, Math.min(row, maxMarkerRowOffset()));
        if (clamped == markerRowOffset) {
            return;
        }
        markerRowOffset = clamped;
        markerWindow.setOffset(clamped * AEPatternRegistries.CELL_MARKER_COLUMN.length);
    }

    /**
     * 编码槽那张元件实际能标记多少格；没元件时 0。
     *
     * <p>不能假定 63：分区库存是元件自己建的。两侧都能算（元件栈是同步的），所以不必另开同步字段。</p>
     */
    public int markerSlotCount() {
        return markerPartitions.slotCount();
    }

    /** 标记窗能滚到的最靠下那一行：按元件实际格数收，格数不够时不让窗口滚到空格上。 */
    public int maxMarkerRowOffset() {
        int columns = AEPatternRegistries.CELL_MARKER_COLUMN.length;
        int rows = (markerSlotCount() + columns - 1) / columns;
        return Math.max(0, Math.min(TOTAL_MARKER_ROWS, rows) - VISIBLE_MARKER_ROWS);
    }

    /** 按语义取槽位：屏幕要用它们的实际位置画覆盖层（位置是 AE2 按样式文档摆的）。 */
    public List<Slot> slotsOf(SlotSemantic semantic) {
        return getSlots(semantic);
    }

    /** 这一格是不是元件编码槽（屏幕用它挡下同一个手势）。 */
    public boolean isEncodingSlot(Slot slot) {
        return slotsOf(AEPatternRegistries.CELL_ENCODE).contains(slot);
    }

    /** 这个槽位是不是分区标记格。 */
    public boolean isMarkerSlot(Slot slot) {
        var semantic = getSlotSemantic(slot);
        for (var marker : AEPatternRegistries.CELL_MARKER_COLUMN) {
            if (semantic == marker) {
                return true;
            }
        }
        return false;
    }

    /** 这个槽位是不是元件升级格（本模组自己建的那批，不是 AE2 的 UPGRADE 语义）。 */
    public boolean isCellUpgradeSlot(Slot slot) {
        return getSlotSemantic(slot) == AEPatternRegistries.CELL_UPGRADE;
    }

    /** 元件升级库存（跟着编码槽那张元件走）：屏幕的模糊卡按钮读它。 */
    public EncodeCellUpgrades getEncodeCellUpgrades() {
        return encodeCellUpgrades;
    }

    /** 供屏幕读取宿主（编码槽、元件分区/升级库存等）。 */
    public ICellManagementHost getCellHost() {
        return host;
    }

    /**
     * 标记区现在能不能编辑。
     *
     * <p>没有元件就没有「谁的分区」这回事：槽底仍画，但按元件工作台的做法淡到 0.2 并拒收点击。</p>
     */
    public boolean isMarkerAreaEditable() {
        if (!hasWorkbenchCell()) {
            return false;
        }
        if (copyMode == CopyMode.KEEP_ON_REMOVE) {
            return true;
        }
        // CLEAR_ON_REMOVE：元件能装多少种就开多少格，多余的格淡化——与 AE2 元件工作台同一判据。
        var config = markerPartitions.config();
        return config != null && config.size() > 0;
    }

    /** 编码槽里那张物品是不是元件工作台元件（标记格与升级格都按这条判据亮不亮）。 */
    public boolean hasWorkbenchCell() {
        ItemStack cell = host.getEncodeCellInventory().getStackInSlot(0);
        return !cell.isEmpty() && cell.getItem() instanceof ICellWorkbenchItem;
    }

    // ---- 点击与快捷移动 ----

    /**
     * 编码槽只挡「会把元件直接挪走」的一类手势：右键取到光标。
     *
     * <p>左键必须放行，它是把元件放进/拿出的正常手势；Shift 点击也放行，没有选中驱动器时编码槽与背包就靠
     * 这一下互相搬（目标槽见 {@link #getQuickMoveDestinationSlots}）。</p>
     */
    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (isEncodingSlotAt(slotId) && clickType == ClickType.PICKUP && button == 1) {
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }

    /**
     * 背包里 Shift+左键存储元件、且没有选中驱动器时，把空的元件编码槽排到快捷移动目标的最前。
     *
     * <p>服务端拿不到客户端「选中了哪台驱动器」这一状态，所以这里只按「编码槽空不空、这东西能不能放」判：
     * AE2 的 {@code mayPlace} 不看槽里有没有东西，编码槽被占时照样会被算成目标，这里只在它空着时抢一次
     * 优先级。反向那一侧由 AE2 父类按玩家侧槽分派，无需补目标。</p>
     */
    @Override
    protected List<Slot> getQuickMoveDestinationSlots(ItemStack stackToMove, boolean fromPlayerSide) {
        var destinations = super.getQuickMoveDestinationSlots(stackToMove, fromPlayerSide);
        if (!fromPlayerSide || !CellDriveScanner.isStorageCell(stackToMove)) {
            return destinations;
        }
        var encodeSlots = slotsOf(AEPatternRegistries.CELL_ENCODE);
        if (encodeSlots.size() != 1) {
            return destinations;
        }
        var encode = encodeSlots.get(0);
        if (!encode.getItem().isEmpty() || !encode.mayPlace(stackToMove)) {
            return destinations;
        }
        var reordered = new ArrayList<Slot>(destinations.size() + 1);
        reordered.add(encode);
        for (var slot : destinations) {
            if (slot != encode) {
                reordered.add(slot);
            }
        }
        return reordered;
    }

    /** 这个槽位号是不是元件编码槽。 */
    private boolean isEncodingSlotAt(int slotId) {
        return slotId >= 0 && slotId < this.slots.size()
                && slotsOf(AEPatternRegistries.CELL_ENCODE).contains(this.slots.get(slotId));
    }

    /**
     * 这次调用是不是发生在服务端。
     *
     * <p>判据与父类的 {@code isClientSide()} 一一对应，这里要的只是「服务端那份菜单在跑吗」。</p>
     */
    private boolean onServerSide() {
        return getPlayer() instanceof net.minecraft.server.level.ServerPlayer;
    }

    // ---- 编码槽那张元件的分区与属性 ----

    /** 「分区存储」：把元件里现有的东西写成它的分区。 */
    public void partition() {
        if (!onServerSide()) {
            sendClientAction(ACTION_PARTITION);
            return;
        }
        editor.partition();
    }

    /** 「清除」：把编码槽里那张元件的分区全部清空。 */
    public void clearMarkerArea() {
        if (!onServerSide()) {
            sendClientAction(ACTION_CLEAR);
            return;
        }
        editor.clear();
    }

    /** 「复制模式」：两档循环。 */
    public void cycleCopyMode() {
        if (!onServerSide()) {
            sendClientAction(ACTION_CYCLE_COPY_MODE);
            return;
        }
        editor.cycleCopyMode();
    }

    /** 切编码槽里那张元件的压缩截断物（MEGA 的大宗元件专有）；参数是「是否反向」。 */
    public void cycleCompressionCutoff(boolean reverse) {
        if (!onServerSide()) {
            sendClientAction(ACTION_CYCLE_COMPRESSION_CUTOFF, reverse);
            return;
        }
        editor.cycleCompressionCutoff(reverse);
    }

    /** 调编码槽里 ECO 大宗元件的压缩截断；参数是「标记槽 + 方向」。 */
    public void cycleEcoBulkCutoff(int markerSlot, int delta) {
        if (delta != -1 && delta != 1) {
            return;
        }
        if (!onServerSide()) {
            sendClientAction(ACTION_CYCLE_ECO_BULK_CUTOFF, new EcoBulkCutoffRequest(markerSlot, delta));
            return;
        }
        editor.cycleEcoBulkCutoff(markerSlot, delta);
    }

    /** 「物质聚合模式」：循环切编码槽那张 EAE 虚空元件的输出。 */
    public void cycleVoidMode(boolean reverse) {
        if (!onServerSide()) {
            sendClientAction(ACTION_CYCLE_VOID_MODE, reverse);
            return;
        }
        editor.cycleVoidMode(reverse);
    }

    /** 批量写入前拿分区库（先复核缓存）。 */
    @Nullable
    ConfigInventory markerConfigForWrite() {
        return editor.configForWrite();
    }

    /** AE2 的客户端动作按 JSON 传参：公开字段 + 无参构造器，不依赖 GSON 对 record 的支持。 */
    public static final class EcoBulkCutoffRequest {
        public int slot;
        public int delta;

        public EcoBulkCutoffRequest() {
        }

        public EcoBulkCutoffRequest(int slot, int delta) {
            this.slot = slot;
            this.delta = delta;
        }
    }

    // ---- 元件搬运：客户端发 / 服务端做 ----

    /** 把驱动器某一格上的元件收进元件编码槽。 */
    public void moveCellToEncodeSlot(String driveKey, int driveSlot) {
        if (!onServerSide()) {
            sendClientAction(ACTION_MOVE_CELL, CellTermMoves.encode(new CellTermMoves.DriveSlot(driveKey, driveSlot)));
            return;
        }
        moves.moveToEncodeSlot(driveKey, driveSlot);
    }

    /** 左键一格元件：光标空就取到光标上，光标有元件就放进这一格。 */
    public void takeCell(String driveKey, int driveSlot) {
        if (!onServerSide()) {
            sendClientAction(ACTION_TAKE_CELL, CellTermMoves.encode(new CellTermMoves.DriveSlot(driveKey, driveSlot)));
            return;
        }
        moves.takeCell(driveKey, driveSlot);
    }

    /** 左键一格空格：把光标上的元件放进去（驱动器拒收就不动）。 */
    public void putCell(String driveKey, int driveSlot) {
        if (!onServerSide()) {
            sendClientAction(ACTION_PUT_CELL, CellTermMoves.encode(new CellTermMoves.DriveSlot(driveKey, driveSlot)));
            return;
        }
        moves.putCell(driveKey, driveSlot);
    }

    /** Shift+左键：把这一格的元件直收进玩家背包。 */
    public void quickMoveCell(String driveKey, int driveSlot) {
        if (!onServerSide()) {
            sendClientAction(ACTION_QUICK_MOVE_CELL,
                    CellTermMoves.encode(new CellTermMoves.DriveSlot(driveKey, driveSlot)));
            return;
        }
        moves.quickMoveCell(driveKey, driveSlot);
    }

    /** Shift+左键背包里的存储元件：存进选中的那台驱动器。 */
    public void storeCellIntoDrive(String leaderKey, int inventorySlot) {
        if (!onServerSide()) {
            sendClientAction(ACTION_STORE_CELL,
                    CellTermMoves.encode(new CellTermMoves.DriveSlot(leaderKey, inventorySlot)));
            return;
        }
        moves.storeCellIntoDrive(leaderKey, inventorySlot);
    }

    /** 右键一个元件格：把它移到选中的那台驱动器。 */
    public void moveCellToSelectedDrive(String hostKey, int hostSlot, String targetLeaderKey) {
        if (!onServerSide()) {
            sendClientAction(ACTION_MOVE_TO_SELECTED,
                    CellTermMoves.encode(new CellTermMoves.Triple(hostKey, hostSlot, targetLeaderKey)));
            return;
        }
        moves.moveToSelectedDrive(hostKey, hostSlot, targetLeaderKey);
    }

    /** 右键元件编码槽里的元件：把它上传到选中的那台驱动器。 */
    public void storeEncodeCellIntoDrive(String leaderKey) {
        if (!onServerSide()) {
            sendClientAction(ACTION_STORE_ENCODE_CELL, leaderKey);
            return;
        }
        moves.storeEncodeCellIntoDrive(leaderKey);
    }

    /** 中键驱动器首格：打开那一台自己的存储优先级界面。 */
    public void openPriorityGui(String leaderKey) {
        if (!onServerSide()) {
            sendClientAction(ACTION_OPEN_PRIORITY, leaderKey);
            return;
        }
        moves.openPriorityGui(leaderKey);
    }

    // ---- 两个管理域与收藏夹 ----

    /** 压缩管理域 · 统一压缩管理：把网络里所有大宗矩阵的标记按链聚合去重。 */
    public void consolidateEcoBulkMarkers() {
        if (!onServerSide()) {
            sendClientAction(ACTION_CONSOLIDATE_ECO_BULK);
            return;
        }
        bulkDomain.consolidate();
    }

    /** 标记管理域 · 快捷标记：把网络里不在域内的物品补写进空标记格。 */
    public void quickMark() {
        if (!onServerSide()) {
            sendClientAction(ACTION_QUICK_MARK);
            return;
        }
        quickMarker.run();
    }

    /** 「标记收藏」：把 JEI / EMI 收藏夹里的物品追加到编码槽那张元件的标记里。 */
    public void markBookmarks(List<ItemStack> items, int skippedNonItems) {
        String payload = CellBookmarks.encode(markerPartitions.config(), items, skippedNonItems);
        sendClientAction(ACTION_MARK_BOOKMARKS, payload);
    }

    // ---- 网络与宿主 ----

    /**
     * 终端所在的那张网；<b>现在连不上时返回 null</b>（动作与列表同步共用这一道门）。
     *
     * <p>判据取 AE2 自家网络交互的那一条 {@code canInteractWithGrid()}，而不是只看节点在不在：
     * <b>无线终端电量耗尽</b>（仍在接入点范围内）与 <b>网络未通电</b> 这两种情况下节点都在，
     * 但此刻对网络的一切读写都该被拦住。</p>
     */
    @Nullable
    IGrid gridOrNull() {
        // 断链（超距 / 未链接 / 终端没电 / 网络未通电）时不分动作与列表同步，一律当作没有网。
        if (!canInteractWithGrid()) {
            LOGGER.debug("[cell-mgmt] 终端此刻连不上网络，本次操作按空网处理");
            return null;
        }
        var gridNode = getGridNode();
        return gridNode != null ? gridNode.getGrid() : null;
    }

    /** 按身份键找驱动器本体（可能在两次点击之间被拆了）；找不到时返回 null，界面上那一行也已消失。 */
    @Nullable
    Object ownerOf(String driveKey) {
        var grid = gridOrNull();
        if (grid == null) {
            return null;
        }
        Object owner = CellDriveScanner.findOwner(grid, driveKey);
        if (owner == null) {
            LOGGER.warn("[cell-mgmt] 找不到驱动器 owner：{}", driveKey);
        }
        return owner;
    }

    /** 格子变动后的收口：落盘 + 立刻重算一次要推给客户端的表与光标。 */
    void afterCellMoved(Object... owners) {
        for (Object owner : owners) {
            if (owner instanceof net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
                blockEntity.setChanged();
            }
        }
        host.markForSave();
        driveList.dirty();
        broadcastChanges();
    }

    /** 客户端报来的动作改动了网络里的元件，下一次同步要重扫。 */
    void markDriveListDirty() {
        driveList.dirty();
    }

    /** 「标记收藏」的服务端入口（注册给 AE2 的那个回调只到得了服务端）。 */
    private void applyBookmarks(String encoded) {
        bookmarks.apply(encoded);
    }

    /** 推包给客户端：AE2 的发送口是 protected，同包的同步类走这一条。 */
    void sendToClient(ClientboundPacket packet) {
        sendPacketToClient(packet);
    }

    /** 给玩家一句聊天栏反馈（无参数）。只在服务端调。 */
    void notice(String key) {
        sendPacketToClient(new CellNoticePayload(key, ""));
    }

    /** 给玩家一句带参数的聊天栏反馈。只在服务端调：客户端手上的菜单没有收发这包的那一线。 */
    void noticeWith(String key, String arg) {
        sendPacketToClient(new CellNoticePayload(key, arg));
    }

    // ---- 驱动器清单同步 ----

    @Override
    public void broadcastChanges() {
        // 父类继续负责链接状态、配置与真实槽位；宿主的展示库存为空，不再同步网络物品。
        super.broadcastChanges();
        markerPartitions.refreshIfStale();
        markerRowOffset = Math.max(0, Math.min(markerRowOffset, maxMarkerRowOffset()));
        markerWindow.setOffset(markerRowOffset * AEPatternRegistries.CELL_MARKER_COLUMN.length);
        if (isServerSide()) {
            driveList.sync();
        }
    }

    /** 客户端侧：服务端最近一次推的驱动器清单。 */
    public List<CellHostListPayload.HostGroup> getDriveList() {
        return driveList.getDriveList();
    }

    /** 客户端侧：接收推送（由 payload 调）。 */
    public void setDriveList(List<CellHostListPayload.HostGroup> groups) {
        driveList.setDriveList(groups);
    }

    public void updateDriveList(List<CellHostListPayload.CellSlot> changes) {
        driveList.updateDriveList(changes);
    }
}
