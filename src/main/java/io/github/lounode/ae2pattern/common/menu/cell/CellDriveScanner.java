package io.github.lounode.ae2pattern.common.menu.cell;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.LinkedHashMap;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import appeng.api.implementations.blockentities.IChestOrDrive;
import appeng.api.implementations.blockentities.IMEChest;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.IBasicCellItem;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.helpers.IPriorityHost;

import io.github.lounode.ae2pattern.network.CellHostListPayload;

/**
 * 扫出网络里的「能装存储元件的东西」，供元件管理终端列表格用。
 *
 * <p><b>必须在服务端跑</b>：{@link IGridNode#getLevel()} 是 {@code ServerLevel}，而且元件的内容（格子里的
 * ItemStack）只在服务端由方块实体持有。扫描结果打成 {@code CellHostListPayload} 推给客户端。</p>
 *
 * <h2>为什么用 {@code getNodes()} 而不是 {@code getMachines()}</h2>
 *
 * <p>AE2 的 {@code Grid} 把机器登记在 {@code machines.put(owner.getClass(), node)}——键是宿主的<b>具体类</b>，
 * 而且只做精确键查。于是 {@code getMachines(DriveBlockEntity.class)} 找不到子类（ExtendedAE 的
 * {@code TileExDrive} 正是它的子类），{@code getMachines(接口)} 更是恒空。只能遍历节点自己判。</p>
 *
 * <h2>两类格式怎么认</h2>
 *
 * <ul>
 *   <li><b>AE2 标准驱动器格式</b>：{@link IChestOrDrive}——AE2 公开的驱动器契约。本体驱动器与 ExtendedAE 的
 *       扩展驱动器（{@link DriveBlockEntity} 的子类）都在里面，第三方按同一条契约注册的设备不用点名就认得；
 *       格数一律现读，不写死 10/20。逐格物品与写入另问 {@code getInternalInventory()}，理由见
 *       {@link #driveViewOf(Object)}（只按契约上的物品列表会把元件配空，所以读不到逐格物品就不列）。</li>
 *   <li><b>NEO ECO 的各级存储、以及 ECO 青春版的 L1 驱动器</b>：这两个的驱动器类不在我们的编译面里
 *       （ECO 是 compileOnly 且版本地板在动，青春版是纯运行时可选），而且青春版的 L1 驱动器连 ECO 的
 *       {@code ICellHost} 都不实现。所以按<b>方法名探</b>：有 {@code getCellStack()} 就当作单格元件座，
 *       能探到 {@code getStorageController()/getCluster()} 就再读一次存储优先级。</li>
 * </ul>
 *
 * <h2>准入按格式双向收口</h2>
 *
 * <p>「这台是哪一格式」与「这张元件是哪一格式」要对上才写得进去：驱动器格式只收 {@link #isDriveFormatCell}
 * 认得的元件，单格元件座只收 {@link #isMatrixFormatCell} 认得的存储矩阵。两边的写入口都不自带这道闸——
 * AE2 的 {@code setItemDirect} 按定义绕过库存 filter，ECO 那边只有它自己的 {@code isItemValid}。不挡的结果是
 * 矩阵停在 ME 驱动器的格子里、元件停在矩阵座里，而两边的存储 Provider 都不认那张栈。</p>
 *
 * <p>探不到就少一列（优先级显示为 {@code —}），不抛异常、不连坐——与全模组「可选依赖缺失只降级」的
 * 口径一致。</p>
 */
public final class CellDriveScanner {


    /** 读不动的宿主与抛异常的邻居都要留痕，否则「这台为什么不在表里」只能靠猜。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.cell");

    /** 探不到优先级时列上显示的字样。 */
    public static final String PRIORITY_UNKNOWN = "—";

    private CellDriveScanner() {}

    /**
     * 表格里的一组驱动器。
     *
     * @param key      组的身份键（驱动器类型 + 优先级）：表格分组用它
     * @param name     显示名
     * @param icon     驱动器方块物品图标（标题栏与每台驱动器的首格都画它）
     * @param priority 存储优先级；读不到时是 {@link #PRIORITY_UNKNOWN}
     * @param cells    这一组里所有驱动器的格位（稠密，同一台的格连续排在一起）
     * @param priorityValue 同一个优先级的数值形式；读不到时 null（显示口径仍用 {@link #priority}）
     * @param ecoHost 这一组是不是 ECO 的存储主机（否则是 AE2 标准驱动器格式的设备）
     */
    public record DriveEntry(String key, String name, ItemStack icon, String priority,
            List<CellHostListPayload.CellSlot> cells, @Nullable Integer priorityValue, boolean ecoHost) {}

    public static List<DriveEntry> scan(IGrid grid) {
        return scan(grid, true);
    }

    /**
     * 扫一遍网络里的存储驱动器。
     *
     * <p>{@code withUsage=false} 只读结构，供取放操作定位。菜单使用带会话缓存的重载读取占用度。</p>
     */
    public static List<DriveEntry> scan(IGrid grid, boolean withUsage) {
        var usage = withUsage ? new CellUsageCache() : null;
        if (usage != null) usage.beginScan(0, true);
        return scan(grid, usage);
    }

    public static List<DriveEntry> scan(IGrid grid, @Nullable CellUsageCache usage) {
        // 同一种驱动器（方块物品相同）且优先级相同的合成一组：表格的身份是「一种驱动器 + 一个优先级」，
        // 不是某一个方块。组里每格自带宿主键，取放照样精确落到具体那一台。
        var byType = new LinkedHashMap<String, TypeGroup>();
        // ECO 家族的存储子系统：主机才是表上的那个「驱动器」，各个元件座变成它下面的格。
        var bySubsystem = new LinkedHashMap<Object, SubsystemAccumulator>();
        for (IGridNode node : grid.getNodes()) {
            Object owner = node.getOwner();
            if (owner == null) {
                continue;
            }
            try {
                DriveView drive = driveViewOf(owner);
                if (drive != null) {
                    DriveEntry single = driveEntry(owner, drive, usage);
                    byType.computeIfAbsent(typeKeyOf(single), key -> new TypeGroup(single)).merge(single);
                    continue;
                }
                Object subsystem = storageSubsystemOf(owner);
                if (subsystem != null) {
                    bySubsystem.computeIfAbsent(subsystem,
                            key -> new SubsystemAccumulator(key, usage)).merge(owner);
                }
            } catch (RuntimeException broken) {
                // 一台读不动就跳过这一台：不认识的第三方存储方块不该让整张表空掉。
                LOGGER.debug("[cell-mgmt] 宿主读不动，跳过：{}", owner.getClass().getName(), broken);
            }
        }
        var out = new ArrayList<DriveEntry>();
        for (TypeGroup group : byType.values()) {
            out.add(group.toEntry());
        }
        for (var accumulator : bySubsystem.values()) {
            DriveEntry entry = accumulator.toEntry();
            if (entry != null) {
                out.add(entry);
            }
        }
        // 按身份键排序：节点迭代序来自哈希表，不排的话同一张网每次扫出来的顺序可能不同，表格会自发重排、
        // 指纹也跟着变（无谓重推）。
        out.sort(java.util.Comparator.comparing(DriveEntry::key));
        if (usage != null) usage.endScan();
        return out;
    }

    /** 分组的身份：同一种方块 + 同一个优先级。 */
    private static String typeKeyOf(DriveEntry single) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(single.icon().getItem())
                + "@" + single.priority();
    }

    /** 把同类型同优先级的驱动器拼成一组：图标与名字取第一台，格位依次接在后面。 */
    private static final class TypeGroup {
        private final ItemStack icon;
        private final String name;
        private final String priority;
        private final Integer priorityValue;
        private final boolean ecoHost;
        private final String key;
        private final List<CellHostListPayload.CellSlot> cells = new ArrayList<>();

        TypeGroup(DriveEntry first) {
            this.icon = first.icon();
            this.name = first.name();
            this.priority = first.priority();
            this.priorityValue = first.priorityValue();
            this.ecoHost = first.ecoHost();
            this.key = typeKeyOf(first);
        }

        void merge(DriveEntry single) {
            cells.addAll(single.cells());
        }

        DriveEntry toEntry() {
            // 顺序不能靠网格节点的哈希序：同一组里哪台在前、以及「第一个空位」落在哪台，都得稳定。
            // 按宿主键排完，一台驱动器的格自然连在一起，表格就靠这个连续性认出每台驱动器的首格。
            cells.sort(java.util.Comparator
                    .comparing(CellHostListPayload.CellSlot::hostKey)
                    .thenComparingInt(CellHostListPayload.CellSlot::hostSlot));
            return new DriveEntry(key, name, icon, priority, List.copyOf(cells), priorityValue, ecoHost);
        }
    }

    /**
     * 一个「首格单」（一台 AE2 / EAE 驱动器，或 ECO 的一整个存储子系统）的格表（只读结构、不数占用度）；
     * 它不在表里时返回空表。
     *
     * <p>「把元件存进选中的那一台」这类手势靠它反查空位。选中的既可能是驱动器自己（AE2 系，首格单就是一台）
     * 也可能是主机（ECO 系，首格单下挂最多 33 个存储矩阵），而每一格自带它归属的首格键，所以一次扫表
     * 就能把两种都接住，不必各写一条路。</p>
     */
    public static List<CellHostListPayload.CellSlot> slotsOfBlock(IGrid grid, String leaderKey) {
        var cells = new ArrayList<CellHostListPayload.CellSlot>();
        for (DriveEntry entry : scan(grid, false)) {
            for (var cell : entry.cells()) {
                if (cell.leaderKey().equals(leaderKey)) {
                    cells.add(cell);
                }
            }
        }
        return cells;
    }

    /**
     * 一台「AE2 标准驱动器格式」宿主的读写视图。
     *
     * <p>AE2 本体、ExtendedAE 的扩展驱动器与任何实现 {@link IChestOrDrive} 的第三方设备共用这一条路：
     * 格数与元件库存问 AE2 的公开契约（{@code getCellCount()} / {@code getCellInventory(int)}），逐格物品栈
     * 与写入问宿主自己的内部库存。</p>
     */
    record DriveView(IChestOrDrive cellHost, InternalInventory inventory) {

        int cellCount() {
            return cellHost.getCellCount();
        }

        ItemStack stackInSlot(int slot) {
            return inventory.getStackInSlot(slot);
        }

        @Nullable
        MEStorage cellInventory(int slot) {
            return cellHost.getCellInventory(slot);
        }

        /** 这一格收不收这张元件：问宿主自己的过滤（AE2 驱动器那条就是 {@code StorageCells.isCellHandled}）。 */
        boolean accepts(int slot, ItemStack stack) {
            return inventory.isItemValid(slot, stack);
        }

        /** 直写一格的物品栈。AE2 的元件库存会在写之前把这一格原有元件的改动落盘，取件不必另存。 */
        void setStackDirect(int slot, ItemStack stack) {
            inventory.setItemDirect(slot, stack);
        }
    }

    /** 报来离谱格数的设备不当驱动器收：一张被撑爆的表比少一列难受。 */
    private static final int MAX_DRIVE_SLOTS = 256;

    /**
     * 这台机器是不是 AE2 标准驱动器格式；不是、或读不到逐格物品时返回 null（交回单格元件座那条路）。
     *
     * <p>AE2 本体驱动器与 ExtendedAE 的 {@code TileExDrive}（{@link DriveBlockEntity} 的子类）都在这个接口里，
     * 第三方按同一条契约注册的设备因此不用点名就能认出来。为什么还要另问 {@code getInternalInventory()}：
     * 契约上只有 {@code getCellItem(int)}，它只给物品：照它能把表列出来，但取件交还给玩家的是一张没配置
     * 的空白盘（分区、升级卡与元件自己的账本全丢），所以宁可整台不列。</p>
     *
     * <p>格数现读不写死：本体 10 格，EAE 的覆写成 20 格。</p>
     *
     * <p>{@link IMEChest}（ME 箱子那类）实现的虽是同一条契约，库存却把元件格与输入缓存格串在一起，
     * 格序与 {@code getCellCount()} 对不上——照驱动器去写会把元件塞进料口，AE2 自己的漏斗都拿不出
     * 那张盘。它不是驱动器格式，整台不列。</p>
     */
    @Nullable
    static DriveView driveViewOf(Object owner) {
        // ME 箱子那类也挂在这条契约下面（IMEChest 继承它），但它不是驱动器格式，理由见上面的 javadoc。
        if (!(owner instanceof IChestOrDrive cellHost) || owner instanceof IMEChest) {
            return null;
        }
        int cellCount = cellHost.getCellCount();
        if (cellCount <= 0 || cellCount > MAX_DRIVE_SLOTS) {
            return null;
        }
        Object inventory = CellProbe.invoke(owner, "getInternalInventory");
        // 库存长度要与契约格数逐一相等：短了逐格读会越界，长了意味着第 0 格不一定是元件格——按错位的
        // 下标取放会把东西写进别的格子，那是丢东西而不是少一列。
        if (!(inventory instanceof InternalInventory internal) || internal.size() != cellCount) {
            return null;
        }
        return new DriveView(cellHost, internal);
    }

    /**
     * 这台驱动器格式宿主的存储优先级：AE2 与 ExtendedAE 读自己的 {@link IPriorityHost}，
     * 没实现那个接口的新驱动器退回按控制器探；都读不到就是表上的「—」，不硬凑一个 0 出来。
     */
    @Nullable
    private static Integer drivePriority(Object owner) {
        if (owner instanceof IPriorityHost host) {
            return host.getPriority();
        }
        return readStoragePriority(owner);
    }

    /** 一台驱动器格式宿主在表格里的一组：它自己就是一个首格单。 */
    private static DriveEntry driveEntry(Object owner, DriveView drive, @Nullable CellUsageCache usage) {
        String hostKey = keyOf(owner);
        Integer priority = drivePriority(owner);
        return new DriveEntry(hostKey, nameOf(owner), iconOf(owner),
                priority != null ? Integer.toString(priority) : PRIORITY_UNKNOWN,
                driveCells(drive, hostKey, usage), priority, false);
    }

    /**
     * 一台驱动器格式宿主的格表。它自己就是一个首格单，所以首格键就是它的宿主键。
     *
     * <p><b>有意识不按契约上那句「先查 {@code isPowered()} 再提供其它信息」做</b>：那句是给指示灯与视觉
     * 状态的口径，而这张表的正事是把断电驱动器里的元件取回来——元件库存与格位都挂在方块实体上，与
     * 通电无关，照那句做会让离线那台整台消失。代价是离线驱动器那些格的两根占用条都是 0（与空盘同形）。
     * 读不动的那一台由 {@code scan} 的逐台收口跳过。</p>
     */
    private static List<CellHostListPayload.CellSlot> driveCells(DriveView drive, String hostKey,
            @Nullable CellUsageCache usage) {
        var cells = new ArrayList<CellHostListPayload.CellSlot>(drive.cellCount());
        for (int slot = 0; slot < drive.cellCount(); slot++) {
            ItemStack stack = drive.stackInSlot(slot);
            // 空格也占一条：稠密表下「列表下标 == 物理格位」，显示列号才能直接当格位用；
            // 只装有元件的格会在有间隔时错位，取放会落到另一格上。
            cells.add(CellFormats.isStorageCell(stack)
                    ? describeCell(stack, usage != null ? drive.cellInventory(slot) : null, hostKey, slot, hostKey,
                            usage)
                    : emptySlot(hostKey, slot, hostKey));
        }
        return cells;
    }

    /** 一个没插元件的物理格（空格也要占位，并带上它的宿主、宿主内格位与它归属的首格键）。 */
    private static CellHostListPayload.CellSlot emptySlot(String hostKey, int hostSlot, String leaderKey) {
        return new CellHostListPayload.CellSlot(ItemStack.EMPTY, 0f, 0f, hostKey, hostSlot, leaderKey);
    }

    /**
     * 这台机器属于哪个存储子系统（主机）：ECO 的驱动器读自己的控制器，青春版经自己的簇回读主机。
     *
     * <p>返回 null 表示这台机器不是这个家族的存储驱动器；返回它本身表示它就是个单格元件座、没有子系统。</p>
     */
    private static Object storageSubsystemOf(Object owner) {
        Object controller = CellProbe.invoke(owner, "getStorageController");
        if (controller != null) {
            return controller;
        }
        Object cluster = CellProbe.invoke(owner, "getCluster");
        return cluster != null ? CellProbe.invoke(cluster, "getController") : null;
    }

    /** 主机下所有存储矩阵驱动器;的顺序读不出来时退回空表（只丢那个子系统的格，不影响其它驱动器）。 */
    @SuppressWarnings("unchecked")
    private static List<Object> drivesOfSubsystem(Object subsystem) {
        Object cluster = CellProbe.invoke(subsystem, "getCluster");
        Object drives = cluster != null ? CellProbe.invoke(cluster, "getDrives") : null;
        return drives instanceof List<?> list ? (List<Object>) list : List.of();
    }

    /**
     * 一个存储子系统在表格里的一组：主机当图标与名字，整个子系统的元件当格。
     *
     * <p>同一子系统会有多个驱动器、也就多次访问到同一把锁，这里只累积去重。格位与宿主键在构建时一次性取完。</p>
     */
    private static final class SubsystemAccumulator {
        private final Object subsystem;
        private final List<CellHostListPayload.CellSlot> cells = new ArrayList<>();

        SubsystemAccumulator(Object subsystem, @Nullable CellUsageCache usage) {
            this.subsystem = subsystem;
            // 一个主机就是一个首格单：它旗下那些单格元件座只当格，不再各占一个首格。
            String leaderKey = keyOf(subsystem);
            for (Object drive : drivesOfSubsystem(subsystem)) {
                appendCellOf(cells, drive, leaderKey, usage);
            }
        }

        void merge(Object drive) {
            // 子系统里的驱动器在首次建这个累积器时已经全量取过了，重复访问不重复算。
        }

        @Nullable
        DriveEntry toEntry() {
            if (cells.isEmpty() && drivesOfSubsystem(subsystem).isEmpty()) {
                return null;
            }
            // 同上：驱动器在簇里的返回顺序不保证稳定，按宿主键排一次。
            cells.sort(java.util.Comparator
                    .comparing(CellHostListPayload.CellSlot::hostKey)
                    .thenComparingInt(CellHostListPayload.CellSlot::hostSlot));
            return new DriveEntry(keyOf(subsystem), nameOf(subsystem), iconOf(subsystem),
                    storagePriority(subsystem), List.copyOf(cells), readStoragePriority(subsystem), true);
        }
    }

    /**
     * 把一台单格元件座追加成首格单（它的主机）上的一格。
     *
     * <p>宿主是那台元件座自己（不是首格键上的主机）：取放要写的是它，主机的 API 根本不存在。空格也要占一条，
     * 不然它后面所有格位都会前移，点第几列就不是第几格了。</p>
     */
    private static void appendCellOf(List<CellHostListPayload.CellSlot> out, Object drive, String leaderKey,
            @Nullable CellUsageCache usage) {
        String hostKey = keyOf(drive);
        Object cell = CellProbe.invoke(drive, "getCellStack");
        if (!(cell instanceof ItemStack stack) || !CellFormats.isStorageCell(stack)) {
            out.add(emptySlot(hostKey, 0, leaderKey));
            return;
        }
        Object storage = usage != null ? CellHostAccess.asStorage(CellProbe.invoke(drive, "getCellInventory")) : null;
        out.add(describeCell(stack, (MEStorage) storage, hostKey, 0, leaderKey, usage));
    }

    /**
     * 这个物品是不是「存储」元件——两种格式之一。
     *
     * <p>必须过滤：ECO 与青春版的闪存晶阵（计算单元）同样长着 {@code getCellStack()}，不筛就会把计算元件
     * 当成存储元件列出来。判定一律用类型而不是物品 id：AE2 系认 {@link IBasicCellItem} 与它的元存处理器，
     * ECO 系先问注册进来的探针、探针缺席才兜底认名字（可选依赖，不能直接引用那个接口），见
     * {@link #isMatrixFormatCell}。</p>
     *
     * <p>这一条只回答「能不能当存储显示」。往格子里放东西要按格式对上，用
     * {@link #isDriveFormatCell} 与 {@link #isMatrixFormatCell}，见 {@link #insertCell}。</p>
     */
    /**
     * 把一格元件连同它的两根占用条算出来。
     *
     * <p>能算就算，算不出就退化成「只画元件、两根条空着」：第三方元件未必实现
     * {@link IBasicCellItem}，而一根填错的条比一根空条更槽。ECO 系的容量不在 {@link IBasicCellItem} 上，
     * 按它接口上的名字探（{@code getTotalTypes()} / {@code getBytes()}，都是无参）。</p>
     *
     * <p>「无限存储矩阵」这类元件报的总字节是天文数字，占条会一直是空的（已用/总量 ≈ 0），这是对的：
     * 它确实不会满。</p>
     *
     * @param storage  这台驱动器在该格上暴露的存储；为空（未通电、离线）时同样退化成空条
     * @param hostKey  这一格的宿主身份键（取放时用）
     * @param hostSlot 这一格在宿主上的格位
     * @param leaderKey 这一格归属的首格键（一台驱动器或一个主机）
     */
    private static CellHostListPayload.CellSlot describeCell(ItemStack stack, MEStorage storage, String hostKey,
            int hostSlot, String leaderKey, @Nullable CellUsageCache usage) {
        if (usage == null || storage == null) {
            return new CellHostListPayload.CellSlot(stack, 0f, 0f, hostKey, hostSlot, leaderKey);
        }
        long totalTypes = readTotalTypes(stack);
        long totalBytes = readTotalBytes(stack);
        var fill = usage.read(storage, totalTypes, totalBytes);
        return new CellHostListPayload.CellSlot(stack, fill.types(), fill.bytes(), hostKey, hostSlot, leaderKey);
    }

    /** 元件支持的类型总数：AE2 系问接口，ECO 系按方法名探；都读不到算 0。 */
    private static long readTotalTypes(ItemStack stack) {
        if (stack.getItem() instanceof IBasicCellItem basic) {
            return Math.max(0, basic.getTotalTypes(stack));
        }
        Object value = CellProbe.invoke(stack.getItem(), "getTotalTypes");
        return value instanceof Integer types ? Math.max(0, types) : 0;
    }

    /** 元件支持的总字节数：同上。返回类型在 ECO 那边是 long，统一抬成 long 算。 */
    private static long readTotalBytes(ItemStack stack) {
        if (stack.getItem() instanceof IBasicCellItem basic) {
            return Math.max(0, basic.getBytes(stack));
        }
        Object value = CellProbe.invoke(stack.getItem(), "getBytes");
        return value instanceof Long bytes ? Math.max(0, bytes) : 0;
    }

    /** 按身份键找回驱动器本体（世界高亮、取元件这些手势要拿它操作）；找不到返回 null。 */
    @Nullable
    public static Object findOwner(IGrid grid, String key) {
        return CellHostAccess.findOwner(grid, key);
    }

    /** 把指定格位的元件取出来；取不到返回空栈。 */
    public static ItemStack extractCell(Object owner, int slot, AtomicReference<ExtractResult> reason) {
        return CellHostAccess.extractCell(owner, slot, reason);
    }

    /** 这台宿主现在让不让取（无限存储迁移中、矩阵还没成型时不让）。 */
    public static boolean canExtract(Object owner) {
        return CellHostAccess.canExtract(owner);
    }

    /** 这台宿主收不收这张元件（格式闸）；不收时调用方连往返都不该开始。 */
    public static boolean canAcceptCell(Object owner, int slot, ItemStack stack) {
        return CellHostAccess.canAccept(owner, slot, stack);
    }

    /** 把元件放进指定格位；放进去了返回 true。 */
    public static boolean insertCell(Object owner, int slot, ItemStack stack) {
        return CellHostAccess.insert(owner, slot, stack);
    }


    /**
     * 把指定格位的元件从驱动器里取出来。
     *
     * <p>返回值区分四种结果：「引擎明确锁住」与「这一格本来就是空的」在玩家看来是两件事（前者要说清被谁锁住，
     * 后者只是点到了空格），而「格里有东西却取不出来」又比锁住更不可知（探不到原因的引擎、写入口未生效等），
     * 所以这三者各自成态，不能都归到「没取到」。</p>
     */
    public enum ExtractResult {
        /** 取到了。 */
        OK,
        /** 这一格本来就没有元件。 */
        EMPTY,
        /** 格里有东西，驱动器明确说了不让取（ECO 的无限存储迁移/成型期会锁住成员矩阵）。 */
        LOCKED,
        /** 格里有东西，但没取走，而驱动器没说是锁住——写入口没生效或这家引擎的拒收探不到原因。 */
        REFUSED,
    }

    /**
     * 外部（{@code integration/} 层）注册的「存储优先级」适配器。
     *
     * <p>本类不引用任何邻居模组的类型：可选依赖缺席时那个包连类都不会被加载。想让公开契约优先于方法名反射，
     * 就由集成层实现这个接口并回调 {@link #registerPriorityAdapter}；没注册时下面三处继续走反射。</p>
     */
    public interface PriorityAdapter {

        /** 这个对象（或其存储控制器）归不归本适配器管；不管就交给反射。 */
        boolean handles(Object owner);

        /** 读优先级；读不到返回 null。 */
        @Nullable
        Integer read(Object owner);

        /** 写优先级；写不进去返回 false（调用方据此报「只读」，不假装成功）。 */
        boolean write(Object owner, int value);
    }

    /** 由集成层在邻居模组在场时调用一次；重复注册以最后一次为准。 */
    public static void registerPriorityAdapter(PriorityAdapter adapter) {
        CellStoragePriority.register(adapter);
    }

    /** 存储优先级；读不到返回 null（表格那列就是「—」）。 */
    @Nullable
    public static Integer readStoragePriority(Object owner) {
        return CellStoragePriority.read(owner);
    }

    /** 这台设备的优先级写不写得进去。 */
    public static boolean canSetStoragePriority(Object owner) {
        return CellStoragePriority.canSet(owner);
    }

    /** 写优先级；写不进去返回 false（调用方据此报「只读」，不假装成功）。 */
    public static boolean setStoragePriority(Object owner, int value) {
        return CellStoragePriority.write(owner, value);
    }

    /** 这台设备的存储控制器（集成层的适配器要拿它做 {@code instanceof}）。 */
    @Nullable
    public static Object storageController(Object owner) {
        return CellStoragePriority.controller(owner);
    }

    /** 表格里那列优先级显示什么：读不到就是「—」。 */
    private static String storagePriority(Object owner) {
        return CellStoragePriority.display(owner);
    }

    private static String nameOf(Object owner) {
        if (owner instanceof net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
            Component name = blockEntity.getBlockState().getBlock().getName();
            return name.getString();
        }
        return owner.getClass().getSimpleName();
    }

    private static ItemStack iconOf(Object owner) {
        if (owner instanceof net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
            return new ItemStack(blockEntity.getBlockState().getBlock());
        }
        return ItemStack.EMPTY;
    }

    static String keyOf(Object owner) {
        if (owner instanceof net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
            BlockPos pos = blockEntity.getBlockPos();
            String dimension = blockEntity.getLevel() != null
                    ? blockEntity.getLevel().dimension().location().toString()
                    : "?";
            return dimension + "@" + pos.asLong();
        }
        return Integer.toHexString(System.identityHashCode(owner));
    }
}
