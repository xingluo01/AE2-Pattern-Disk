package io.github.lounode.ae2pattern.common.menu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.world.item.ItemStack;

import appeng.api.stacks.AEKey;

import org.jetbrains.annotations.Nullable;

/**
 * <b>压缩管理域</b>：网络里所有 ECO 大宗压缩矩阵，按压缩链聚合去重（「统一压缩管理」按钮）。
 *
 * <p>与 {@link CellMarkDomain} 的区别只在判重单位：本域<b>一条链算一项</b>，那条链在多台矩阵上被标了几遍
 * 只留一份；标记管理域按单个键判重。两域共用同一道准入闸与同一套「取出 → 改副本 → 放回」。</p>
 *
 * <p><b>不碰元件编码槽</b>：只整理已经装在网络驱动器 / 矩阵座里的元件。所有站点一律走副本改写，写回失败
 * 回填原件，连原件都放不回则掉在玩家脚下，宁可什么都不改。</p>
 */
final class CellBulkDomain {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory
            .getLogger(CellBulkDomain.class);

    /** 非 ECO 主机里发现大宗元件时的去重键（每次开终端的聚合只提醒一次）。 */
    private static final Set<String> MISPLACED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final CellManagementTermMenu menu;

    CellBulkDomain(CellManagementTermMenu menu) {
        this.menu = menu;
    }

    /** 域里的一个站点：网络里一格放得回去的大宗压缩矩阵。 */
    private record Site(String driveKey, int driveSlot, ItemStack stack,
                        CellBulkCutoffCompat.Site cell,
                        List<CellBulkCutoffCompat.Marker> markers, int capacity,
                        int cellPriority, @Nullable Integer priority, boolean ecoHost) {
    }

    /** 一次站点写回的结果：没变、写了、写不动（读不可信 / 换格失败 / 元件被挪走）。 */
    private enum ApplyResult { UNCHANGED, APPLIED, FAILED }

    /** 一次聚合的结果：每台矩阵该留的名单，与被清掉的重复标记条数。 */
    private record Plan(List<List<ItemStack>> wanted, int cleared) {
    }

    /**
     * 统一压缩管理：把网络里所有大宗矩阵的标记按链聚合去重，按优先级归属。
     *
     * <p>归属规则：每条链归目标顺序里第一个还有格位的那台，同链其余标记算重复并清掉；所有目标都满员时，
     * 链留在第一个列出它的矩阵，宁可不并也不丢。一台矩阵内部抄了两遍的也一并并掉。</p>
     */
    void consolidate() {
        var sites = collect();
        if (sites.isEmpty()) {
            return;
        }
        var plan = plan(sites);
        int touched = 0;
        var owners = new ArrayList<Object>();
        for (int index = 0; index < sites.size(); index++) {
            if (apply(sites.get(index), plan.wanted().get(index)) != ApplyResult.APPLIED) {
                continue;
            }
            touched++;
            var owner = menu.ownerOf(sites.get(index).driveKey());
            if (owner != null) {
                owners.add(owner);
            }
        }
        if (touched == 0) {
            return;
        }
        menu.afterCellMoved(owners.toArray());
        menu.noticeWith(CellManagementTermMenu.NOTICE + "bulk_consolidated", String.valueOf(plan.cleared()));
    }

    /**
     * 收集：网络里所有大宗压缩矩阵，按扫描顺序，名单取未去重的那份。
     *
     * <p>只认 {@link CellBulkCutoffCompat} 能打开的元件。<b>写不回去的站点在收集阶段就排除</b>：留着它，
     * 计划会把链判给一台必定收不下的宿主、别处的重复标记照删，账面报了条数而实际一格没改。</p>
     */
    private List<Site> collect() {
        var sites = new ArrayList<Site>();
        var grid = menu.gridOrNull();
        if (grid == null) {
            return sites;
        }
        // 宿主按键回读，同一台下面几十格：记一次就够，别每格重扫一遍网络。
        var ownerByKey = new HashMap<String, Object>();
        for (var entry : CellDriveScanner.scan(grid, false)) {
            for (var cell : entry.cells()) {
                var owner = ownerByKey.computeIfAbsent(cell.hostKey(),
                        key -> CellDriveScanner.findOwner(grid, key));
                if (owner == null || !CellDriveScanner.canAcceptCell(owner, cell.hostSlot(), cell.stack())) {
                    if (MISPLACED.add(cell.hostKey() + "|" + cell.stack().getItem())) {
                        LOGGER.warn("[cell-mgmt] 大宗元件 {} 放在宿主 {} 里：这台宿主不收这一类元件，"
                                + " 它只出现在表里，不作为聚合目标", cell.stack().getItem(), cell.hostKey());
                    }
                    continue;
                }
                // 先问这一句再开 Site：读一张矩阵的账不便宜。
                var site = open(cell.hostKey(), cell.hostSlot(), cell.stack(),
                        entry.priorityValue(), entry.ecoHost());
                if (site != null) {
                    sites.add(site);
                }
            }
        }
        return sites;
    }

    /** 读一台矩阵的名单、格数与库存；只要是大宗元件就返回（空盘也有格数，能当聚合目标）。 */
    @Nullable
    private static Site open(String driveKey, int driveSlot, ItemStack stack,
            @Nullable Integer priority, boolean ecoHost) {
        var cell = CellBulkCutoffCompat.open(stack);
        if (cell == null) {
            return null;
        }
        var markers = cell.rawMarkers();
        if (!cell.trusted()) {
            // 读失败不能当空盘：那会把这台真实的标记整份覆盖。
            return null;
        }
        return new Site(driveKey, driveSlot, stack, cell, markers, cell.capacity(),
                cell.storagePriority(), priority, ecoHost);
    }

    private Plan plan(List<Site> sites) {
        // 链 → 列出它的站点集合（同一台里抄了两遍也只算它一次）
        var listed = new LinkedHashMap<Set<AEKey>, LinkedHashSet<Integer>>();
        for (int index = 0; index < sites.size(); index++) {
            for (var marker : sites.get(index).markers()) {
                listed.computeIfAbsent(marker.chain(), key -> new LinkedHashSet<>()).add(index);
            }
        }
        // 归属只看目标顺序：每条链归顺序里第一个还有格位的那台，不管库存现在在谁那里（库存本身不动）。
        var keepers = new LinkedHashMap<Set<AEKey>, List<Integer>>();
        var targets = targetOrder(sites);
        var room = new int[sites.size()];
        for (int index = 0; index < sites.size(); index++) {
            room[index] = sites.get(index).capacity();
        }
        logOrder(sites, targets);
        for (var entry : listed.entrySet()) {
            for (int target : targets) {
                if (room[target] <= 0) {
                    continue;
                }
                keepers.put(entry.getKey(), List.of(target));
                room[target]--;
                break;
            }
        }
        // 所有目标都满员：这条链留在第一个列出它的矩阵，宁可不并也不丢。
        for (var entry : listed.entrySet()) {
            keepers.putIfAbsent(entry.getKey(), List.of(List.copyOf(entry.getValue()).get(0)));
        }
        var wanted = new ArrayList<List<ItemStack>>(sites.size());
        int cleared = 0;
        for (int index = 0; index < sites.size(); index++) {
            var site = sites.get(index);
            var mine = new ArrayList<ItemStack>();
            var taken = new HashSet<Set<AEKey>>();
            for (var marker : site.markers()) {
                var chain = marker.chain();
                if (!keepers.get(chain).contains(index) || !taken.add(chain)) {
                    continue;
                }
                mine.add(marker.item());
            }
            cleared += site.markers().size() - taken.size();
            // 归到这台、但它原本没列出的链：取来源那台的标记物。
            for (var entry : listed.entrySet()) {
                var chain = entry.getKey();
                if (taken.contains(chain) || !keepers.get(chain).contains(index)) {
                    continue;
                }
                var source = markerOf(sites.get(entry.getValue().iterator().next()), chain);
                if (source != null) {
                    mine.add(source.item());
                    taken.add(chain);
                }
            }
            wanted.add(mine);
        }
        if (LOGGER.isInfoEnabled()) {
            var sizes = new StringBuilder();
            for (int index = 0; index < wanted.size(); index++) {
                sizes.append(index).append('=').append(wanted.get(index).size()).append(' ');
            }
            LOGGER.info("[cell-mgmt] bulk consolidation: wanted per site: {}, cleared={}", sizes, cleared);
        }
        return new Plan(wanted, cleared);
    }

    /**
     * 填充顺序：按控制器的存储优先级降序（读不到的排最后），同优先级按扫描顺序。
     *
     * <p>先按矩阵自己的元件级优先级，再按宿主控制器优先级；{@code List.sort} 稳定，同级保持扫描顺序。</p>
     */
    private static List<Integer> targetOrder(List<Site> sites) {
        var order = new ArrayList<Integer>(sites.size());
        for (int index = 0; index < sites.size(); index++) {
            order.add(index);
        }
        order.sort(Comparator
                .comparingInt((Integer index) -> sites.get(index).cellPriority())
                .thenComparingInt((Integer index) -> {
                    Integer priority = sites.get(index).priority();
                    return priority == null ? Integer.MIN_VALUE : priority;
                })
                .reversed());
        return order;
    }

    private static void logOrder(List<Site> sites, List<Integer> targets) {
        if (!LOGGER.isInfoEnabled()) {
            return;
        }
        var order = new StringBuilder();
        for (int target : targets) {
            var site = sites.get(target);
            order.append(target).append('=').append(site.driveKey())
                    .append("/q").append(site.cellPriority())
                    .append("/p").append(site.priority() == null ? "?" : site.priority().toString())
                    .append("/m").append(site.markers().size())
                    .append("/c").append(site.capacity())
                    .append(site.ecoHost() ? "/eco" : "/drive").append(' ');
        }
        LOGGER.info("[cell-mgmt] bulk consolidation: {} sites, target order: {}", sites.size(), order);
    }

    @Nullable
    private static CellBulkCutoffCompat.Marker markerOf(Site site, Set<AEKey> chain) {
        for (var marker : site.markers()) {
            if (marker.chain().equals(chain)) {
                return marker;
            }
        }
        return null;
    }

    /** 按目标名单改写这一台；返回是否真的动了它。 */
    private ApplyResult apply(Site site, List<ItemStack> markers) {
        if (!site.cell().trusted()) {
            return ApplyResult.FAILED;
        }
        if (sameMarkers(site.markers(), markers)) {
            return ApplyResult.UNCHANGED;
        }
        var owner = menu.ownerOf(site.driveKey());
        if (owner == null) {
            return ApplyResult.FAILED;
        }
        if (!CellDriveScanner.canAcceptCell(owner, site.driveSlot(), site.stack())) {
            // 这台宿主不收这一类元件（ECO 的矩阵躺在 AE2 驱动器里就是这样）：一动就是「取出来放不回去」，
            // 所以连这次往返都不开始。
            return ApplyResult.FAILED;
        }
        var taken = CellDriveScanner.extractCell(owner, site.driveSlot(), null);
        if (taken.isEmpty()) {
            // 这台现在不让动（无限存储迁移中、矩阵还没成型），跳过它而不是硬来。
            return ApplyResult.FAILED;
        }
        var modified = taken.copy();
        var replacement = CellBulkCutoffCompat.open(modified);
        if (replacement == null || !replacement.setMarkers(markers)
                || !CellDriveScanner.insertCell(owner, site.driveSlot(), modified)) {
            dropIfStuck(owner, site, taken);
            return ApplyResult.FAILED;
        }
        return ApplyResult.APPLIED;
    }

    /** 写回失败就原样塞回去；连原件都塞不回（宿主刚被拆、或口径变了）也不能凭空吞掉。 */
    private void dropIfStuck(Object owner, Site site, ItemStack taken) {
        if (!CellDriveScanner.insertCell(owner, site.driveSlot(), taken)) {
            LOGGER.info("[cell-mgmt] 大宗矩阵写回失败且原件放不回去，已掉在玩家脚下：{}", site.stack().getItem());
            menu.getPlayer().drop(taken, false);
        }
    }

    /** 名单是否逐格相同：聚合会既删又加，条数相同不代表内容没变。 */
    private static boolean sameMarkers(List<CellBulkCutoffCompat.Marker> current, List<ItemStack> markers) {
        if (current.size() != markers.size()) {
            return false;
        }
        for (int slot = 0; slot < current.size(); slot++) {
            if (!ItemStack.isSameItemSameComponents(current.get(slot).item(), markers.get(slot))) {
                return false;
            }
        }
        return true;
    }
}
