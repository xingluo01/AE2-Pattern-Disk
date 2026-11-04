package io.github.lounode.ae2pattern.common.menu.cell;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import appeng.api.networking.IGrid;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

import io.github.lounode.ae2pattern.config.AEPDCommonConfig;

/**
 * 标记管理域 · 快捷标记：把 ME 网络里<b>不在域内</b>的物品补写进域内元件的空标记格。
 *
 * <p>「不在域内」的判据是 {@link CellMarkDomain#coveredKeys(List)}（所有站点已标记键的并集）；
 * 分配按 {@link CellMarkDomain#targetOrder(List)} 与 {@link CellMarkDomain#firstTarget(List, int[])}：
 * 顺着优先级把第一台有空位的元件填满，再轮到下一台。</p>
 *
 * <p><b>不看 ECO 压缩链</b>：候选只按数量阈值与「是否已在域内」筛，不反查压缩链、不按链分组。早期版本把
 * 过阈值的物品先判一遍链，非链成员一律只找普通分区元件，而 Bigger AE2 那张只有 1 格且常常没插，结果
 * ECO 矩阵始终 0 / 5。上游对非链成员允许作为普通标记，写进矩阵是安全的。</p>
 *
 * <p>默认口径：只写标记、不迁移库存；候选是数量严格大于阈值、且不是 2.1G / 9.2E 两档哨兵数量的物品键
 * （见 {@link CellMarkDomain#acceptsAmount}）；装不下的计入「未写入」，不按数量排序。</p>
 */
final class CellQuickMark {

    /**
     * 数量阈值：与 ECO 21.2.12 的 {@code megaBulkAutoMarkThreshold} 默认值对齐，
     * 口径同样是「数量<b>严格大于</b>此值才标」。
     *
     * <p>不读上游配置：那份在 ECO 自己的 {@code config} 包里，不是公开 API，引到总是加载的 common 代码里
     * 会在缺 ECO 时炸。这里固定成同一个默认值。</p>
     */
    private static final long MIN_AMOUNT = 20_000L;

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory
            .getLogger(CellQuickMark.class);

    private final CellManagementTermMenu menu;

    CellQuickMark(CellManagementTermMenu menu) {
        this.menu = menu;
    }

    /**
     * 按下按钮：分配 → 逐台写回 → 回执。
     *
     * <p><b>每次按下都留痕</b>：早退分支（没连上网、域里没有目标元件）与正常结束都走 {@link #report}，
     * 聊天栏给六个计数、日志给逐环诊断。以前这几条分支完全静默，「按了没反应」和「矩阵还是 0/5」在现场
     * 无从区分。</p>
     */
    void run() {
        var grid = menu.gridOrNull();
        if (grid == null) {
            report(0, 0, 0, 0, 0, 0, "grid=null（终端此刻没连上网络）");
            return;
        }
        var sites = CellMarkDomain.collect(grid);
        // 诊断：把「扫描器看见了」与「进了标记管理域」分开数。两者不等就卡在准入闸或白名单上。
        ScanCount scan = countScanned(grid);
        if (sites.isEmpty()) {
            report(0, 0, 0, 0, 0, 0,
                    "no-target: scanned=" + scan.cells() + " named=" + scan.named()
                            + "（元件被准入闸 / 白名单 / 可信度挡在域外）");
            return;
        }
        var covered = new HashSet<AEKey>(CellMarkDomain.coveredKeys(sites));
        var order = CellMarkDomain.targetOrder(sites);
        var room = new int[sites.size()];
        var additions = new ArrayList<List<AEKey>>(sites.size());
        var siteDesc = new StringBuilder();
        int bulkSites = 0;
        int totalRoom = 0;
        for (int index = 0; index < sites.size(); index++) {
            var site = sites.get(index);
            room[index] = Math.max(0, site.capacity() - site.markers().size());
            totalRoom += room[index];
            if (site.bulk()) {
                bulkSites++;
            }
            additions.add(new ArrayList<>());
            siteDesc.append(index).append(':')
                    .append(site.bulk() ? "bulk" : "plain").append('/')
                    .append(site.capacity()).append('-').append(site.markers().size());
            if (index + 1 < sites.size()) {
                siteDesc.append(' ');
            }
        }
        Tally tally = assign(grid, covered, order, room, additions);
        WriteResult write = writeBack(sites, order, additions);
        report(write.written(), tally.duplicate(), tally.noRoom() + write.lostItems(),
                tally.nonItems(), sites.size(), tally.candidates(),
                "sites=" + sites.size() + " bulk=" + bulkSites + " room=" + totalRoom
                        + " [" + siteDesc + "] scanned=" + scan.cells() + " named=" + scan.named()
                        + " | below20k=" + tally.belowThreshold() + " sentinel=" + tally.sentinel()
                        + " candidates=" + tally.candidates() + " assigned=" + tally.assigned()
                        + " writeOk=" + write.ok() + " writeFailed=" + write.failedSites());
    }

    private record ScanCount(int cells, int named) {
    }

    /** 扫描器看见的元件数、其中命中目标白名单的数量。 */
    private static ScanCount countScanned(IGrid grid) {
        int cells = 0;
        int named = 0;
        for (var entry : CellDriveScanner.scan(grid, false)) {
            for (var cell : entry.cells()) {
                if (cell.stack().isEmpty()) {
                    continue;
                }
                cells++;
                if (CellMarkDomain.isMarkTarget(BuiltInRegistries.ITEM.getKey(cell.stack().getItem()).toString())) {
                    named++;
                }
            }
        }
        return new ScanCount(cells, named);
    }

    /** 一轮分配的计数。 */
    private record Tally(int duplicate, int noRoom, int nonItems, int candidates,
                         int belowThreshold, int sentinel, int assigned) {
    }

    /** 按目标顺序把候选排进各站点的空位；只算名单，不动元件。 */
    private static Tally assign(IGrid grid, HashSet<AEKey> covered,
            List<Integer> order, int[] room, List<List<AEKey>> additions) {
        int duplicate = 0;
        int noRoom = 0;
        int nonItems = 0;
        int candidates = 0;
        int belowThreshold = 0;
        int sentinel = 0;
        int assigned = 0;
        for (var entry : grid.getStorageService().getInventory().getAvailableStacks()) {
            long amount = entry.getLongValue();
            if (!CellMarkDomain.acceptsAmount(amount, MIN_AMOUNT)) {
                // 分两档记账：ECO 的 BigInteger 压缩库存在 KeyCounter 里会饱和到 9.2E，
                // 日志里要能看出「候选全被它筛光」这种情形。
                if (CellMarkDomain.isSentinelAmount(amount)) {
                    sentinel++;
                } else {
                    belowThreshold++;
                }
                continue;
            }
            candidates++;
            AEKey key = entry.getKey();
            if (!(key instanceof AEItemKey)) {
                nonItems++;
                continue;
            }
            if (covered.contains(key)) {
                duplicate++;
                continue;
            }
            int target = CellMarkDomain.firstTarget(order, room);
            if (target < 0) {
                noRoom++;
                continue;
            }
            additions.get(target).add(key);
            room[target]--;
            assigned++;
            covered.add(key);
        }
        return new Tally(duplicate, noRoom, nonItems, candidates, belowThreshold, sentinel, assigned);
    }

    /** 逐台写回：成功写进去的条数、成功台数、失败台数、失败带掉的条数。 */
    private record WriteResult(int written, int ok, int failedSites, int lostItems) {
    }

    private WriteResult writeBack(List<CellMarkDomain.Site> sites, List<Integer> order,
            List<List<AEKey>> additions) {
        var owners = new ArrayList<Object>();
        int written = 0;
        int ok = 0;
        int failedSites = 0;
        int lostItems = 0;
        for (int index : order) {
            var added = additions.get(index);
            if (added.isEmpty()) {
                continue;
            }
            var site = sites.get(index);
            var wanted = new ArrayList<AEKey>(site.markers());
            wanted.addAll(added);
            if (wanted.size() > site.capacity() || !apply(site, wanted)) {
                failedSites++;
                lostItems += added.size();
                LOGGER.info("[cell-mgmt] quick mark: 站点 {} 写回失败（名单 {} 格 / 容量 {}），已回填原件",
                        index, wanted.size(), site.capacity());
                continue;
            }
            written += added.size();
            ok++;
            var owner = menu.ownerOf(site.hostKey());
            if (owner != null) {
                owners.add(owner);
            }
        }
        if (!owners.isEmpty()) {
            menu.afterCellMoved(owners.toArray());
        }
        return new WriteResult(written, ok, failedSites, lostItems);
    }

    /** 按目标名单改写一台元件：取放与失败回填走两个管理域共用的那一条（见 {@link CellHostSwap}）。 */
    private boolean apply(CellMarkDomain.Site site, List<AEKey> markers) {
        return CellHostSwap.rewrite(menu, site.hostKey(), site.hostSlot(), site.stack(), "快捷标记",
                modified -> site.view().write(modified, markers));
    }

    /** 统一出口：聊天栏给六个计数，日志给逐环诊断；受同一个配置开关约束。 */
    private void report(int written, int duplicate, int noRoom, int skippedNonItems,
            int targets, int candidates, String detail) {
        LOGGER.info("[cell-mgmt] quick mark: {} | written={} duplicate={} noRoom={} nonItems={} targets={} candidates={}",
                detail, written, duplicate, noRoom, skippedNonItems, targets, candidates);
        String noticeKey = CellManagementTermMenu.NOTICE + "quick_mark";
        if (menu.getPlayer() instanceof ServerPlayer serverPlayer
                && AEPDCommonConfig.isMessageShown(noticeKey)) {
            serverPlayer.sendSystemMessage(Component.translatable(noticeKey,
                    written, duplicate, noRoom, skippedNonItems, targets, candidates));
        }
    }
}
