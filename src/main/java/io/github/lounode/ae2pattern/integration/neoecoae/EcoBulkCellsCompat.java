package io.github.lounode.ae2pattern.integration.neoecoae;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.item.ItemStack;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.storage.cells.ISaveProvider;
import appeng.util.ConfigInventory;

import io.github.lounode.ae2pattern.common.menu.CellBulkCutoffCompat;

/**
 * NEO ECO 大宗元件的软依赖入口：只认字符串与反射，不 import 它的任何类型。
 *
 * <p>与 {@code MegaCellsCompat} 同一做法。要找的三条契约都在 {@code cn.dancingsnow.neoecoae.api.storage}：
 * {@code IECOBulkMarkableCellItem} 给标记容器，{@code IECOBulkDisplayCell} 给压缩截断调级，
 * {@code ECOStorageCells} 是取库存的入口——ECO 的元件不注册进 AE2 的 {@code StorageCells}，拿错入口会得到
 * null。前两条随 ECO 的大宗存储一起在 21.2.11 出现，所以旧版 ECO 上这里整体退场，终端不显示相关按钮。</p>
 *
 * <p><b>标记物就是截断层。</b>ECO 21.2.11 起，大宗元件每个非空标记槽里的物品本身就是该压缩链的截断点；
 * 调级不是改一个隐藏的显示值，而是把该槽的标记换成链上的上/下一级。所以 {@link #cycleCutoff} 调完
 * {@code cycleCompressionCutoff} 后会把返回值写回配置槽，调用方再落盘与广播。</p>
 *
 * <p><b>按链去重</b>：同一个压缩链只保留配置顺序里的第一个非空标记。ECO 没有公开“这条键属于哪条链”的
 * 查询，这里改用公开的 {@code cycleCompressionCutoff} 在元件副本上走一圈：从任意链成员出发连续前进，
 * 回到起点时集合就是整条链。副本既不动真元件，也不需要依赖 {@code impl} 包。</p>
 */
public final class EcoBulkCellsCompat implements CellBulkCutoffCompat.Adapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.neoecoae");

    private static final String MARKABLE_CELL = "cn.dancingsnow.neoecoae.api.storage.IECOBulkMarkableCellItem";
    private static final String DISPLAY_CELL = "cn.dancingsnow.neoecoae.api.storage.IECOBulkDisplayCell";
    private static final String STORAGE_CELLS = "cn.dancingsnow.neoecoae.api.storage.ECOStorageCells";
    /** 压缩链长度上限；现实链远短于此，超出只可能是上游改了语义。 */
    private static final int MAX_CHAIN_LENGTH = 128;

    private static volatile Boolean available;
    private static Class<?> markableCell;
    private static Class<?> displayCell;
    private static Method configInventory;
    private static Method cycleCutoff;
    private static Method getCellInventory;

    public EcoBulkCellsCompat() {
    }

    /** 这个 ECO 版本是否带大宗元件的标记与截断契约。 */
    public static boolean isAvailable() {
        var cached = available;
        if (cached == null) {
            cached = type(MARKABLE_CELL) != null && type(DISPLAY_CELL) != null;
            available = cached;
        }
        return cached;
    }

    /**
     * 打开一张大宗元件；不是大宗元件或契约缺失时 null。
     *
     * <p>这里只取标记容器（很轻），走链用的那份副本等第一次读名单时再建：调用方常常只要写一格，
     * 没必要为它先解析整张元件。</p>
     */
    @Override
    @Nullable
    public CellBulkCutoffCompat.Site open(ItemStack stack) {
        ConfigInventory config = configOf(stack);
        if (config == null || cycleMethod() == null) {
            return null;
        }
        return new Site(stack, config);
    }

    /** 名单与库存读同一份副本，调级与重写标记落真元件。 */
    private static final class Site implements CellBulkCutoffCompat.Site {

        private final ItemStack stack;
        private final ConfigInventory config;
        @Nullable
        private Object probe;
        @Nullable
        private List<CellBulkCutoffCompat.Marker> cached;
        /** 同一条链上的多个标记只走一次：走链是反射加内部落盘，按格各走一遍会成倍放大。 */
        private final Map<AEItemKey, Set<AEItemKey>> chains = new HashMap<>();

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
            this.cached = markers;
            return markers;
        }

        /** 元件自己的压缩账本解析一次不便宜，读来读去用同一份副本。 */
        @Nullable
        private Object probe() {
            if (probe == null) {
                probe = copyCellOf(stack);
            }
            return probe;
        }

        private List<CellBulkCutoffCompat.Marker> readMarkers() {
            Object probe = this.probe();
            Method cycle = cycleMethod();
            if (probe == null || cycle == null) {
                LOGGER.debug("[neoecoae] {} is a bulk markable cell without a display inventory;"
                        + " markers stay hidden", stack.getItem());
                return List.of();
            }
            List<CellBulkCutoffCompat.Marker> result = new ArrayList<>();
            for (int slot = 0; slot < config.size(); slot++) {
                if (!(config.getKey(slot) instanceof AEItemKey marker)) {
                    continue;
                }
                Set<AEItemKey> chain = chains.computeIfAbsent(marker, key -> chainOf(probe, cycle, key));
                result.add(new CellBulkCutoffCompat.Marker(slot, marker.toStack(), chain.size() > 1,
                        Set.copyOf(chain)));
            }
            return List.copyOf(result);
        }

        @Override
        public int capacity() {
            return config.size();
        }

        /** 读的是副本：查库存会顺带触发上游的账本恢复，别让它写到真元件上。 */
        @Override
        public Set<AEKey> storedKeys() {
            Object probe = this.probe();
            return probe instanceof MEStorage storage
                    ? EcoBulkCellsCompat.storedKeys(storage)
                    : Set.of();
        }

        /** 写过真元件，那份副本就旧了：下一次读重建。 */
        private void invalidate() {
            probe = null;
            cached = null;
        }

        /**
         * 把某一格的标记换成链上的上/下一级，并写回配置槽。
         *
         * @param delta 只接受 -1 / 1，与 ECO 自己的调级入口一致
         */
        @Override
        public boolean cycleCutoff(int slot, int delta) {
            if (delta != -1 && delta != 1) {
                return false;
            }
            Object cell = cellOf(stack);
            Method cycle = cycleMethod();
            if (cell == null || cycle == null || slot < 0 || slot >= config.size()) {
                return false;
            }
            if (!(config.getKey(slot) instanceof AEItemKey marker)) {
                return false;
            }
            try {
                Object next = cycle.invoke(cell, marker, delta);
                if (!(next instanceof AEItemKey selected)) {
                    return false;
                }
                config.setStack(slot, new GenericStack(selected, 0L));
                invalidate();
                return true;
            } catch (ReflectiveOperationException | RuntimeException broken) {
                LOGGER.debug("[neoecoae] bulk cutoff cycle failed", broken);
                return false;
            }
        }

        /** 整份重写标记：前 n 格写成这些物品，其余清空；一格都没变时返回 false。 */
        @Override
        public boolean setMarkers(List<ItemStack> markers) {
            boolean changed = rewrite(config, markers);
            if (changed) {
                invalidate();
            }
            return changed;
        }
    }

    /** 这台矩阵实际存着哪些物品键：读一次可用清单，链归属由调用方拿标记的链集合去比。 */
    private static Set<AEKey> storedKeys(MEStorage storage) {
        var counter = new KeyCounter();
        storage.getAvailableStacks(counter);
        var keys = new HashSet<AEKey>();
        for (var entry : counter) {
            if (entry.getLongValue() > 0L) {
                keys.add(entry.getKey());
            }
        }
        return keys;
    }

    /**
     * 按目标序列逐格比对后写入。
     *
     * <p>先比再写是必要的：ECO 的配置库存每写一格都会把整份列表落回元件一次，全列表重写一遍既慢又会
     * 在「其实没变」时白刷一次同步。</p>
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
     * 在元件副本上走完一条压缩链；返回值一定包含 {@code start}。
     *
     * <p>先走一步再开始收集：{@code cycleCompressionCutoff} 以元件记着的截断点为基准，而不是以传入的
     * {@code start} 为基准；从返回的那一格继续走一圈，整条链都会被收集到。</p>
     */
    private static Set<AEItemKey> chainOf(Object copyCell, Method cycle, AEItemKey start) {
        Set<AEItemKey> chain = new HashSet<>();
        try {
            Object first = cycle.invoke(copyCell, start, 1);
            if (!(first instanceof AEItemKey current)) {
                return Set.of(start);
            }
            for (int step = 0; step < MAX_CHAIN_LENGTH; step++) {
                if (!chain.add(current)) {
                    break;
                }
                Object next = cycle.invoke(copyCell, current, 1);
                if (!(next instanceof AEItemKey item)) {
                    break;
                }
                current = item;
            }
        } catch (ReflectiveOperationException | RuntimeException broken) {
            LOGGER.debug("[neoecoae] bulk compression chain probe failed", broken);
        }
        chain.add(start);
        return chain;
    }

    @Nullable
    private static ConfigInventory configOf(ItemStack stack) {
        if (stack.isEmpty() || !isAvailable()) {
            return null;
        }
        try {
            Object value = configInventory().invoke(stack.getItem(), stack);
            return value instanceof ConfigInventory config ? config : null;
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return null;
        }
    }

    @Nullable
    private static Object cellOf(ItemStack stack) {
        return displayInventory(stack);
    }

    @Nullable
    private static Object copyCellOf(ItemStack stack) {
        return displayInventory(stack.copy());
    }

    /**
     * 取这张元件的库存，且只认带大宗显示契约的那一种。
     *
     * <p>ECO 的元件不注册进 AE2 的 {@code StorageCells}，走的是自家的 {@code ECOStorageCells}；
     * 拿错入口会得到 null，表现就是终端里两枚按钮根本不出现。</p>
     */
    @Nullable
    private static Object displayInventory(ItemStack stack) {
        if (stack.isEmpty() || !isAvailable()) {
            return null;
        }
        try {
            Object cell = cellInventoryMethod().invoke(null, stack, null);
            return displayType().isInstance(cell) ? cell : null;
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return null;
        }
    }

    private static Method cellInventoryMethod() {
        if (getCellInventory == null) {
            try {
                getCellInventory = Class.forName(STORAGE_CELLS, false, EcoBulkCellsCompat.class.getClassLoader())
                        .getMethod("getCellInventory", ItemStack.class, ISaveProvider.class);
            } catch (ClassNotFoundException | NoSuchMethodException | LinkageError absent) {
                return null;
            }
        }
        return getCellInventory;
    }

    private static Class<?> markableType() {
        if (markableCell == null) {
            markableCell = type(MARKABLE_CELL);
        }
        return markableCell;
    }

    private static Class<?> displayType() {
        if (displayCell == null) {
            displayCell = type(DISPLAY_CELL);
        }
        return displayCell;
    }

    private static Method configInventory() {
        if (configInventory == null) {
            Class<?> markable = markableType();
            if (markable == null) {
                return null;
            }
            try {
                configInventory = markable.getMethod("getConfigInventory", ItemStack.class);
            } catch (NoSuchMethodException | RuntimeException absent) {
                return null;
            }
        }
        return configInventory;
    }

    private static Method cycleMethod() {
        if (cycleCutoff == null) {
            Class<?> display = displayType();
            if (display == null) {
                return null;
            }
            try {
                cycleCutoff = display.getMethod("cycleCompressionCutoff", AEItemKey.class, int.class);
            } catch (NoSuchMethodException | RuntimeException absent) {
                return null;
            }
        }
        return cycleCutoff;
    }

    @Nullable
    private static Class<?> type(String name) {
        try {
            return Class.forName(name, false, EcoBulkCellsCompat.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }
}
