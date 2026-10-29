package io.github.lounode.ae2pattern.integration.neoecoae;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.util.ConfigInventory;

import cn.dancingsnow.neoecoae.api.storage.ECOStorageCells;
import cn.dancingsnow.neoecoae.api.storage.IECOBulkDisplayCell;
import cn.dancingsnow.neoecoae.api.storage.IECOBulkMarkableCellItem;
import cn.dancingsnow.neoecoae.api.storage.IECOStorageCell;

import io.github.lounode.ae2pattern.common.menu.CellBulkCutoffCompat;

/**
 * NEO ECO 大宗元件的压缩截断桥，编译期用上游公开契约。
 *
 * <p>地板 21.2.12：那两个查询（{@code getCompressionChain} / {@code getCompressionCutoff}，上游 PR #141）
 * 只在这个版本起才随发布件提供，本模组的 {@code mods.toml} 因此把 {@code neoecoae} 限制在 {@code [21.2.12,)}。
 * 更早的 ECO 上没有可退化的路径——压缩链只能靠反复调截断点试探，那条反射层已随之删除。</p>
 *
 * <p>载体不写死：判定只看 {@link IECOBulkMarkableCellItem}（物品面）与 {@link IECOBulkDisplayCell}（实例面），
 * 所以 ECO 的大宗盘（含 MEGA 联动那批）与 21.2.12 新出的数字奇点物品存储矩阵走同一条路；后者由 ECO 带
 * {@code @Integration("bigger_ae2")} 的集成只在 Bigger AE2 在场时注册。MEGA Cells 自己那枚截断按钮仍走
 * {@code MegaCellsCompat}，不经过本类。</p>
 *
 * <p><b>读走副本</b>：上游查生效截断会顺带做历史账户恢复并落盘，所以名单与库存都读 {@code stack.copy()}；
 * 只有调级必须落在真元件上——它要把截断点记进元件自己的账本。</p>
 *
 * <p><b>上游调用一律被接住</b>：删掉反射层时也删掉了它每个入口的 catch，而名单是终端周期性刷新的。现在读侧
 * 退化成「没有标记」、写侧退化成「没改动过」，异常只报一次；不接住就是每刷一次抛一遍。</p>
 */
public final class EcoBulkCells implements CellBulkCutoffCompat.Adapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.neoecoae");

    /** 周期性刷新不能每刷一行；进程内只留一条 WARN，之后降到 DEBUG。 */
    private static volatile boolean warned;
    /** noteOnce 的去重键：元件 id + 原因。 */
    private static final Set<String> NOTED = ConcurrentHashMap.newKeySet();


    @Override
    @Nullable
    public CellBulkCutoffCompat.Site open(ItemStack stack) {
        if (!(stack.getItem() instanceof IECOBulkMarkableCellItem item)) {
            noteOnce(stack, "物品不是 IECOBulkMarkableCellItem");
            return null;
        }
        ConfigInventory config;
        try {
            config = item.getConfigInventory(stack);
        } catch (RuntimeException | LinkageError broken) {
            report("the ECO bulk marker container", broken);
            return null;
        }
        if (config == null) {
            noteOnce(stack, "getConfigInventory 返回 null");
            return null;
        }
        if (config.size() == 0) {
            noteOnce(stack, "配置格数为 0");
        }
        return new Site(stack, config);
    }

    /** 名单与库存读同一份副本，调级与重写标记落真元件。 */
    private static final class Site implements CellBulkCutoffCompat.Site {

        private final ItemStack stack;
        private final ConfigInventory config;
        @Nullable
        private IECOBulkDisplayCell probe;
        @Nullable
        private List<CellBulkCutoffCompat.Marker> cached;
        /** 有任何一次上游读失败就置位：名单与库存都不再可信。 */
        private boolean degraded;

        private Site(ItemStack stack, ConfigInventory config) {
            this.stack = stack;
            this.config = config;
        }

        @Override
        public List<CellBulkCutoffCompat.Marker> markers() {
            return CellBulkCutoffCompat.dedupeByChain(rawMarkers());
        }

        @Override
        public List<CellBulkCutoffCompat.Marker> rawMarkers() {
            var cached = this.cached;
            if (cached != null) {
                return cached;
            }
            List<CellBulkCutoffCompat.Marker> markers = readMarkers();
            // 读失败不写缓存：那一次抖动不该把这台矩阵的按钮清空到下次写过标记为止。
            if (markers != null) {
                this.cached = markers;
                return markers;
            }
            return List.of();
        }

        /**
         * ECO 的元件不注册进 AE2 的 {@code StorageCells}，拿错入口会得到 null。读的一律是副本。
         *
         * <p>取库存本身也是上游代码，所以在这一个入口里接住：失败时返回 null 而不落缓存，下次照试。</p>
         */
        @Nullable
        private IECOBulkDisplayCell probe() {
            var cached = probe;
            if (cached != null) {
                return cached;
            }
            try {
                Object inventory = ECOStorageCells.getCellInventory(stack.copy(), null);
                probe = inventory instanceof IECOBulkDisplayCell cell ? cell : null;
                if (probe == null) {
                    // 拿到的不是大宗显示面：说不出这张盘有没有标记，按不可信处理。
                    degraded = true;
                }
                if (probe == null) {
                    noteOnce(stack, "getCellInventory 不是 IECOBulkDisplayCell："
                            + (inventory == null ? "null" : inventory.getClass().getName()));
                }
            } catch (RuntimeException | LinkageError broken) {
                report("the ECO cell inventory read", broken);
                degraded = true;
                return null;
            }
            return probe;
        }

        /** @return 名单；上游查询失败时 null（调用方据此决定不缓存）。 */
        @Nullable
        private List<CellBulkCutoffCompat.Marker> readMarkers() {
            List<CellBulkCutoffCompat.Marker> result = new ArrayList<>();
            try {
                IECOBulkDisplayCell probe = probe();
                if (probe == null) {
                    return List.of();
                }
                for (int slot = 0; slot < config.size(); slot++) {
                    if (!(config.getKey(slot) instanceof AEItemKey marker)) {
                        continue;
                    }
                    List<AEItemKey> chain = probe.getCompressionChain(marker);
                    if (chain == null || chain.isEmpty()) {
                        chain = List.of(marker);
                    }
                    // 显示的是这条链真正生效的截断层：内部记录优先，否则才是配置里的标记物。
                    var cutoff = probe.getCompressionCutoff(marker);
                    result.add(new CellBulkCutoffCompat.Marker(slot,
                            cutoff == null ? marker.toStack() : cutoff.toStack(),
                            chain.size() > 1, Set.copyOf(chain)));
                }
            } catch (RuntimeException | LinkageError broken) {
                // 含第三方实现返回重复链成员时 Set.copyOf 的 IllegalArgumentException。
                report("the ECO compression chain query", broken);
                degraded = true;
                return null;
            }
            if (result.isEmpty()) {
                noteOnce(stack, "没有已配置的标记（格数 " + config.size() + "）");
            }
            return List.copyOf(result);
        }

        @Override
        public int storagePriority() {
            return probe() instanceof IECOStorageCell cell ? cell.getStoragePriority() : 0;
        }

        @Override
        public int capacity() {
            return config.size();
        }
        @Override
        public boolean trusted() {
            return !degraded;
        }


        @Override
        public boolean cycleCutoff(int markerSlot, int delta) {
            if (delta != -1 && delta != 1) {
                return false;
            }
            if (markerSlot < 0 || markerSlot >= config.size()) {
                return false;
            }
            if (!(config.getKey(markerSlot) instanceof AEItemKey marker)) {
                return false;
            }
            AEItemKey selected;
            try {
                // 调级必须落真元件，所以这里不取副本；写格会由上游的改动回调自己落盘。
                if (!(ECOStorageCells.getCellInventory(stack, null) instanceof IECOBulkDisplayCell display)) {
                    return false;
                }
                selected = display.cycleCompressionCutoff(marker, delta);
                if (selected == null) {
                    return false;
                }
                config.setStack(markerSlot, new GenericStack(selected, 0L));
            } catch (RuntimeException | LinkageError broken) {
                report("the ECO compression cutoff cycle", broken);
                degraded = true;
                return false;
            }
            invalidate();
            return true;
        }

        @Override
        public boolean setMarkers(List<ItemStack> markers) {
            boolean changed;
            try {
                changed = rewrite(config, markers);
            } catch (RuntimeException | LinkageError broken) {
                report("the ECO bulk marker rewrite", broken);
                return false;
            }
            if (changed) {
                invalidate();
            }
            return changed;
        }

        /** 写过真元件，那份副本就旧了：下一次读重建。 */
        private void invalidate() {
            probe = null;
            cached = null;
        }
    }

    /**
     * 按目标序列逐格比对后写入。
     *
     * <p>先比再写是必要的：ECO 的配置库存每写一格都会把整份列表落回元件一次，全列表重写一遍既慢又会
     * 在「其实没变」时白刷一次同步。中途抛错时前面几格已经落盘，调用方按「没改」处理即可，不会再播一次同步。</p>
     */
    static boolean rewrite(ConfigInventory config, List<ItemStack> markers) {
        if (markers.size() > config.size()) {
            // 目标名单超出格数时整份不写：写一半等于静默丢标记。
            return false;
        }
        boolean changed = false;
        for (int slot = 0; slot < config.size(); slot++) {
            GenericStack target = targetAt(markers, slot);
            if (sameMarker(config.getKey(slot), target)) {
                continue;
            }
            config.setStack(slot, target);
            changed = true;
        }
        return changed;
    }

    @Nullable
    private static GenericStack targetAt(List<ItemStack> markers, int slot) {
        if (slot >= markers.size()) {
            return null;
        }
        AEItemKey key = AEItemKey.of(markers.get(slot));
        return key == null ? null : new GenericStack(key, 0L);
    }

    /** 这一格要不要重写：两边都空算相同，否则比键（标记恒为物品键，数量恒为 0）。 */
    static boolean sameMarker(@Nullable AEKey have, @Nullable GenericStack want) {
        if (have == null || want == null) {
            return have == null && want == null;
        }
        return have.equals(want.what());
    }

    /**
     * 现场诊断：ECO 家族的元件进了判定链却拿不到按钮时，把卡在哪一环记一行。
     *
     * <p>判定是每帧跑的，所以按「元件 id + 原因」去重；只对 ECO 家族记，别的模组的元件不刷屏。</p>
     */
    private static void noteOnce(ItemStack stack, String reason) {
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!"neoecoae".equals(id.getNamespace()) && !"neoecoprototype".equals(id.getNamespace())) {
            return;
        }
        if (!NOTED.add(id + " | " + reason)) {
            return;
        }
        LOGGER.info("[AE2-Pattern-Disk] ECO bulk probe: {} -> {} (item class {})",
                id, reason, stack.getItem().getClass().getName());
    }

    /** 上游坏了不该拖垮终端：退化成「没有标记 / 没改动过」，并只报第一次。 */
    private static void report(String what, Throwable broken) {
        if (warned) {
            LOGGER.debug("[neoecoae] {} failed again", what, broken);
            return;
        }
        warned = true;
        LOGGER.warn("[AE2-Pattern-Disk] {} failed; the ECO bulk buttons degrade to empty", what, broken);
    }
}
