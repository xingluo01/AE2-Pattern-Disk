package io.github.lounode.ae2pattern.common.menu;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.LinkedHashMap;

import org.jetbrains.annotations.Nullable;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.stacks.KeyCounter;
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
 * <h2>四类来源怎么认</h2>
 *
 * <ul>
 *   <li><b>AE2 本体驱动器与 ExtendedAE 扩展驱动器</b>：{@code instanceof DriveBlockEntity}。EAE 的扩展驱动器
 *       就是这个类的子类，格数由它覆写的 {@link DriveBlockEntity#getCellCount()} 给（本体 10、EAE 20），
 *       所以格数一律动态读，不写死。</li>
 *   <li><b>NEO ECO 的各级存储、以及 ECO 青春版的 L1 驱动器</b>：这两个的驱动器类不在我们的编译面里
 *       （ECO 是 compileOnly 且版本地板在动，青春版是纯运行时可选），而且青春版的 L1 驱动器连 ECO 的
 *       {@code ICellHost} 都不实现。所以按<b>方法名探</b>：有 {@code getCellStack()} 就当作单格元件座，
 *       能探到 {@code getStorageController()/getCluster()} 就再读一次存储优先级。</li>
 * </ul>
 *
 * <p>探不到就少一列（优先级显示为 {@code —}），不抛异常、不连坐——与全模组「可选依赖缺失只降级」的
 * 口径一致。</p>
 */
public final class CellDriveScanner {

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
     */
    public record DriveEntry(String key, String name, ItemStack icon, String priority,
            List<CellHostListPayload.CellSlot> cells) {}

    public static List<DriveEntry> scan(IGrid grid) {
        return scan(grid, true);
    }

    /**
     * 扫一遍网络里的存储驱动器。
     *
     * <p><b>{@code withUsage=false} 时不数占用度</b>：那两个条要遍历元件里已存的每一种键
     * （{@code getAvailableStacks()} 建一次 KeyCounter），是这条路上最贵的一步；只要格位与宿主的手势
     * （存入、移动元件）用不着它，开关一关就只剩读栈与客户端发包前的组装。</p>
     */
    public static List<DriveEntry> scan(IGrid grid, boolean withUsage) {
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
                if (owner instanceof DriveBlockEntity drive) {
                    DriveEntry single = ae2Drive(drive, withUsage);
                    byType.computeIfAbsent(typeKeyOf(single), key -> new TypeGroup(single)).merge(single);
                    continue;
                }
                Object subsystem = storageSubsystemOf(owner);
                if (subsystem != null) {
                    bySubsystem.computeIfAbsent(subsystem,
                            key -> new SubsystemAccumulator(key, withUsage)).merge(owner);
                }
            } catch (RuntimeException broken) {
                // 一台读不动就跳过这一台：不认识的第三方存储方块不该让整张表空掉。
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
        private final String key;
        private final List<CellHostListPayload.CellSlot> cells = new ArrayList<>();

        TypeGroup(DriveEntry first) {
            this.icon = first.icon();
            this.name = first.name();
            this.priority = first.priority();
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
            return new DriveEntry(key, name, icon, priority, List.copyOf(cells));
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

    /** AE2 本体与 ExtendedAE 的驱动器（后者是前者的子类，格数用 getCellCount 现读，不写死 10/20）。 */
    private static DriveEntry ae2Drive(DriveBlockEntity drive, boolean withUsage) {
        String hostKey = keyOf(drive);
        return new DriveEntry(hostKey, nameOf(drive), iconOf(drive),
                Integer.toString(drive.getPriority()), driveCells(drive, hostKey, withUsage));
    }

    /**
     * 一台 AE2 系驱动器的格表。那一台驱动器自己就是一个首格单，所以首格键就是它的宿主键。
     */
    private static List<CellHostListPayload.CellSlot> driveCells(DriveBlockEntity drive, String hostKey,
            boolean withUsage) {
        var cells = new ArrayList<CellHostListPayload.CellSlot>(drive.getCellCount());
        for (int slot = 0; slot < drive.getCellCount(); slot++) {
            ItemStack stack = drive.getInternalInventory().getStackInSlot(slot);
            // 空格也占一条：稠密表下「列表下标 == 物理格位」，显示列号才能直接当格位用；
            // 只装有元件的格会在有间隔时错位，取放会落到另一格上。
            cells.add(isStorageCell(stack)
                    ? describeCell(stack, withUsage ? drive.getCellInventory(slot) : null, hostKey, slot, hostKey,
                            withUsage)
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
        Object controller = invoke(owner, "getStorageController");
        if (controller != null) {
            return controller;
        }
        Object cluster = invoke(owner, "getCluster");
        return cluster != null ? invoke(cluster, "getController") : null;
    }

    /** 主机下所有存储矩阵驱动器;的顺序读不出来时退回空表（只丢那个子系统的格，不影响其它驱动器）。 */
    @SuppressWarnings("unchecked")
    private static List<Object> drivesOfSubsystem(Object subsystem) {
        Object cluster = invoke(subsystem, "getCluster");
        Object drives = cluster != null ? invoke(cluster, "getDrives") : null;
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

        SubsystemAccumulator(Object subsystem, boolean withUsage) {
            this.subsystem = subsystem;
            // 一个主机就是一个首格单：它旗下那些单格元件座只当格，不再各占一个首格。
            String leaderKey = keyOf(subsystem);
            for (Object drive : drivesOfSubsystem(subsystem)) {
                appendCellOf(cells, drive, leaderKey, withUsage);
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
                    storagePriority(subsystem), List.copyOf(cells));
        }
    }

    /**
     * 把一台单格元件座追加成首格单（它的主机）上的一格。
     *
     * <p>宿主是那台元件座自己（不是首格键上的主机）：取放要写的是它，主机的 API 根本不存在。空格也要占一条，
     * 不然它后面所有格位都会前移，点第几列就不是第几格了。</p>
     */
    private static void appendCellOf(List<CellHostListPayload.CellSlot> out, Object drive, String leaderKey,
            boolean withUsage) {
        String hostKey = keyOf(drive);
        Object cell = invoke(drive, "getCellStack");
        if (!(cell instanceof ItemStack stack) || !isStorageCell(stack)) {
            out.add(emptySlot(hostKey, 0, leaderKey));
            return;
        }
        Object storage = withUsage ? asStorage(invoke(drive, "getCellInventory")) : null;
        out.add(describeCell(stack, (MEStorage) storage, hostKey, 0, leaderKey, withUsage));
    }

    /**
     * 这个物品是不是「存储」元件。
     *
     * <p>必须过滤：ECO 与青春版的闪存晶阵（计算单元）同样长着 {@code getCellStack()}，不筛就会把计算元件
     * 当成存储元件列出来。判定用类型而不是物品 id：AE2 系认 {@link IBasicCellItem} 与它的元存处理器，
     * ECO 系认名字里的 {@code IECOStorageCellItem}（可选依赖，不能直接引用那个接口）。</p>
     */
    public static boolean isStorageCell(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof IBasicCellItem || StorageCells.isCellHandled(stack)) {
            return true;
        }
        // ECO 系：元件的面是 {@code IBasicECOCellItem}（它再继承 AE2 的 ICellWorkbenchItem），
        // 而不是根本不存在的 IECOStorageCellItem（旧写法认错了名字，于是 ECO 原生与 ECO 青春版的
        // 「无限存储矩阵」这类元件全漏了）。连接口的父接口一起比，免得实现只是继承过去的。
        return implementsAny(stack.getItem().getClass(), ECO_CELL_INTERFACES);
    }

    /** ECO 系存储元件的接口名（按名字比：ECO 是可选依赖，不能直接引用它的接口）。 */
    private static final java.util.Set<String> ECO_CELL_INTERFACES = java.util.Set.of(
            "IBasicECOCellItem", "IECOStorageCellItem");

    /** 类及其父类链上有没有名字落在 {@code names} 里的接口（接口的父接口也算）。 */
    private static boolean implementsAny(Class<?> type, java.util.Set<String> names) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> iface : current.getInterfaces()) {
                if (interfaceChainContains(iface, names)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean interfaceChainContains(Class<?> iface, java.util.Set<String> names) {
        if (iface == null) {
            return false;
        }
        if (names.contains(iface.getSimpleName())) {
            return true;
        }
        for (Class<?> parent : iface.getInterfaces()) {
            if (interfaceChainContains(parent, names)) {
                return true;
            }
        }
        return false;
    }

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
            int hostSlot, String leaderKey, boolean withUsage) {
        if (!withUsage || storage == null) {
            return new CellHostListPayload.CellSlot(stack, 0f, 0f, hostKey, hostSlot, leaderKey);
        }
        long totalTypes = readTotalTypes(stack);
        long totalBytes = readTotalBytes(stack);
        if (totalTypes <= 0 && totalBytes <= 0) {
            return new CellHostListPayload.CellSlot(stack, 0f, 0f, hostKey, hostSlot, leaderKey);
        }
        KeyCounter used = storage.getAvailableStacks();
        float typeFill = totalTypes <= 0 ? 0f : Math.min(1f, (float) used.size() / totalTypes);
        long usedBytes = 0;
        for (var entry : used) {
            int perByte = Math.max(1, entry.getKey().getType().getAmountPerByte());
            usedBytes += Math.max(1, (entry.getLongValue() + perByte - 1) / perByte);
        }
        float byteFill = totalBytes <= 0 ? 0f : Math.min(1f, (float) usedBytes / totalBytes);
        return new CellHostListPayload.CellSlot(stack, typeFill, byteFill, hostKey, hostSlot, leaderKey);
    }

    /** 元件支持的类型总数：AE2 系问接口，ECO 系按方法名探；都读不到算 0。 */
    private static long readTotalTypes(ItemStack stack) {
        if (stack.getItem() instanceof IBasicCellItem basic) {
            return Math.max(0, basic.getTotalTypes(stack));
        }
        Object value = invoke(stack.getItem(), "getTotalTypes");
        return value instanceof Integer types ? Math.max(0, types) : 0;
    }

    /** 元件支持的总字节数：同上。返回类型在 ECO 那边是 long，统一抬成 long 算。 */
    private static long readTotalBytes(ItemStack stack) {
        if (stack.getItem() instanceof IBasicCellItem basic) {
            return Math.max(0, basic.getBytes(stack));
        }
        Object value = invoke(stack.getItem(), "getBytes");
        return value instanceof Long bytes ? Math.max(0, bytes) : 0;
    }

    /**
     * 按身份键找回驱动器本体（世界高亮、取元件这些手势要拿它操作）。
     *
     * <p>重新扫一遍而不是缓存：驱动器可能在两次点击之间被拆掉或换位置，拿旧引用去操作会改到错的方块实体。</p>
     */
    public static Object findOwner(IGrid grid, String key) {
        if (grid == null) {
            return null;
        }
        for (IGridNode node : grid.getNodes()) {
            Object owner = node.getOwner();
            if (owner != null && keyOf(owner).equals(key)) {
                return owner;
            }
        }
        return null;
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
     * 把指定格位的元件从驱动器里取出来；取不到（格变了、驱动器没了）返回空栈。
     *
     * <p>AE2 与 EAE 走驱动器库存；ECO 家族是单格元件座，只有第 0 格，且只能整格取走。</p>
     */
    public static ItemStack extractCell(Object owner, int slot, AtomicReference<ExtractResult> reason) {
        if (reason != null) {
            reason.set(ExtractResult.EMPTY);
        }
        if (owner instanceof DriveBlockEntity drive) {
            if (slot < 0 || slot >= drive.getCellCount()) {
                return ItemStack.EMPTY;
            }
            ItemStack current = drive.getInternalInventory().getStackInSlot(slot);
            if (current.isEmpty()) {
                return ItemStack.EMPTY;
            }
            drive.getInternalInventory().setItemDirect(slot, ItemStack.EMPTY);
            boolean taken = drive.getInternalInventory().getStackInSlot(slot).isEmpty();
            if (reason != null) {
                // 格里有东西却没变空同样是「取不出来」，不能报成「这一格没有元件」。
                reason.set(taken ? ExtractResult.OK : ExtractResult.REFUSED);
            }
            return taken ? current : ItemStack.EMPTY;
        }
        if (slot != 0) {
            return ItemStack.EMPTY;
        }
        Object current = invoke(owner, "getCellStack");
        if (!(current instanceof ItemStack stack) || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        // 单格座：清格用 null——ECO 的 setCellStack 对空栈会静默拒收（它的 isCellHandled(EMPTY) 是 false），
        // 它自己清格用的就是 null。清完必须回读：没真变空就是没取到（不能算「取了但原件还在」的复制）。
        clearCellStack(owner);
        Object after = invoke(owner, "getCellStack");
        if (after instanceof ItemStack left && !left.isEmpty()) {
            // 回读发现格子里还是原来那张：格里有东西却没取走。能问到锁的（ECO 无限存储迁移/成型）报 LOCKED，
            // 问不到的报 REFUSED——两者都比默认的 EMPTY 诚实：默认值会让玩家听到「这一格没有元件」，而格
            // 子里明明有。
            if (reason != null) {
                reason.set(canExtract(owner) ? ExtractResult.REFUSED : ExtractResult.LOCKED);
            }
            return ItemStack.EMPTY;
        }
        if (reason != null) {
            reason.set(ExtractResult.OK);
        }
        return stack;
    }

    /**
     * 这台宿主现在允不允许取出元件；探不到这个方法就算允许。
     *
     * <p>只对报了 {@code canExtractCell()} 的宿主有意义：ECO 的驱动器在无限存储迁移中、或主机开启无限模式
     * 而该矩阵尚未成型时返回 false，它的 {@code setCellStack} 第一句就是 {@code if (!canExtractCell()) return;}
     * ——静默拒收，回读只能看出「没取到」，看不出「为什么」。纯探测，不做任何写入。</p>
     */
    public static boolean canExtract(Object owner) {
        Object value = invoke(owner, "canExtractCell");
        return !(value instanceof Boolean allowed) || allowed;
    }

    /**
     * 把单格元件座清空：三种口径都试一遍，成败一律由调用方回读决定。
     *
     * <p>为什么不在返回值上判：这几家的回报各不相同——ECO 的 {@code setCellStack} 返回 void 且会对空栈静默
     * 拒收，青春版的 {@code removeCell()} 是<b>零参</b>（按 {@code ItemStack} 形参调用会永远匹配不上）且空手
     * 时返回空栈而不是 null。按返回值判就要为每家写一套，回读一句就都覆盖了。</p>
     *
     * <p>失败也不会弄丢东西：回读发现格子里还是原来那张时，取件直接返回 EMPTY。</p>
     */
    private static void clearCellStack(Object owner) {
        setCellStack(owner, null);
        setCellStack(owner, ItemStack.EMPTY);
        invoke(owner, "removeCell");
    }

    /** 把元件放回驱动器原来的格位；放不下或对方没收下返回 false。 */
    public static boolean insertCell(Object owner, int slot, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (owner instanceof DriveBlockEntity drive) {
            // 写前先判：驱动器只收存储元件，绕过它的过滤会把任意物品停在元件格里。
            if (slot < 0 || slot >= drive.getCellCount() || !isStorageCell(stack)
                    || !drive.getInternalInventory().getStackInSlot(slot).isEmpty()) {
                return false;
            }
            drive.getInternalInventory().setItemDirect(slot, stack);
            return ItemStack.isSameItemSameComponents(drive.getInternalInventory().getStackInSlot(slot), stack);
        }
        if (slot != 0) {
            return false;
        }
        Object current = invoke(owner, "getCellStack");
        // 空位在 ECO 那边是 null（它的字段初值就是 null，清格也用 null），空栈只是另一位面的口径：
        // 两者都算空，只有「真有一个元件」时才拒。
        if (current instanceof ItemStack existing && !existing.isEmpty()) {
            return false;
        }
        // 写前先问对方收不收（ECO 有 public isItemValid(ItemStack)）：它拒收是静默的，不回读就会
        // 「光标被清空、驱动器还是空的」——东西直接没了。
        if (!acceptsCell(owner, stack)) {
            return false;
        }
        if (!setCellStack(owner, stack)) {
            // 青春版的 L1 驱动器用 insertCell(ItemStack)；它收下返回 true，拒收返回 false。
            if (!Boolean.TRUE.equals(invokeItem(owner, "insertCell", stack))) {
                return false;
            }
        }
        Object after = invoke(owner, "getCellStack");
        return after instanceof ItemStack placed && ItemStack.isSameItemSameComponents(placed, stack);
    }

    /** 这台单格座收不收这张元件：问它自己的校验方法，问不到（第三方换名字）就放行。 */
    private static boolean acceptsCell(Object owner, ItemStack stack) {
        try {
            Method method = owner.getClass().getMethod("isItemValid", ItemStack.class);
            Object accepted = method.invoke(owner, stack);
            return !(accepted instanceof Boolean value) || value;
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return true;
        }
    }

    /**
     * 单格元件座的写入口（按名字探）。
     *
     * <p><b>不是每家都有这个方法</b>：ECO 各级存储的驱动器有 {@code setCellStack}，而青春版的 L1 驱动器
     * 没有——它用的是 {@code insertCell(ItemStack)} / {@code removeCell()} 一对。清格与放入各自先试这里、
     * 失败了再试那一对（见 {@link #clearCellStack} 与 {@link #insertCell}）。</p>
     *
     * <p>传入 {@code null} 表示清格（ECO 用它自己的判空口径）；返回的只是「方法存在且没抛异常」，
     * 真有没有生效由调用方回读确认——ECO 的 setCellStack 是会静默拒收的。</p>
     *
     * <p>形参类型必须保持 {@code ItemStack}：{@code invoke} 收的是「一个 ItemStack 参数」，
     * 若把它改成 {@code Object} 或直接写字面量 {@code null}，反射会退化成「零参数调用」而抛异常，
     * 清格就静默失效了（取件会被回读挡下，不会复制，但会变成取不了）。</p>
     */
    private static boolean setCellStack(Object owner, @Nullable ItemStack stack) {
        try {
            Method method = owner.getClass().getMethod("setCellStack", ItemStack.class);
            method.invoke(owner, stack);
            return true;
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return false;
        }
    }

    /** 按名字带一个 {@link ItemStack} 形参调用（用于 {@code insertCell}/{@code removeCell} 那一对）；失败返回 null。 */
    private static Object invokeItem(Object target, String name, @Nullable ItemStack arg) {
        try {
            Method method = target.getClass().getMethod(name, ItemStack.class);
            return method.invoke(target, arg);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return null;
        }
    }

    /** 探到的元件库存只有实现了 {@link MEStorage} 才能读占用；探不到就当作读不了。 */
    private static MEStorage asStorage(Object candidate) {
        return candidate instanceof MEStorage storage ? storage : null;
    }

    /**
     * 存储优先级：ECO 挂在存储控制器上（不是像 AE2 那样挂在单个驱动器上），青春版挂在它自己的 L1 宿主上。
     * 两条路都按方法名探，探不到就认不出；读得到的机器未必写得进去（ECO 本体只有 getter）。
     *
     * @return 读不到返回 null（表格那列就是「—」）
     */
    @Nullable
    public static Integer readStoragePriority(Object owner) {
        Object controller = storageController(owner);
        Object priority = controller != null ? invoke(controller, "getStoragePriority") : null;
        return priority instanceof Integer value ? value : null;
    }

    /** 这台设备的优先级**写不写得进去**：只有读没有写时不给入口。 */
    public static boolean canSetStoragePriority(Object owner) {
        if (owner instanceof IPriorityHost) {
            return true;
        }
        return hasIntSetter(owner) || hasIntSetter(storageController(owner));
    }

    /**
     * 探 {@code setStoragePriority(int)}。
     *
     * <p>为什么入口判据是“可写”而不是“可读”：AE2 的优先级界面只把值交给宿主，不读回也不报错，所以对着一个
     * 只有 getter 的设备（ECO 本体就是：它的 setter 是私有的、还要玩家参数）确认下去只会让玩家以为改成了。</p>
     */
    private static boolean hasIntSetter(@Nullable Object target) {
        if (target == null) {
            return false;
        }
        try {
            target.getClass().getMethod("setStoragePriority", int.class);
            return true;
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return false;
        }
    }

    /** 表格里那列优先级显示什么：读不到就是「—」。 */
    private static String storagePriority(Object owner) {
        Integer value = readStoragePriority(owner);
        return value != null ? Integer.toString(value) : PRIORITY_UNKNOWN;
    }

    /**
     * 把存储优先级写进这台设备的存储控制器；写不进去返回 false。
     *
     * <p>**不是每家都能写**：青春版的 L1 宿主带 {@code setStoragePriority(int)}，而 ECO 本体目前只暴露
     * 了 getter，对它写会返回 false。调用方据此回一句「只读」，而不是假装写成功。</p>
     */
    public static boolean setStoragePriority(Object owner, int value) {
        Object controller = storageController(owner);
        if (controller == null) {
            return false;
        }
        try {
            Method method = controller.getClass().getMethod("setStoragePriority", int.class);
            method.invoke(controller, value);
            return true;
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return false;
        }
    }

    /**
     * 这台设备的存储控制器：ECO 本体在驱动器自己身上（{@code getStorageController()}），青春版在
     * {@code getCluster().getController()}（驱动器没有前者，但继承了 {@code NEBlockEntity.getCluster()}）。
     */
    @Nullable
    private static Object storageController(Object owner) {
        Object controller = invoke(owner, "getStorageController");
        if (controller != null) {
            return controller;
        }
        Object cluster = invoke(owner, "getCluster");
        return cluster != null ? invoke(cluster, "getController") : null;
    }

    /** 无参方法按名字调；没有这个方法或调用失败都返回 null（可选依赖的常态）。 */
    private static Object invoke(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return null;
        }
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

    private static String keyOf(Object owner) {
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
