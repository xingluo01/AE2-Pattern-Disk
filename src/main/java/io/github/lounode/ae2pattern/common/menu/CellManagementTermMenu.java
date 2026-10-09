package io.github.lounode.ae2pattern.common.menu;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.CopyMode;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.ITerminalHost;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.helpers.IPriorityHost;
import appeng.menu.MenuOpener;
import appeng.menu.SlotSemantic;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.implementations.PriorityMenu;
import appeng.menu.locator.MenuHostLocator;
import appeng.menu.locator.MenuLocators;
import appeng.menu.me.common.MEStorageMenu;
import appeng.util.ConfigInventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;

import appeng.menu.slot.AppEngSlot;
import appeng.menu.slot.FakeSlot;

import org.jetbrains.annotations.Nullable;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.part.CellManagementTerminalPart;
import io.github.lounode.ae2pattern.integration.extendedae.VoidCellCompat;
import io.github.lounode.ae2pattern.integration.megacells.MegaCellsCompat;
import io.github.lounode.ae2pattern.network.CellHostListPayload;
import io.github.lounode.ae2pattern.network.CellNoticePayload;

/**
 * 元件管理终端的菜单：列出网络里各驱动器的存储元件，右侧标记区编辑编码槽里那个元件的分区。
 *
 * <p><b>为什么不继承两个样板终端的菜单</b>：它们的槽位全在构造器里一次性 {@code addSlot}（合成网格、
 * 处理输入输出、雕凿输入……），没有可覆写的分槽钩子；而本终端的右下角是元件编码槽，与样板编码区不共存。
 * 继承过来只能靠「建了再删」绕，那会把不存在的槽位留在 {@code menu.slots} 里——{@code slots} 与 AE2 的
 * 语义簿记、原版的槽位镜像不是一个可以随便动的列表，删格必须走 AE2 的 API。所以这里直接建在
 * {@link MEStorageMenu} 上：终端该有的网格访问、链接状态都还在，样板那套一件不带。</p>
 *
 * <p><b>升级槽是元件的，不是终端的。</b>宿主（{@link ICellManagementHost}）的 {@code getUpgrades()}
 * 交空，父类因此一格 {@code UPGRADE} 都不建；面板上那六格是挂在编码槽里那张元件上的
 * {@link EncodeCellUpgrades}（语义 {@code ae2_pattern_disk:cell_upgrade}）。这样卡插的是元件本身，
 * 元件从编码槽拿走时卡跟着走，与 AE2 元件工作台同一个语义。</p>
 */
public class CellManagementTermMenu extends AbstractPatternDiskTermMenu {

    public static final MenuType<CellManagementTermMenu> TYPE = MenuTypeBuilder
            .create((id, ip, host) -> new CellManagementTermMenu(id, ip, host), CellManagementTerminalPart.class)
            .buildUnregistered(ResourceLocation.parse("ae2_pattern_disk:cell_management_terminal"));

    /** 标记区每页的行数：贴图上能画下 3 行，71.5 里先给 3。 */
    public static final int VISIBLE_MARKER_ROWS = 3;

    /** 标记区总行数。63 = 7 列 &times; 9 行，正好是 AE2 元件的分区上限。 */
    public static final int TOTAL_MARKER_ROWS = 9;

    private final ICellManagementHost host;

    /** 诊断用：动作是否真的到达服务端、以及在哪一步被拒（见 {@code withDriveSlot} 与 {@code ownerOf}）。 */
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory
            .getLogger(CellManagementTermMenu.class);

    /** 标记窗：21 格槽位挂在它上面，滚动只撼它的起点。 */
    private final SlidingInventoryWindow markerWindow;

    /** 标记区的数据源：编码槽里那张元件的分区（不是部件自己的库存）。 */
    private final EncodeCellPartitions markerPartitions;

    /**
     * 标记窗起点（行）：0..{@code TOTAL_MARKER_ROWS - VISIBLE_MARKER_ROWS}。
     *
     * <p>同步给客户端：滚动条位置与滚轮都要有服务端认可的当前值，否则两侧各滚各的。</p>
     */
    @GuiSync(301)
    public int markerRowOffset;

    public CellManagementTermMenu(int id, Inventory playerInventory, CellManagementTerminalPart host) {
        this(TYPE, id, playerInventory, host);
    }

    protected CellManagementTermMenu(MenuType<?> menuType, int id, Inventory playerInventory,
            ICellManagementHost host) {
        super(menuType, id, playerInventory, (ITerminalHost) host);
        this.host = host;

        registerClientAction(ACTION_SET_MARKER_ROW, Integer.class, this::setMarkerRowOffset);
        registerClientAction(ACTION_MOVE_CELL, String.class, this::moveCellToEncodeSlotEncoded);
        registerClientAction(ACTION_TAKE_CELL, String.class, this::takeCellEncoded);
        registerClientAction(ACTION_PUT_CELL, String.class, this::putCellEncoded);
        registerClientAction(ACTION_QUICK_MOVE_CELL, String.class, this::quickMoveCellEncoded);
        registerClientAction(ACTION_STORE_CELL, String.class, this::storeCellIntoDriveEncoded);
        registerClientAction(ACTION_MOVE_TO_SELECTED, String.class, this::moveCellToSelectedEncoded);
        registerClientAction(ACTION_STORE_ENCODE_CELL, String.class, this::storeEncodeCellIntoDrive);
        registerClientAction(ACTION_OPEN_PRIORITY, String.class, this::openPriorityGui);
        registerClientAction(ACTION_PARTITION, this::partition);
        registerClientAction(ACTION_CLEAR, this::clearMarkerArea);
        registerClientAction(ACTION_CYCLE_COPY_MODE, this::cycleCopyMode);
        registerClientAction(ACTION_CYCLE_COMPRESSION_CUTOFF, Boolean.class, this::cycleCompressionCutoff);
        registerClientAction(ACTION_MARK_BOOKMARKS, String.class, this::applyBookmarks);
        registerClientAction(ACTION_CYCLE_VOID_MODE, Boolean.class, this::cycleVoidMode);
        this.encodeCellUpgrades = new EncodeCellUpgrades(host);

        // 元件编码槽：一格，正在被编辑的那个元件。标记区改的就是它的分区。
        this.addSlot(new AppEngSlot(host.getEncodeCellInventory(), 0), AEPatternRegistries.CELL_ENCODE);

        // 元件升级槽：跟着编码槽里那张元件走，用元件自己的 getUpgrades 实现，终端物品上不登记任何卡种。
        for (int i = 0; i < EncodeCellUpgrades.SIZE; i++) {
            this.addSlot(new AppEngSlot(encodeCellUpgrades, i), AEPatternRegistries.CELL_UPGRADE);
        }
        // 标记窗：7 列各一语义、每列 3 格，摆出 7x3 的可见窗口。后备 63 格 = 编码槽里那张元件的分区，
        // 滚动时整窗平移。槽位用幽灵槽：分区是「标记」，放进去只是记下这个类型，不是把实物扣下。
        this.markerPartitions = new EncodeCellPartitions(host);
        this.markerWindow = new SlidingInventoryWindow(this.markerPartitions,
                AEPatternRegistries.CELL_MARKER_COLUMN.length * VISIBLE_MARKER_ROWS);
        for (int column = 0; column < AEPatternRegistries.CELL_MARKER_COLUMN.length; column++) {
            var semantic = AEPatternRegistries.CELL_MARKER_COLUMN[column];
            for (int row = 0; row < VISIBLE_MARKER_ROWS; row++) {
                this.addSlot(new FakeSlot(markerWindow, column * VISIBLE_MARKER_ROWS + row), semantic);
            }
        }    }


    /**
     * 不显示 AE2 的 view cell 面板。
     *
     * <p>宿主是 {@code AbstractTerminalPart}，它实现 {@code IViewCellStorage}，于是 {@code MEStorageMenu} 会建
     * VIEW_CELL 槽、{@code MEStorageScreen} 会去取名为 {@code viewCells} 的 widget——而那个 widget 的位置来自样式
     * 文档，缺节点时是抛异常，不是不画。本终端右上角是表格，没有它的位置，所以直接关掉：一行同时解决「widget
     * 必需」与「槽没地方摆、会堆在面板 (0,0)」两件事。</p>
     */
    @Override
    protected boolean hideViewCells() {
        return true;
    }

    /** 标记区滚动请求用的 AE2 客户端动作名。 */
    private static final String ACTION_SET_MARKER_ROW = "setMarkerRow";

    /** 元件工作台那三个按钮的动作名：分区存储 / 清除 / 复制模式。 */
    private static final String ACTION_PARTITION = "partition";
    private static final String ACTION_CLEAR = "clear";
    private static final String ACTION_CYCLE_COPY_MODE = "nextCopyMode";

    /**
     * 复制模式，语义与 AE2 元件工作台完全一致。
     *
     * <p>{@code CLEAR_ON_REMOVE}：把元件从编码槽里拿走时它的分区跟着清掉（分区是「为这张元件配的」）；
     * {@code KEEP_ON_REMOVE}：分区留在元件上，换一张元件继续编辑。</p>
     */
    @GuiSync(302)
    public CopyMode copyMode = CopyMode.CLEAR_ON_REMOVE;

    /** 元件升级槽的库存：跟着编码槽里那张元件走，不是跟着终端走。 */
    private final EncodeCellUpgrades encodeCellUpgrades;

    /** 「把驱动器某一格的元件收进元件编码槽」用的 AE2 客户端动作名。 */
    private static final String ACTION_MOVE_CELL = "moveCell";

    /** 左键把元件取到光标 / 把光标上的元件放进这一格。 */
    private static final String ACTION_TAKE_CELL = "takeCell";
    private static final String ACTION_PUT_CELL = "putCell";

    /** Shift+左键：把元件收进玩家背包（光标上有东西时先收光标那个）。 */
    private static final String ACTION_QUICK_MOVE_CELL = "quickMoveCell";

    /** Shift+左键背包里的存储元件：存进选中的那台驱动器。 */
    private static final String ACTION_STORE_CELL = "storeCell";

    /** 右键一个元件格：把它移到选中的那台驱动器（源格空着也不抱手）。 */
    private static final String ACTION_MOVE_TO_SELECTED = "moveCellToSelected";

    /** 右键元件编码槽里的元件：把它上传到选中的那台驱动器。 */
    private static final String ACTION_STORE_ENCODE_CELL = "storeEncodeCell";

    /** Shift+右键驱动器首格：打开那一台自己的存储优先级界面。 */
    private static final String ACTION_OPEN_PRIORITY = "openPriority";
    /** 切编码槽那张元件的压缩截断物（MEGA 大宗元件专有）；参数是「是否反向」。 */
    private static final String ACTION_CYCLE_COMPRESSION_CUTOFF = "cycleCompressionCutoff";
    private static final String ACTION_MARK_BOOKMARKS = "markBookmarks";
    private static final String ACTION_CYCLE_VOID_MODE = "cycleVoidMode";

    /**
     * 「标记收藏」载荷的字符预算（留出余量）：AE2 客户端动作的字符串上限是 32767，而 {@code writeUtf}
     * 超限就直接抛，所以这里先自己截。
     */
    private static final int MAX_PAYLOAD_CHARS = 16000;

    /** 聊天栏上报的计数上限（载荷来自客户端，夹一下免得出现负数或离谱的数）。 */
    private static final int MAX_REPORTED_COUNT = 1_000_000;

    /** 标记窗当前挂在哪一段后备库存上，供屏幕画滚动条与内容。 */
    public int getMarkerRowOffset() {
        return markerRowOffset;
    }

    /**
     * 把标记区滚到某一行。
     *
     * <p>客户端调它时只把意图发上去，不自己改本地值：偏移量是两侧共用的状态，由服务端改完随同步回来，
     * 免得两边各滚各的。</p>
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
     * <p>不能假定 63：分区库存是元件自己建的，部分元件给的上限比 63 小——屏那边按这个数决定画几格与
     * 能滚多远。两侧都能算（元件栈是同步的），所以不必另开一条同步字段。</p>
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
    public List<net.minecraft.world.inventory.Slot> slotsOf(SlotSemantic semantic) {
        return getSlots(semantic);
    }

    /**
     * 编码槽只挡「会把元件直接挪走」的两类手势：Shift 点击（快捷移动）与右键（取到光标）。
     *
     * <p>左键必须放行：它是把元件放进编码槽/拿出来的正常手势，挡了的话这一格就完全不可交互。
     * 上上轮的写法用 {@code ClickType.PICKUP} 当挡板，而 PICKUP 左右键共用，把左键一起挡住了。</p>
     */
    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (clickIsEncodingSlot(slotId, button, clickType)) {
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }

    /** 编码槽要挡下的手势：Shift 点击（QUICK_MOVE）与右键取件。 */
    private boolean clickIsEncodingSlot(int slotId, int button, ClickType clickType) {
        if (!isEncodingSlotAt(slotId)) {
            return false;
        }
        if (clickType == ClickType.QUICK_MOVE) {
            return true;
        }
        return clickType == ClickType.PICKUP && button == 1;
    }

    /**
     * 这次调用是不是发生在服务端。
     *
     * <p>用玩家真实类型判而不是父类的 {@code isClientSide()}：两者语义相同（AE2 那份就是读玩家所在侧），
     * 但这里要的只是「服务端那份菜单在跑吗」，客户端手上那份的 getPlayer() 永远是 LocalPlayer，
     * 判据与语义一一对应，不必绕着父类约定走。</p>
     */
    private boolean onServerSide() {
        return getPlayer() instanceof net.minecraft.server.level.ServerPlayer;
    }

    /** 这个槽位号是不是元件编码槽。 */
    private boolean isEncodingSlotAt(int slotId) {
        return slotId >= 0 && slotId < this.slots.size()
                && slotsOf(AEPatternRegistries.CELL_ENCODE).contains(this.slots.get(slotId));
    }

    /** 这一格是不是元件编码槽（屏幕用它挡下同一个手势）。 */
    public boolean isEncodingSlot(Slot slot) {
        return slotsOf(AEPatternRegistries.CELL_ENCODE).contains(slot);
    }

    /** 元件升级库存（跟着编码槽那张元件走）：屏幕的模糊卡按钮读它。 */
    public EncodeCellUpgrades getEncodeCellUpgrades() {
        return encodeCellUpgrades;
    }

    /**
     * 标记区现在能不能编辑。
     *
     * <p>没有元件就没有「谁的分区」这回事：槽底仍画，但按元件工作台的做法淡到 0.2 并拒收点击。</p>
     */
    public boolean isMarkerAreaEditable() {
        ItemStack cell = host.getEncodeCellInventory().getStackInSlot(0);
        if (cell.isEmpty() || !(cell.getItem() instanceof ICellWorkbenchItem workbenchItem)) {
            return false;
        }
        if (copyMode == CopyMode.KEEP_ON_REMOVE) {
            return true;
        }
        // CLEAR_ON_REMOVE：元件能装多少种就开多少格，多余的格淡化——与 AE2 元件工作台同一判据。
        return workbenchItem.getConfigInventory(cell).size() > 0;
    }

    /**
     * 编码槽里那张物品是不是元件工作台元件：不是的话标记格与升级格都不接受操作（同 AE2 元件工作台）。
     *
     * <p>编码槽本身没有类型限制，木棍也放得进去；这一层判据必须跟服务端 {@code EncodeCellUpgrades}、
     * {@code EncodeCellPartitions} 一致（它们要的就是 {@link ICellWorkbenchItem}），否则会出现「界面全亮、
     * 点下去什么也没发生」。</p>
     */
    public boolean hasWorkbenchCell() {
        ItemStack cell = host.getEncodeCellInventory().getStackInSlot(0);
        return !cell.isEmpty() && cell.getItem() instanceof ICellWorkbenchItem;
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

    /** 「分区存储」：把元件里现有的东西写成它的分区（元件工作台同款）。 */
    public void partition() {
        if (!onServerSide()) {
            sendClientAction(ACTION_PARTITION);
            return;
        }
        ItemStack cell = host.getEncodeCellInventory().getStackInSlot(0);
        if (cell.isEmpty() || !(cell.getItem() instanceof ICellWorkbenchItem workbenchItem)) {
            return;
        }
        var config = workbenchItem.getConfigInventory(cell);
        var storage = StorageCells.getCellInventory(cell, null);
        if (storage == null) {
            return;
        }
        int index = 0;
        for (var entry : storage.getAvailableStacks()) {
            if (index >= config.size()) {
                break;
            }
            config.setStack(index, new GenericStack(entry.getKey(), 1));
            index++;
        }
        // ConfigInventory 的改动写在元件栈的组件里，元件栈本身得被标记变了才会落盘。
        host.getEncodeCellInventory().sendChangeNotification(0);
        host.markForSave();
        broadcastChanges();
    }

    /** 「清除」：把编码槽里那张元件的分区全部清空。 */
    public void clearMarkerArea() {
        if (!onServerSide()) {
            sendClientAction(ACTION_CLEAR);
            return;
        }
        ItemStack cell = host.getEncodeCellInventory().getStackInSlot(0);
        if (cell.isEmpty() || !(cell.getItem() instanceof ICellWorkbenchItem workbenchItem)) {
            return;
        }
        workbenchItem.getConfigInventory(cell).clear();
        host.markForSave();
        broadcastChanges();
    }

    /** 「复制模式」：两档循环，与元件工作台同一个枚举、同一个含义。 */
    public void cycleCopyMode() {
        if (!onServerSide()) {
            sendClientAction(ACTION_CYCLE_COPY_MODE);
            return;
        }
        copyMode = copyMode == CopyMode.CLEAR_ON_REMOVE ? CopyMode.KEEP_ON_REMOVE : CopyMode.CLEAR_ON_REMOVE;
        host.markForSave();
        broadcastChanges();
    }

    /**
     * 把驱动器某一格上的元件收进元件编码槽：右键表格里的元件就是这个动作。
     *
     * <p>编码槽已经有东西时不换：得先把当前那个放回去，否则另一个元件就被挤没了。格位由客户端报下标，
     * 服务端拿当下的栈再取一次——两次点击之间玩家可能已经把元件挪走了。</p>
     */
    public void moveCellToEncodeSlot(String driveKey, int driveSlot) {
        if (!onServerSide()) {
            // 动作参数用一个串传：AE2 的客户端动作只支持单参数，两个值只能自己编码。
            // 与视图状态那条（applyViewStateEncoded）同一口径，不再多开一种自定义类型的序列化面。
            sendClientAction(ACTION_MOVE_CELL, encodeMoveCell(driveKey, driveSlot));
            return;
        }
        var encodeInv = host.getEncodeCellInventory();
        if (!encodeInv.getStackInSlot(0).isEmpty()) {
            notice(NOTICE + "encode_busy");
            return;
        }
        Object owner = ownerOf(driveKey);
        if (owner == null) {
            return;
        }
        var reason = new java.util.concurrent.atomic.AtomicReference<CellDriveScanner.ExtractResult>();
        ItemStack taken = CellDriveScanner.extractCell(owner, driveSlot, reason);
        if (taken.isEmpty()) {
            notice(NOTICE + takeFailureKey(reason.get()));
            return;
        }
        encodeInv.setItemDirect(0, taken);
        host.markForSave();
        broadcastChanges();
        noticeWith(NOTICE + "moved_to_encode", taken.getHoverName().getString());
    }

    /** 把「驱动器键 + 格位」编成一个串（两者都不含分隔符：键是维度@坐标的数字串）。 */
    private static String encodeMoveCell(String driveKey, int driveSlot) {
        return driveKey + "|" + driveSlot;
    }

    /** 解不开的串一律当没点：协议错配不该把服务端带进越界取值。 */
    private void moveCellToEncodeSlotEncoded(String encoded) {
        int separator = encoded.lastIndexOf('|');
        if (separator <= 0) {
            return;
        }
        try {
            moveCellToEncodeSlot(encoded.substring(0, separator),
                    Integer.parseInt(encoded.substring(separator + 1)));
        } catch (NumberFormatException broken) {
            // 不是我们发的串，丢弃。
        }
    }

    /**
     * 左键一格元件：光标空就把元件取到光标上，光标有元件就把它放进这一格。
     *
     * <p>驱动器里的元件不在菜单的槽位表里（表格是自绘的），所以取放走客户端动作，服务端拿当下的栈再确认
     * 一次；两次点击之间玩家可能已经把那一格挪走了。</p>
     *
     * <p>两个参数是「格位宿主键 + 宿主内格位」，不是行键：ECO 家族的一行代表整个子系统，格位分散在
     * 多台元件座上，拿行键（主机）去找是找不到能写的那一台的。</p>
     */
    public void takeCell(String driveKey, int driveSlot) {
        if (!onServerSide()) {
            sendClientAction(ACTION_TAKE_CELL, encodeMoveCell(driveKey, driveSlot));
            return;
        }
        // 光标上有东西就不取：取来的元件没地方放，硬顶上去会把玩家手上的东西弄丢。
        if (!getCarried().isEmpty()) {
            notice(NOTICE + "carried_not_empty");
            return;
        }
        Object owner = ownerOf(driveKey);
        if (owner == null) {
            return;
        }
        var reason = new java.util.concurrent.atomic.AtomicReference<CellDriveScanner.ExtractResult>();
        ItemStack taken = CellDriveScanner.extractCell(owner, driveSlot, reason);
        if (taken.isEmpty()) {
            notice(NOTICE + takeFailureKey(reason.get()));
            return;
        }
        setCarried(taken);
        afterCellMoved(owner);
        noticeWith(NOTICE + "took", taken.getHoverName().getString());
    }

    /** 左键一格空格：把光标上的元件放进去（驱动器拒收就不动）。 */
    public void putCell(String driveKey, int driveSlot) {
        if (!onServerSide()) {
            sendClientAction(ACTION_PUT_CELL, encodeMoveCell(driveKey, driveSlot));
            return;
        }
        ItemStack carried = getCarried();
        if (carried.isEmpty()) {
            notice(NOTICE + "nothing_carried");
            return;
        }
        Object owner = ownerOf(driveKey);
        if (owner == null) {
            return;
        }
        String name = carried.getHoverName().getString();
        // 只交一份：元件本身不可堆叠，而对方若在内部 copyWithCount(1)（青春版就是这么写的），
        // 整栈交过去会让多出来的份数静默消失。
        if (!CellDriveScanner.insertCell(owner, driveSlot, carried.copyWithCount(1))) {
            // 失败原因就那三种（格位已被占、驱动器不收这类东西、写完被回读判为没生效），对玩家是同一件事。
            noticeWith(NOTICE + "cell_rejected", name);
            return;
        }
        // 光标上还剩更多份时只减一份（当前元件不可堆叠，走不到，但不能在这埋一个吞东西的写法）。
        ItemStack rest = carried.copy();
        rest.shrink(1);
        setCarried(rest);
        afterCellMoved(owner);
        noticeWith(NOTICE + "put", name);
    }

    /**
     * Shift+左键：把这一格的元件直收进玩家背包；光标上有东西时先收光标那个。
     *
     * <p>背包装不下就把取出来的元件放回原格，不丢东西。</p>
     */
    public void quickMoveCell(String driveKey, int driveSlot) {
        if (!onServerSide()) {
            sendClientAction(ACTION_QUICK_MOVE_CELL, encodeMoveCell(driveKey, driveSlot));
            return;
        }
        ItemStack carried = getCarried();
        if (!carried.isEmpty()) {
            String name = carried.getHoverName().getString();
            if (getPlayer().getInventory().add(carried)) {
                setCarried(ItemStack.EMPTY);
                broadcastChanges();
                noticeWith(NOTICE + "put", name);
            } else {
                notice(NOTICE + "inventory_full");
            }
            return;
        }
        Object owner = ownerOf(driveKey);
        if (owner == null) {
            return;
        }
        var reason = new java.util.concurrent.atomic.AtomicReference<CellDriveScanner.ExtractResult>();
        ItemStack taken = CellDriveScanner.extractCell(owner, driveSlot, reason);
        if (taken.isEmpty()) {
            notice(NOTICE + takeFailureKey(reason.get()));
            return;
        }
        if (!getPlayer().getInventory().add(taken)) {
            // 背包满：先试着放回原格；驱动器可能刚被拆、或被拒收，那也不能凭空吞掉——掉在玩家脚下。
            if (!CellDriveScanner.insertCell(owner, driveSlot, taken)) {
                getPlayer().drop(taken, false);
            }
            notice(NOTICE + "inventory_full");
            return;
        }
        afterCellMoved(owner);
        noticeWith(NOTICE + "took", taken.getHoverName().getString());
    }

    /**
     * 把背包里的一张存储元件存进选中那个首格单的第一个空位（Shift+左键背包里的元件就是这个动作）。
     *
     * <p>目标由客户端报出它的首格键（一台 AE2 / EAE 驱动器，或一个 ECO 主机），服务端只认现在还在表里的那一个；
     * 要的是空位的格位与宿主，而它们只向那一个首格单现读。</p>
     */
    public void storeCellIntoDrive(String leaderKey, int inventorySlot) {
        if (!onServerSide()) {
            sendClientAction(ACTION_STORE_CELL, encodeMoveCell(leaderKey, inventorySlot));
            return;
        }
        var inventory = getPlayer().getInventory();
        if (inventorySlot < 0 || inventorySlot >= inventory.getContainerSize()) {
            notice(NOTICE + "slot_changed");
            return;
        }
        ItemStack source = inventory.getItem(inventorySlot);
        if (!CellDriveScanner.isStorageCell(source)) {
            notice(NOTICE + "not_a_cell");
            return;
        }
        var grid = gridOrNull();
        if (grid == null) {
            notice(NOTICE + "grid_offline");
            return;
        }
        var slots = CellDriveScanner.slotsOfBlock(grid, leaderKey);
        if (slots.isEmpty()) {
            notice(NOTICE + "drive_gone");
            return;
        }
        String name = source.getHoverName().getString();
        for (CellHostListPayload.CellSlot slot : slots) {
            if (!slot.stack().isEmpty()) {
                continue;
            }
            Object owner = CellDriveScanner.findOwner(grid, slot.hostKey());
            // 存入的是快照的副本：源栈接下来要减一，不能让驱动器里那份跟着变。
            if (owner == null || !CellDriveScanner.insertCell(owner, slot.hostSlot(), source.copyWithCount(1))) {
                continue;
            }
            ItemStack remaining = source.copy();
            remaining.shrink(1);
            // 背包那份是活的 Inventory 对象，不在槽位包自动同步的范围里，得主动推一次。
            inventory.setItem(inventorySlot, remaining.isEmpty() ? ItemStack.EMPTY : remaining);
            inventory.setChanged();
            afterCellMoved(owner);
            noticeWith(NOTICE + "put", name);
            return;
        }
        notice(NOTICE + "drive_full");
    }

    /**
     * 把一个元件格里的元件移到选中的那台驱动器（右键元件格就是这个动作）。
     *
     * <p>目标先确认有空位才去取源：取出来再发现放不进去，那一下已经把元件抱在手里了，多一次回填就多一次
     * 出错的机会。真到插入失败（格位在两次循环之间被占）时也回填原处，不吞东西。</p>
     */
    public void moveCellToSelectedDrive(String hostKey, int hostSlot, String targetLeaderKey) {
        if (!onServerSide()) {
            sendClientAction(ACTION_MOVE_TO_SELECTED, encodeMoveCell3(hostKey, hostSlot, targetLeaderKey));
            return;
        }
        var grid = gridOrNull();
        if (grid == null) {
            notice(NOTICE + "grid_offline");
            return;
        }
        Object source = CellDriveScanner.findOwner(grid, hostKey);
        if (source == null) {
            notice(NOTICE + "drive_gone");
            return;
        }
        var slots = CellDriveScanner.slotsOfBlock(grid, targetLeaderKey);
        if (slots.isEmpty()) {
            notice(NOTICE + "drive_gone");
            return;
        }
        if (slots.stream().noneMatch(slot -> slot.stack().isEmpty())) {
            notice(NOTICE + "drive_full");
            return;
        }
        var reason = new java.util.concurrent.atomic.AtomicReference<CellDriveScanner.ExtractResult>();
        ItemStack taken = CellDriveScanner.extractCell(source, hostSlot, reason);
        if (taken.isEmpty()) {
            notice(NOTICE + takeFailureKey(reason.get()));
            return;
        }
        for (CellHostListPayload.CellSlot slot : slots) {
            if (!slot.stack().isEmpty()) {
                continue;
            }
            Object owner = CellDriveScanner.findOwner(grid, slot.hostKey());
            if (owner == null || !CellDriveScanner.insertCell(owner, slot.hostSlot(), taken)) {
                continue;
            }
            afterCellMoved(source);
            afterCellMoved(owner);
            noticeWith(NOTICE + "moved", taken.getHoverName().getString());
            return;
        }
        if (!CellDriveScanner.insertCell(source, hostSlot, taken)) {
            // 回填也失败（驱动器可能刚被拆）：不能把元件凭空吞掉，掉在玩家脚下。
            getPlayer().drop(taken, false);
        }
        notice(NOTICE + "move_failed");
    }

    /**
     * 把元件编码槽里那张元件上传到选中的那一台驱动器（右键编码槽就是这个动作）。
     *
     * <p>槽位的取件手势在 {@code clicked} 里被挡着，这条是它的正规出口：元件不是被「拿走」，而是换个地方放。
     * 先放进去、成功了才清编码槽——反过来的话插入失败就把元件弄丢了。</p>
     */
    public void storeEncodeCellIntoDrive(String leaderKey) {
        if (!onServerSide()) {
            sendClientAction(ACTION_STORE_ENCODE_CELL, leaderKey);
            return;
        }
        var encodeInv = host.getEncodeCellInventory();
        ItemStack cell = encodeInv.getStackInSlot(0);
        if (cell.isEmpty()) {
            notice(NOTICE + "encode_empty");
            return;
        }
        var grid = gridOrNull();
        if (grid == null) {
            notice(NOTICE + "grid_offline");
            return;
        }
        var slots = CellDriveScanner.slotsOfBlock(grid, leaderKey);
        if (slots.isEmpty()) {
            notice(NOTICE + "drive_gone");
            return;
        }
        for (CellHostListPayload.CellSlot slot : slots) {
            if (!slot.stack().isEmpty()) {
                continue;
            }
            Object owner = CellDriveScanner.findOwner(grid, slot.hostKey());
            if (owner == null || !CellDriveScanner.insertCell(owner, slot.hostSlot(), cell)) {
                continue;
            }
            encodeInv.setItemDirect(0, ItemStack.EMPTY);
            host.markForSave();
            afterCellMoved(owner);
            noticeWith(NOTICE + "put", cell.getHoverName().getString());
            return;
        }
        notice(NOTICE + "drive_full");
    }

    /** 终端所在的那张网；没连上时返回 null。 */
    private appeng.api.networking.IGrid gridOrNull() {
        var gridNode = getGridNode();
        return gridNode != null ? gridNode.getGrid() : null;
    }

    /**
     * 切编码槽里那张元件的压缩截断物（MEGA 的大宗元件专有）。
     *
     * <p>只对「接在压缩链上且已启用压缩」的元件有意义，而那个判据由客户端每帧现算（它决定按钮显不显示），
     * 所以这里再来一次兜底：不是那种元件就什么都不做，不报错。
     *
     * <p>元件是原地改的（截断物存在元件自己的组件里），改完得让宿主把这一格重新落盘——面板写部件 NBT、
     * 无线写物品组件，两个形态共用这一条。</p>
     */
    public void cycleCompressionCutoff(boolean reverse) {
        if (!onServerSide()) {
            sendClientAction(ACTION_CYCLE_COMPRESSION_CUTOFF, reverse);
            return;
        }
        ItemStack cell = host.getEncodeCellInventory().getStackInSlot(0);
        Object bulk = MegaCellsCompat.bulkInventoryOf(cell);
        if (bulk == null || !MegaCellsCompat.switchCutoff(bulk, reverse)) {
            return;
        }
        host.markForSave();
        broadcastChanges();
    }

    /**
     * 「标记收藏」：把 JEI / EMI 收藏夹里的物品追加到编码槽那张元件的标记里。
     *
     * <p>发之前先<b>探一遍</b>再截：逐条问这张元件的标记容器「有没有哪一格收得下它」——AE2 的
     * {@code isAllowedIn} 是纯查询（自己只说「支持这种键类型 + 槽过滤器放行」），问一遍不会动元件。
     * 探有两层用处：一是元件不支持的条目根本不用发；二是**部分元件自己给标记数量设了上限**，格数不是
     * 真上限，探得到的可落格数才是——不限制的话载荷会白白大一大截（而且客户端动作的字符串上限只有
     * 32767 字符，超了直接抛异常）。</p>
     *
     * <p>写入与最终的「能不能写进去」判定仍在服务端（见 {@link #applyBookmarks}）。</p>
     */
    public void markBookmarks(List<ItemStack> items, int skippedNonItems) {
        var config = markerConfig();
        var allowed = new ArrayList<ItemStack>();
        int rejected = 0;
        for (ItemStack stack : items) {
            if (config != null && accepts(config, stack)) {
                allowed.add(stack);
            } else {
                rejected++;
            }
        }
        int capacity = config == null ? 0 : config.size();
        sendClientAction(ACTION_MARK_BOOKMARKS,
                encodeBookmarks(allowed, skippedNonItems, rejected, capacity));
    }

    /** 编码槽那张元件的标记容器；没有可标记元件时 null。 */
    @Nullable
    private ConfigInventory markerConfig() {
        ItemStack cell = host.getEncodeCellInventory().getStackInSlot(0);
        if (cell.isEmpty() || !(cell.getItem() instanceof ICellWorkbenchItem workbenchItem)) {
            return null;
        }
        return workbenchItem.getConfigInventory(cell);
    }

    /** 这张元件的标记容器里有没有哪一格收得下这个键；纯查询，不改元件。 */
    private static boolean accepts(ConfigInventory config, ItemStack stack) {
        AEKey key = AEItemKey.of(stack);
        if (key == null) {
            return false;
        }
        for (int i = 0; i < config.size(); i++) {
            if (config.isAllowedIn(i, key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 载荷首行是「收藏夹里跳过的非物品项数 | 发之前就注定写不下的项数」，其余每行一个物品栈的 SNBT；
     * 解不开的行直接丢。
     *
     * <p>发之前就得截断：客户端动作的字符串上限是 32767 字符（AE2 的 {@code sendClientAction} 超了就抛
     * 异常），而收藏夹可以大得多——不截断的话点一下就是一个客户端异常。条数按标记格数收，另给一个字节
     * 预算兜底（单件带大组件时一行就能很长）。</p>
     */
    private void applyBookmarks(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return;
        }
        var lines = encoded.split("\n", -1);
        int skippedNonItems;
        int noRoom;
        try {
            var header = lines[0].split("\\|", -1);
            // 夹一下：这两个数只是给玩家看的，但载荷完全来自客户端——不夹就能把计数推成负数。
            skippedNonItems = Math.clamp(Integer.parseInt(header[0]), 0, MAX_REPORTED_COUNT);
            noRoom = header.length > 1 ? Math.clamp(Integer.parseInt(header[1]), 0, MAX_REPORTED_COUNT) : 0;
        } catch (RuntimeException malformed) {
            // 协议错配不该把服务端带进越界取值：宁可什么都不做。
            return;
        }
        ItemStack cell = host.getEncodeCellInventory().getStackInSlot(0);
        if (cell.isEmpty() || !(cell.getItem() instanceof ICellWorkbenchItem workbenchItem)) {
            return;
        }
        var config = workbenchItem.getConfigInventory(cell);
        var marked = new HashSet<AEKey>(config.keySet());
        int written = 0;
        int duplicate = 0;
        int cursor = 0;
        // 元件自己给标记数量设了上限时（部分元件如此），写到量之后每次都会失败——那时不再逐条试。
        boolean exhausted = false;
        for (int line = 1; line < lines.length; line++) {
            ItemStack stack = parseStack(lines[line]);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            AEKey key = AEItemKey.of(stack);
            // 未注册的物品（或者空栈）给 null：标记只能装键，直接塞进去会在下游炸。
            if (key == null) {
                noRoom++;
                continue;
            }
            if (marked.contains(key)) {
                duplicate++;
                continue;
            }
            // 找一格「空着且收这个键」的：收不收由元件说了算（与客户端那遍探测同一判据）。
            int slot = -1;
            if (!exhausted) {
                for (int i = cursor; i < config.size(); i++) {
                    if (config.getKey(i) == null && config.isAllowedIn(i, key)) {
                        slot = i;
                        break;
                    }
                }
            }
            if (slot < 0) {
                noRoom++;
                continue;
            }
            config.setStack(slot, new GenericStack(key, 1));
            if (config.getKey(slot) == null) {
                // 过滤器放行却写不进去：这张元件自己的上限到了（或者不肯收这个键）——不再逐条白试，
                // 剩下的全计「未写入」。
                exhausted = true;
                noRoom++;
                continue;
            }
            marked.add(key);
            cursor = slot + 1;
            written++;
        }
        if (written > 0) {
            // ConfigInventory 的改动写在元件栈的组件里，元件栈本身得被标记变了才会落盘。
            host.getEncodeCellInventory().sendChangeNotification(0);
            host.markForSave();
        }
        broadcastChanges();
        if (getPlayer() instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(Component.translatable(
                    "gui.ae2_pattern_disk.cell_management_terminal.notice.mark_bookmarks",
                    written, duplicate, noRoom, skippedNonItems));
        }
    }

    /** 「物质聚合模式」：循环切编码槽那张 EAE 虚空元件的输出（销毁 / 物质球 / 奇点）。 */
    public void cycleVoidMode(boolean reverse) {
        if (!onServerSide()) {
            sendClientAction(ACTION_CYCLE_VOID_MODE, reverse);
            return;
        }
        ItemStack cell = host.getEncodeCellInventory().getStackInSlot(0);
        if (!VoidCellCompat.cycleMode(cell, reverse)) {
            return;
        }
        host.getEncodeCellInventory().sendChangeNotification(0);
        host.markForSave();
        broadcastChanges();
    }

    /** 载荷编码：条数与字节双重截断，超出的都算进「未写入」，读回去由首行报出。 */
    private static String encodeBookmarks(List<ItemStack> items, int skippedNonItems, int overflow, int capacity) {
        var body = new StringBuilder();
        int written = 0;
        for (ItemStack stack : items) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            var encoded = ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, stack).result().orElse(null);
            if (encoded == null) {
                overflow++;
                continue;
            }
            var snbt = encoded.toString();
            if (written >= capacity || body.length() + snbt.length() + 1 > MAX_PAYLOAD_CHARS) {
                overflow++;
                continue;
            }
            body.append('\n').append(snbt);
            written++;
        }
        return Math.max(0, skippedNonItems) + "|" + overflow + body;
    }

    @Nullable
    private static ItemStack parseStack(String line) {
        try {
            return ItemStack.CODEC.parse(NbtOps.INSTANCE, TagParser.parseTag(line)).result().orElse(null);
        } catch (Exception malformed) {
            return null;
        }
    }

    /**
     * 中键某一台驱动器首格：打开 AE2 自己的存储优先级界面配那一台。
     *
     * <p>能不能单独调优先级由 AE2 的接口判定：只有那一台自己实现了 {@link IPriorityHost}（AE2 与 ExtendedAE
     * 的驱动器都算）才有这个界面。ECO 家族的优先级记在存储控制器上、不在单格元件座上，这里交不出
     * 界面，只说一句不支持。</p>
     *
     * <p>两个形态的界面宿主不同：面板形态指本部件（AE2 那面的返回键会把玩家送回本终端），无线形态用本菜单
     * 自己的 locator——宿主是物品，AE2WTLib 自家的无线终端开子菜单也是这条。locator 解析出来的是宿主类的
     * <b>另一个实例</b>，所以目标键得先写进物品组件（见 {@code WirelessCellManagementTerminalHost}）。</p>
     */
    public void openPriorityGui(String leaderKey) {
        if (!onServerSide()) {
            sendClientAction(ACTION_OPEN_PRIORITY, leaderKey);
            return;
        }
        var grid = gridOrNull();
        if (grid == null) {
            notice(NOTICE + "grid_offline");
            return;
        }
        Object owner = CellDriveScanner.findOwner(grid, leaderKey);
        if (owner == null) {
            notice(NOTICE + "drive_gone");
            return;
        }
        // 只要能**写**优先级就放行：AE2/EAE 驱动器走 IPriorityHost，NEO ECO 各级存储与青春版的 L1 驱动器
        // 走它们存储控制器上的 setStoragePriority(int)。只有读、没有写的不放行（ECO 本体就是）：AE2 那个
        // 优先级界面不读回也不报错，放进去只会让玩家以为改成了。
        if (!CellDriveScanner.canSetStoragePriority(owner)) {
            notice(NOTICE + "priority_unsupported");
            return;
        }
        host.setPriorityTarget(leaderKey);
        MenuHostLocator locator = host instanceof CellManagementTerminalPart part
                ? MenuLocators.forPart(part)
                : getLocator();
        if (!MenuOpener.open(PriorityMenu.TYPE, getPlayer(), locator)) {
            // 宿主解析不出来时 AE2 那边只是静默返回 false（不报错也不开屏），这里补一句，免得玩家以为点坏了。
            notice(NOTICE + "priority_unsupported");
        }
    }

    /** 「宿主键 | 宿主内格位 | 目标首格键」编成一个串（三个值都不含分隔符）。 */
    private static String encodeMoveCell3(String hostKey, int hostSlot, String targetLeaderKey) {
        return hostKey + "|" + hostSlot + "|" + targetLeaderKey;
    }

    private void storeCellIntoDriveEncoded(String encoded) {
        withDriveSlot(encoded, this::storeCellIntoDrive);
    }

    /** 解三段串；解不开就当没点（协议错配不该把服务端带进越界取值）。 */
    private void moveCellToSelectedEncoded(String encoded) {
        int first = encoded.indexOf('|');
        int second = first < 0 ? -1 : encoded.indexOf('|', first + 1);
        if (first <= 0 || second <= 0 || second >= encoded.length() - 1) {
            return;
        }
        try {
            moveCellToSelectedDrive(encoded.substring(0, first),
                    Integer.parseInt(encoded.substring(first + 1, second)), encoded.substring(second + 1));
        } catch (NumberFormatException broken) {
            // 不是我们发的串，丢弃。
        }
    }

    /** 格子变动后的收口：落盘 + 立刻重算一次要推给客户端的表与光标。 */
    private void afterCellMoved(Object owner) {
        if (owner instanceof net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
            blockEntity.setChanged();
        }
        host.markForSave();
        broadcastChanges();
    }

    /** 按身份键找驱动器本体（驱动器可能在两次点击之间被拆了）；找不到时已经把原因告知玩家。 */
    private Object ownerOf(String driveKey) {
        var grid = gridOrNull();
        if (grid == null) {
            notice(NOTICE + "grid_offline");
            return null;
        }
        Object owner = CellDriveScanner.findOwner(grid, driveKey);
        if (owner == null) {
            LOGGER.warn("[cell-mgmt] 找不到驱动器 owner：{}", driveKey);
            notice(NOTICE + "drive_gone");
        }
        return owner;
    }

    /** 反馈文案的语言键前缀（这些串只在服务端拼键，未尾化由客户端做）。 */
    private static final String NOTICE = "gui.ae2_pattern_disk.cell_management_terminal.notice.";

    /** 给玩家一句聊天栏反馈（无参数）。只在服务端调。 */
    private void notice(String key) {
        noticeWith(key, "");
    }

    /** 给玩家一句带参数的聊天栏反馈。只在服务端调：客户端手上的菜单没有收发这包的那一线。 */
    private void noticeWith(String key, String arg) {
        sendPacketToClient(new CellNoticePayload(key, arg));
    }

    /**
     * 取不到元件时该报哪一句。
     *
     * <p>三态分明：格里有东西但被锁住时说锁，格里有东西却不知为何没取走时照实说「取不出来」，只有真的空着
     * 才说「没有元件」——最后这条区分是必要的，否则玩家会以为东西被吞了。</p>
     */
    private static String takeFailureKey(CellDriveScanner.ExtractResult result) {
        return switch (result) {
            case LOCKED -> "cell_locked";
            case REFUSED -> "cell_refused";
            default -> "slot_empty";
        };
    }

    private void takeCellEncoded(String encoded) {
        withDriveSlot(encoded, this::takeCell);
    }

    private void putCellEncoded(String encoded) {
        withDriveSlot(encoded, this::putCell);
    }

    private void quickMoveCellEncoded(String encoded) {
        withDriveSlot(encoded, this::quickMoveCell);
    }

    /** 把「驱动器键|格位」解回两个参数；解不开就当没点（协议错配不该把服务端带进越界取值）。 */
    private void withDriveSlot(String encoded, java.util.function.ObjIntConsumer<String> action) {
        int separator = encoded.lastIndexOf('|');
        if (separator <= 0) {
            LOGGER.warn("[cell-mgmt] 收到解不开的动作参数：{}", encoded);
            return;
        }
        try {
            String driveKey = encoded.substring(0, separator);
            int driveSlot = Integer.parseInt(encoded.substring(separator + 1));
            LOGGER.debug("[cell-mgmt] 收到动作：host={} slot={}", driveKey, driveSlot);
            action.accept(driveKey, driveSlot);
        } catch (NumberFormatException broken) {
            LOGGER.warn("[cell-mgmt] 收到非数字格位：{}", encoded);
        }
    }

    /** 客户端侧：服务端推送的驱动器清单（屏幕每帧读）。 */
    private List<CellHostListPayload.HostGroup> hostList = List.of();

    /** 结构指纹：只含「有什么、在哪」，不含占用度——占用度一直在动，混进指纹等于每拍都重推。 */
    private int sentStructureFingerprint;

    /** 上一次推出去的那张表：结构变化时用它把没变的格的占用度沿用过来（免得占用条空一拍）。 */
    private List<CellHostListPayload.HostGroup> lastPushedGroups = List.of();

    /** 已推过一次没有（首次先推廉价的结构版，占用条下一拍补）。 */
    private boolean sentDriveListOnce;

    /** 距上次算占用度过了多少拍；初始就定在刷新点：首推之后应立即补一次带占用度的。 */
    private int ticksSinceUsageScan = USAGE_REFRESH_TICKS;

    /** 扫描节拍（tick）：表是给人看的，没必要每 tick 重扫一遍元件内容。 */
    private static final int DRIVE_SCAN_INTERVAL_TICKS = 10;

    /** 占用度的兜底刷新间隔（拍）：结构没变也要把两根占用条刷到这个节奏。 */
    private static final int USAGE_REFRESH_TICKS = 60;

    private int driveScanCooldown;

    /**
     * 每 tick 比对一次网络里的驱动器清单，变了就推。
     *
     * <p>驱动器数量有限（一网几十台也算多），指纹只算「哪几台 + 每台里装了什么 + 优先级」，
     * 比每次重扫重发便宜得多。</p>
     */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        // 偏移量由 @GuiSync 同步，两侧都在这里落到窗口上——槽位是共享对象，只有两侧口径一致，
        // 客户端画的与交互的才是同一段。这里再夹一次：元件换成格数更少的那个时，原来的偏移可能超了。
        markerRowOffset = Math.max(0, Math.min(markerRowOffset, maxMarkerRowOffset()));
        markerWindow.setOffset(markerRowOffset * AEPatternRegistries.CELL_MARKER_COLUMN.length);
        if (isServerSide()) {
            syncDriveList();
        }
    }

    /**
     * 按节拍同步表格。
     *
     * <p>分两段扫：<b>结构扫描</b>每拍都做（只读栈，便宜），用它判断表要不要重推；<b>占用度</b>要遍历每个
     * 元件里已存的每一种键，是这条路上最贵的一步，只在结构变了或兜底刷新到点时才算。开屏的首推只带结构，
     * 占用条下一拍补上——这样进终端不会因为一次全量数键而卡一下。</p>
     */
    private void syncDriveList() {
        if (++driveScanCooldown < DRIVE_SCAN_INTERVAL_TICKS) {
            return;
        }
        driveScanCooldown = 0;

        var gridNode = getGridNode();
        var grid = gridNode != null ? gridNode.getGrid() : null;
        if (grid == null) {
            // 终端不在已连通的网上：不推空表，保留客户端手上那份，免得断开一瞬间整张表闪空。
            return;
        }
        var structure = CellDriveScanner.scan(grid, false);
        int structureFingerprint = fingerprint(structure);
        boolean first = !sentDriveListOnce;
        boolean structureChanged = structureFingerprint != sentStructureFingerprint;
        // 计数器按 tick 记：能走到这里就已经过了 DRIVE_SCAN_INTERVAL_TICKS 拍，所以阈值 60 就是 3 秒。
        boolean refreshUsage = !first
                && (ticksSinceUsageScan += DRIVE_SCAN_INTERVAL_TICKS) >= USAGE_REFRESH_TICKS;
        if (!first && !structureChanged && !refreshUsage) {
            return;
        }
        sentDriveListOnce = true;
        sentStructureFingerprint = structureFingerprint;

        // 首推与结构变化都只带结构（立即、便宜）；占用度留给下一拍或兜底点——不在玩家点击的那一拍
        // 去数全网的键，那正是「放进去 / 拿出来会卡一下」的来源。
        boolean withUsage = !first && refreshUsage;
        if (withUsage) {
            ticksSinceUsageScan = 0;
        } else if (structureChanged) {
            ticksSinceUsageScan = USAGE_REFRESH_TICKS;
        }
        if (withUsage) {
            pushDriveList(CellDriveScanner.scan(grid, true));
        } else {
            pushGroups(carryOverUsage(structure));
        }
    }

    private void pushDriveList(List<CellDriveScanner.DriveEntry> entries) {
        pushGroups(toGroups(entries));
    }

    /** 把扫描结果打包成一张表：结构版（占用度全 0）与完整版都走这里。 */
    private static List<CellHostListPayload.HostGroup> toGroups(List<CellDriveScanner.DriveEntry> entries) {
        var groups = new java.util.ArrayList<CellHostListPayload.HostGroup>(entries.size());
        for (var entry : entries) {
            groups.add(new CellHostListPayload.HostGroup(entry.key(), entry.name(), entry.icon(),
                    entry.priority(), List.copyOf(entry.cells())));
        }
        return List.copyOf(groups);
    }

    private void pushGroups(List<CellHostListPayload.HostGroup> groups) {
        lastPushedGroups = groups;
        sendPacketToClient(new CellHostListPayload(groups));
    }

    /**
     * 把上一次推送里同一格的占用度搬到新的结构版上。
     *
     * <p>结构变化那一拍我们只扫结构（不数键，才不会在玩家点击那一下卡住），但两根画着占用度的条会因此空
     * 一拍。同一台驱动器的同一个格位、插的还是同一张元件，占用度就照抄上一次的值；只有真的换了元件的那格
     * 从空开始，等下一拍的完整扫描补上。</p>
     */
    private List<CellHostListPayload.HostGroup> carryOverUsage(List<CellDriveScanner.DriveEntry> entries) {
        var previous = new java.util.HashMap<String, CellHostListPayload.CellSlot>();
        for (CellHostListPayload.HostGroup group : lastPushedGroups) {
            for (CellHostListPayload.CellSlot cell : group.cells()) {
                previous.put(cell.hostKey() + "@" + cell.hostSlot(), cell);
            }
        }
        var groups = new java.util.ArrayList<CellHostListPayload.HostGroup>(entries.size());
        for (var entry : entries) {
            var cells = new java.util.ArrayList<CellHostListPayload.CellSlot>(entry.cells().size());
            for (CellHostListPayload.CellSlot cell : entry.cells()) {
                CellHostListPayload.CellSlot old = previous.get(cell.hostKey() + "@" + cell.hostSlot());
                boolean sameCell = old != null && !cell.stack().isEmpty()
                        && ItemStack.isSameItemSameComponents(old.stack(), cell.stack());
                cells.add(sameCell
                        ? new CellHostListPayload.CellSlot(cell.stack(), old.typeFill(), old.byteFill(),
                                cell.hostKey(), cell.hostSlot(), cell.leaderKey())
                        : cell);
            }
            groups.add(new CellHostListPayload.HostGroup(entry.key(), entry.name(), entry.icon(),
                    entry.priority(), List.copyOf(cells)));
        }
        return List.copyOf(groups);
    }

    /** 表的结构指纹：驱动器身份、名字、优先级、每格的宿主与栈——不含占用度（那两根本来就一直在动）。 */
    private static int fingerprint(List<CellDriveScanner.DriveEntry> entries) {
        int hash = 1;
        for (var entry : entries) {
            hash = 31 * hash + entry.key().hashCode();
            hash = 31 * hash + entry.name().hashCode();
            hash = 31 * hash + entry.priority().hashCode();
            for (CellHostListPayload.CellSlot cell : entry.cells()) {
                ItemStack stack = cell.stack();
                hash = 31 * hash + cell.hostKey().hashCode();
                hash = 31 * hash + cell.hostSlot();
                hash = 31 * hash + ItemStack.hashItemAndComponents(stack);
                hash = 31 * hash + stack.getCount();
            }
        }
        return hash;
    }

    /** 客户端侧：服务端最近一次推的驱动器清单。 */
    public List<CellHostListPayload.HostGroup> getDriveList() {
        return hostList;
    }

    /** 客户端侧：接收推送（由 payload 调）。 */
    public void setDriveList(List<CellHostListPayload.HostGroup> groups) {
        this.hostList = groups;
    }

    /** 供屏幕读取宿主（编码槽、元件分区/升级库存等）。 */
    public ICellManagementHost getCellHost() {
        return host;
    }

    /**
     * 本屏没有物品网格，所以要掉「配置可见类型」那枚按钮。
     *
     * <p>它出现与否由 AE2 按「菜单主机是不是 {@code KeyTypeSelectionHost}」决定，本终端的主机不是，所以现在
     * 压根不会出现——这一句是防着以后：哪天主机多实现了那个接口，它就会自己冒出来，而本屏那枚按钮按下去没有
     * 任何东西可筛。排按钮那一段只认得出「终端设置」与两枚 setting，拦不住它。</p>
     */
    @Override
    public boolean canConfigureTypeFilter() {
        return false;
    }
}
