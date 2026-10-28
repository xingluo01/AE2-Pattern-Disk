package io.github.lounode.ae2pattern.integration.neoecoae;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.ItemStack;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.util.ConfigInventory;

import cn.dancingsnow.neoecoae.api.storage.ECOStorageCells;
import cn.dancingsnow.neoecoae.api.storage.IECOBulkDisplayCell;
import cn.dancingsnow.neoecoae.api.storage.IECOBulkMarkableCellItem;
import cn.dancingsnow.neoecoae.api.storage.IECOStorageCell;

import io.github.lounode.ae2pattern.common.menu.CellBulkCutoffCompat;

/**
 * ECO 大宗元件的 API 适配器：用上游新增的 {@code getCompressionChain} 做同链去重，
 * 用 {@code getCompressionCutoff} 显示这条链真正生效的截断层。
 *
 * <p>这个源目录只在 {@code -PecoApiJar=<patched ECO jar>} 时编进源集；普通构建没有该 API，
 * 由 {@link EcoBulkCellsCompat} 走反射退场。上游发版后把本文件移进 {@code src/main/java}，
 * 并把构建坐标升到那个版本。</p>
 *
 * <p>生效截断的查询在上游那边会顺带做历史账户恢复并落盘，所以名单与库存都读副本；
 * 只有调级必须落在真元件上——它要把截断点记进元件自己的账本。</p>
 */
public final class EcoBulkCellsApi implements CellBulkCutoffCompat.Adapter {

    @Override
    @Nullable
    public CellBulkCutoffCompat.Site open(ItemStack stack) {
        if (!(stack.getItem() instanceof IECOBulkMarkableCellItem item)) {
            return null;
        }
        ConfigInventory config = item.getConfigInventory(stack);
        return new Site(stack, config);
    }

    /** 读走 {@code stack} 的一份副本，写落真元件；副本等第一次读名单时再建。 */
    private static final class Site implements CellBulkCutoffCompat.Site {

        private final ItemStack stack;
        private final ConfigInventory config;
        @Nullable
        private IECOBulkDisplayCell probe;
        @Nullable
        private List<CellBulkCutoffCompat.Marker> cached;

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

        /** ECO 的元件不注册进 AE2 的 {@code StorageCells}，拿错入口会得到 null。读的一律是副本。 */
        @Nullable
        private IECOBulkDisplayCell probe() {
            if (probe == null) {
                probe = ECOStorageCells.getCellInventory(stack.copy(), null) instanceof IECOBulkDisplayCell cell
                        ? cell
                        : null;
            }
            return probe;
        }

        private List<CellBulkCutoffCompat.Marker> readMarkers() {
            IECOBulkDisplayCell probe = probe();
            if (probe == null) {
                return List.of();
            }
            List<CellBulkCutoffCompat.Marker> result = new ArrayList<>();
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
                result.add(new CellBulkCutoffCompat.Marker(slot, cutoff == null ? marker.toStack() : cutoff.toStack(),
                        chain.size() > 1, Set.copyOf(chain)));
            }
            return List.copyOf(result);
        }

        @Override
        public int capacity() {
            return config.size();
        }

        @Override
        public Set<AEKey> storedKeys() {
            if (!(probe() instanceof IECOStorageCell storage)) {
                return Set.of();
            }
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

        @Override
        public boolean cycleCutoff(int markerSlot, int delta) {
            if (delta != -1 && delta != 1) {
                return false;
            }
            if (!(ECOStorageCells.getCellInventory(stack, null) instanceof IECOBulkDisplayCell display)) {
                return false;
            }
            if (markerSlot < 0 || markerSlot >= config.size()) {
                return false;
            }
            if (!(config.getKey(markerSlot) instanceof AEItemKey marker)) {
                return false;
            }
            AEItemKey selected = display.cycleCompressionCutoff(marker, delta);
            if (selected == null) {
                return false;
            }
            config.setStack(markerSlot, new GenericStack(selected, 0L));
            invalidate();
            return true;
        }

        /** 写过真元件，那份副本就旧了：下一次读重建。 */
        private void invalidate() {
            probe = null;
            cached = null;
        }

        /** 整份重写标记；比对与写入的口径与反射层共用同一份（见 {@link EcoBulkCellsCompat#rewrite}）。 */
        @Override
        public boolean setMarkers(List<ItemStack> markers) {
            boolean changed = EcoBulkCellsCompat.rewrite(config, markers);
            if (changed) {
                invalidate();
            }
            return changed;
        }
    }
}
