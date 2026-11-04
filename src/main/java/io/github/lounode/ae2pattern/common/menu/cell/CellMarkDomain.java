package io.github.lounode.ae2pattern.common.menu.cell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import appeng.api.networking.IGrid;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.util.ConfigInventory;

/**
 * <b>标记管理域</b>：网络里所有「能写标记」的目标存储元件。
 *
 * <p>与 {@code CellManagementTermMenu#collectBulkSites()} 那套<b>压缩管理域</b>并列，两者不是同一个集合：</p>
 *
 * <table border="1">
 *   <caption>两个管理域</caption>
 *   <tr><th></th><th>压缩管理域</th><th>标记管理域（本类）</th></tr>
 *   <tr><td>收什么</td><td>所有 ECO 大宗压缩矩阵</td><td>只收下面两款奇点元件</td></tr>
 *   <tr><td>标记单位</td><td>一条压缩链占一格</td><td>一个 {@link AEKey} 占一格</td></tr>
 *   <tr><td>跨站点关系</td><td>按链去重、按优先级归属</td><td>不做去重；只回答「哪些键已经被管住」</td></tr>
 * </table>
 *
 * <p><b>两域都不收元件编码槽里那张元件</b>：两枚批量按钮都只在编码槽空着时出现，本域只读已经装在网络
 * 驱动器 / 矩阵座里的目标元件，不写编码槽的标记槽。{@link #collect} 因此只要网格，不再需要编码槽栈。</p>
 *
 * <p>本类只做<b>收集、读取与对副本改写</b>，不碰菜单服务：从宿主里取出元件、写回、失败掉在玩家脚下那一套
 * 留在菜单侧（{@code applyMarkers} 的形状），因为它要用 {@code getPlayer()} 与 {@code broadcastChanges()}。
 * 「快捷标记」按钮接的是本域的 {@link #coveredKeys(List)}（判断物品在不在域内）、
 * {@link #targetOrder(List)}（挑先填哪一台）与 {@link #firstTarget(List, int[])}（找还有空位的那一台）。</p>
 *
 * <p><b>与 ECO 压缩链无关</b>：快捷标记只按「数量过阈值、且域里还没管住」挑物品，不问它在不在某条
 * 压缩链上，也不按链分组。早期版本拿 {@code getCompressionChain} 反查，结果绝大多数过阈值的物品
 * 都被判成「非压缩」而只往普通元件塞，ECO 矩阵一格没动——这张矩阵本来就能给任意物品当标记位
 * （上游对非链成员给一条单成员链，只是不参与压缩）。压缩链只在<b>压缩管理域</b>用于去重。</p>
 *
 * <p><b>为什么先问 ECO 大宗</b>：ECO 的存储矩阵同样长着 {@link ICellWorkbenchItem}，但它的标记槽要走
 * ECO 自己的 {@code setMarkers}（落盘口径与 AE2 的 {@code ConfigInventory} 不同）；先按普通分区元件认，
 * 写回去会盖掉生效截断层。所以 {@link #open(ItemStack)} 的顺序是压缩矩阵优先，认出来了就不再往下走。
 * 这是<b>元件类型</b>的区分，不是拿物品的压缩链去筛候选。</p>
 *
 * <p><b>目标严格限定</b>：标记管理域只认 {@link #MARK_TARGETS} 里的两款元件——ECO 的数字奇点物品存储矩阵
 * （压缩链）与 Bigger AE2 的数字奇点物品存储元件（单格分区）。收「所有 {@link ICellWorkbenchItem}」会把网络里
 * 的普通 1k/4k 元件、创意元件一并当成写入目标，分区被整份重写，表现就是「胡乱标记」。
 * 这里按物品 id 的字符串收口：两个 id 都是稳定的注册名，且不把可选依赖的类型名带进总是加载的
 * {@code main} 源集（见集成红线）。压缩管理域不受这条限制，它仍按中立适配器判定。</p>
 *
 * <p><b>读走副本</b>：ECO 侧查生效截断会顺带做历史账户恢复并落盘，所以扫描阶段读的是站点自己的视图，
 * 写一律落在调用方递进来的那张栈上（见 {@link View#write}），和压缩管理域同一口径。</p>
 */
public final class CellMarkDomain {

    /**
     * 允许被快捷标记改写的两款元件。
     *
     * <p>{@code neoecoae:...} 那张只在 Bigger AE2 在场时由 ECO 的 {@code @Integration("bigger_ae2")} 注册；
     * 它正是压缩管理域认得的压缩矩阵，走 {@code CellBulkCutoffCompat}。另一张是 Bigger AE2 自己的普通
     * AE2 分区元件（{@link ICellWorkbenchItem}，只有 1 个分区格）。同族的流体 / 化学 / FE / 经验 / 魔源矩阵
     * 与旧 MEGA 大宗矩阵都不在此列——原始需求只点了「物品」这两款。</p>
     */
    private static final java.util.Set<String> MARK_TARGETS = java.util.Set.of(
            "neoecoae:eco_digital_singularity_item_storage_cell",
            "bigger_ae2:digital_singularity_item_storage_cell");

    /** 这张元件是不是快捷标记允许改写的目标；按注册名字符串判，不引邻居模组的类型。 */
    public static boolean isMarkTarget(String id) {
        return MARK_TARGETS.contains(id);
    }

    /**
     * AE2 终端里显示成「2.1G」与「9.2E」的那两档数量，不是真实库存。
     *
     * <p>2.1G = {@link Integer#MAX_VALUE}（2,147,483,647）：网络数量被夹到 int 契约上限的结果，本仓
     * {@code PatternDiskProviderLogic} 里就有 {@code Math.min(Integer.MAX_VALUE, entry.getLongValue())}。
     * 9.2E = {@link Long#MAX_VALUE}（9,223,372,036,854,775,807）：long 的上限，无限 / 虚拟存储常把可用量
     * 报成这个数。快捷标记按「数量超过阈值」筛，这两档必然入选，会把无限元件里的幽灵物品写进只有几格的
     * 标记区，所以按精确相等单独挡掉——不按 {@code >=} 挡，21 亿以上的真实大宗库存仍要能标。</p>
     */
    public static boolean isSentinelAmount(long amount) {
        return amount == Integer.MAX_VALUE || amount == Long.MAX_VALUE;
    }

    /** 数量够不够格被快捷标记：严格大于阈值，且不是上面两档哨兵数量。 */
    public static boolean acceptsAmount(long amount, long threshold) {
        return amount > threshold && !isSentinelAmount(amount);
    }

    private CellMarkDomain() {
    }

    /**
     * 一张可标记元件的中立视图。判定只看 AE2 公共契约与 {@link CellBulkCutoffCompat} 的中立适配器，
     * 不出现任何邻居模组的类型名——本类在总是加载的 {@code main} 源集里。
     */
    public interface View {

        /** 当前标记名单，按配置格顺序、只收非空格；写回时按这份名单逐格比对。 */
        List<AEKey> markers();

        /**
         * 这张元件已经「管住」的键。
         *
         * <p>普通分区元件就是标记本身；压缩矩阵要算上每条已标记链的<b>全部成员</b>——矩阵上标了铁锭，
         * 网络里的铁粒与铁块同样已经被这条链接住了，快捷标记不该再给它们另找一格。</p>
         */
        Set<AEKey> coveredKeys();

        /** 现在能写多少格（含空位），随扩容卡变。 */
        int capacity();

        /** 是不是 ECO 大宗压缩矩阵：决定「符合压缩链优先填这里」。 */
        boolean bulk();

        /** 元件自身的存储优先级；普通 AE2 元件没有这个概念，给 0。 */
        int storagePriority();

        /** 这份视图可不可信：上游查询失败时实现必须返回 false，收集阶段会整台跳过。 */
        boolean trusted();

        /**
         * 把 {@code cell} 的标记整份改成 {@code markers}，其余格清空；返回是否真的动了格。
         *
         * <p><b>内部重开</b>：传进来的通常是刚从驱动器取出的副本，本方法不复用收集阶段那份视图——
         * 那份绑在旧栈上，拿它去写会写到已经不在宿主里的那张元件。名单超出格数时整份不写：
         * 写一半等于静默丢标记（{@code EcoBulkCells.rewrite} 也是这个取舍）。</p>
         */
        boolean write(ItemStack cell, List<AEKey> markers);
    }

    /**
     * 标记管理域里的一个站点：网络里一格放得回去的目标元件。
     *
     * @param hostKey  宿主的身份键（驱动器 / 矩阵座）
     * @param hostSlot 这一格在宿主上的格位；单格元件座恒为 0
     * @param priority 宿主控制器的存储优先级，读不到时 null
     * @param ecoHost  这一格来自 ECO 存储主机（否则是 AE2 标准驱动器格式的设备）
     */
    public record Site(String hostKey, int hostSlot, ItemStack stack, View view,
            int cellPriority, @Nullable Integer priority, boolean ecoHost) {

        public List<AEKey> markers() {
            return view.markers();
        }

        public Set<AEKey> coveredKeys() {
            return view.coveredKeys();
        }

        public int capacity() {
            return view.capacity();
        }

        public boolean bulk() {
            return view.bulk();
        }
    }

    /**
     * 认一张元件：先问压缩矩阵，再问 AE2 分区元件；都不是返回 null。
     *
     * <p>返回非 null 不代表可信，{@link #collect} 会再看一眼 {@link View#trusted()}。</p>
     */
    @Nullable
    public static View open(ItemStack stack) {
        if (stack.isEmpty()
                || !isMarkTarget(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) {
            return null;
        }
        var bulk = CellBulkCutoffCompat.open(stack);
        if (bulk != null) {
            return new BulkView(bulk);
        }
        if (stack.getItem() instanceof ICellWorkbenchItem workbench) {
            ConfigInventory config = workbench.getConfigInventory(stack);
            if (config != null && config.size() > 0) {
                return new WorkbenchView(config);
            }
        }
        return null;
    }

    /**
     * 扫出标记管理域：全网各格里放得回去、且落在 {@link #MARK_TARGETS} 里的元件，按扫描顺序。
     * <b>不收元件编码槽里那张</b>，与压缩管理域同口径。
     *
     * <p>与压缩管理域共用同一道准入闸 {@code CellDriveScanner.canAcceptCell}：写不回去的站点（例如
     * ECO 矩阵错躺在 AE2 驱动器里）不进域，否则计划会把标记判给一台必定收不下的宿主，账面报了条数
     * 而实际一格没改。宿主按键回读，同一台下面几十格只找一次 owner。</p>
     *
     * <p>必须在服务端跑：格里的 ItemStack 只在服务端由方块实体持有。</p>
     */
    public static List<Site> collect(@Nullable IGrid grid) {
        var sites = new ArrayList<Site>();
        if (grid != null) {
            var ownerByKey = new HashMap<String, Object>();
            for (var entry : CellDriveScanner.scan(grid, false)) {
                for (var cell : entry.cells()) {
                    var owner = ownerByKey.computeIfAbsent(cell.hostKey(),
                            key -> CellDriveScanner.findOwner(grid, key));
                    if (owner == null || !CellDriveScanner.canAcceptCell(owner, cell.hostSlot(), cell.stack())) {
                        continue;
                    }
                    var site = siteOf(cell.hostKey(), cell.hostSlot(), cell.stack(),
                            entry.priorityValue(), entry.ecoHost());
                    if (site != null) {
                        sites.add(site);
                    }
                }
            }
        }
        return sites;
    }

    @Nullable
    private static Site siteOf(String hostKey, int hostSlot, ItemStack stack,
            @Nullable Integer priority, boolean ecoHost) {
        var view = open(stack);
        // 读失败不能当空盘：那会让后续分配把这台真实的标记整份覆盖。
        if (view == null || !view.trusted()) {
            return null;
        }
        return new Site(hostKey, hostSlot, stack, view, view.storagePriority(), priority, ecoHost);
    }

    /**
     * 整个标记管理域已经管住的键：所有站点 {@link View#coveredKeys()} 的并集。
     *
     * <p>「ME 网络里不在标记管理域的物品」就是 {@code networkKeys - coveredKeys(sites)}。压缩矩阵按整条链
     * 展开，所以链上任何形态都算在域内；这是快捷标记判重的唯一口径，别在调用方另写一份。</p>
     */
    public static Set<AEKey> coveredKeys(List<Site> sites) {
        var covered = new HashSet<AEKey>();
        for (var site : sites) {
            covered.addAll(site.coveredKeys());
        }
        return Set.copyOf(covered);
    }

    /**
     * 填充顺序：压缩矩阵优先，其后按元件级 / 宿主级存储优先级降序，同级按扫描顺序。
     *
     * <p>「压缩矩阵优先」只看<b>元件类型</b>（大容量矩阵先填），与物品属不属于 ECO 压缩链无关。
     * 优先级来源与压缩管理域一致：ECO 走 {@code IECOStoragePriorityHost}，
     * AE2 驱动器走 {@code getPriority()}，读不到的排最后。{@code List.sort} 稳定，同级保持扫描顺序。</p>
     */
    public static List<Integer> targetOrder(List<Site> sites) {
        var order = new ArrayList<Integer>(sites.size());
        for (int index = 0; index < sites.size(); index++) {
            order.add(index);
        }
        order.sort(Comparator
                .comparingInt((Integer index) -> sites.get(index).bulk() ? 1 : 0)
                .thenComparingInt(index -> sites.get(index).cellPriority())
                .thenComparingInt(index -> {
                    Integer priority = sites.get(index).priority();
                    return priority == null ? Integer.MIN_VALUE : priority;
                })
                .reversed());
        return order;
    }

    /**
     * 目标顺序里第一台还有空位的站点；全满时 -1。
     *
     * <p>不区分站点类型、也不问物品的压缩链：顺序本身（见 {@link #targetOrder(List)}）已经把
     * 该先填谁定好了，这里只负责跳过装满的。一台填满了自然轮到下一台，两类元件都能被用到。</p>
     */
    public static int firstTarget(List<Integer> order, int[] room) {
        for (int index : order) {
            if (room[index] > 0) {
                return index;
            }
        }
        return -1;
    }

    /** ECO 大宗压缩矩阵：一格一条链，标记物是生效截断层那一级。 */
    private record BulkView(CellBulkCutoffCompat.Site cell) implements View {

        @Override
        public List<AEKey> markers() {
            var markers = new ArrayList<AEKey>();
            for (var marker : cell.rawMarkers()) {
                AEItemKey key = AEItemKey.of(marker.item());
                if (key != null) {
                    markers.add(key);
                }
            }
            return List.copyOf(markers);
        }

        @Override
        public Set<AEKey> coveredKeys() {
            var covered = new HashSet<AEKey>();
            for (var marker : cell.rawMarkers()) {
                covered.addAll(marker.chain());
            }
            return Set.copyOf(covered);
        }

        @Override
        public int capacity() {
            return cell.capacity();
        }

        @Override
        public boolean bulk() {
            return true;
        }

        @Override
        public int storagePriority() {
            return cell.storagePriority();
        }

        @Override
        public boolean trusted() {
            return cell.trusted();
        }

        @Override
        public boolean write(ItemStack stack, List<AEKey> markers) {
            var opened = CellBulkCutoffCompat.open(stack);
            if (opened == null || !opened.trusted()) {
                return false;
            }
            var items = new ArrayList<ItemStack>(markers.size());
            for (AEKey key : markers) {
                // 压缩标记只能是物品键；上游的 setMarkers 收的是 ItemStack，塞不进非物品。
                if (!(key instanceof AEItemKey itemKey)) {
                    return false;
                }
                items.add(itemKey.toStack());
            }
            return opened.setMarkers(items);
        }
    }

    /** AE2 标准分区元件：分区库存自己决定收哪些键类型、能标几格。 */
    private record WorkbenchView(ConfigInventory config) implements View {

        @Override
        public List<AEKey> markers() {
            var markers = new ArrayList<AEKey>();
            for (int slot = 0; slot < config.size(); slot++) {
                AEKey key = config.getKey(slot);
                if (key != null) {
                    markers.add(key);
                }
            }
            return List.copyOf(markers);
        }

        @Override
        public Set<AEKey> coveredKeys() {
            return Set.copyOf(markers());
        }

        @Override
        public int capacity() {
            return config.size();
        }

        @Override
        public boolean bulk() {
            return false;
        }

        @Override
        public int storagePriority() {
            return 0;
        }

        @Override
        public boolean trusted() {
            return true;
        }

        @Override
        public boolean write(ItemStack stack, List<AEKey> markers) {
            if (!(stack.getItem() instanceof ICellWorkbenchItem workbench)) {
                return false;
            }
            // 从传进来的这张栈重开：分区库存是「建时读进内存、改动时整份写回」，用收集阶段那份会写到旧栈上。
            ConfigInventory target = workbench.getConfigInventory(stack);
            if (target == null || markers.size() > target.size()) {
                return false;
            }
            // 先整份验一遍再动格：中途发现某格收不下就什么都不写，免得前半段已经落盘、后半段被丢掉。
            for (int slot = 0; slot < target.size(); slot++) {
                AEKey want = slot < markers.size() ? markers.get(slot) : null;
                if (want != null && !Objects.equals(target.getKey(slot), want)
                        && !target.isAllowedIn(slot, want)) {
                    return false;
                }
            }
            boolean changed = false;
            for (int slot = 0; slot < target.size(); slot++) {
                AEKey want = slot < markers.size() ? markers.get(slot) : null;
                if (Objects.equals(target.getKey(slot), want)) {
                    continue;
                }
                target.setStack(slot, want == null ? null : new GenericStack(want, 1));
                if (!Objects.equals(target.getKey(slot), want)) {
                    // 过滤器放行却写不进去：这张元件自己的上限到了，按失败处理而不是报「改过了」。
                    return false;
                }
                changed = true;
            }
            return changed;
        }
    }
}
