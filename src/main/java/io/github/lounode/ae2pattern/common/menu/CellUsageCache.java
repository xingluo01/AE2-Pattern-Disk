package io.github.lounode.ae2pattern.common.menu;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import appeng.api.storage.MEStorage;
import appeng.me.cells.BasicCellInventory;
import appeng.me.storage.DriveWatcher;

/** 菜单会话内的占用统计。常见元件读已有计数，未知实现的全量枚举按扫描轮次分摊。 */
public final class CellUsageCache {
    private static final long REFRESH_TICKS = 60;
    private final Map<MEStorage, Cached> cached = new IdentityHashMap<>();
    private final ArrayList<Pending> pending = new ArrayList<>();
    private long tick;
    private boolean allowEnumeration;

    public record Usage(float types, float bytes) {
        private static final Usage EMPTY = new Usage(0, 0);
    }

    private record Cached(long tick, Usage usage) {}
    private record Pending(MEStorage storage, long totalTypes, long totalBytes) {}

    /** 反射只用于可选模组的统计方法；不存在的方法也按类缓存，避免反复创建查找异常。 */
    private static final ClassValue<StatsMethods> METHODS = new ClassValue<>() {
        @Override protected StatsMethods computeValue(Class<?> type) {
            try {
                return new StatsMethods(type.getMethod("getStoredItemTypes"), type.getMethod("getTotalItemTypes"),
                        type.getMethod("getUsedBytes"), type.getMethod("getTotalBytes"));
            } catch (ReflectiveOperationException | RuntimeException absent) {
                return new StatsMethods(null, null, null, null);
            }
        }
    };

    private record StatsMethods(@Nullable Method usedTypes, @Nullable Method totalTypes,
            @Nullable Method usedBytes, @Nullable Method totalBytes) {
        @Nullable Usage read(MEStorage storage) {
            if (usedTypes == null) return null;
            try {
                return usage(((Number) usedTypes.invoke(storage)).longValue(),
                        ((Number) totalTypes.invoke(storage)).longValue(),
                        ((Number) usedBytes.invoke(storage)).longValue(),
                        ((Number) totalBytes.invoke(storage)).longValue());
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                return null;
            }
        }
    }

    public void beginScan(long tick, boolean allowEnumeration) {
        this.tick = tick;
        this.allowEnumeration = allowEnumeration;
        pending.clear();
    }

    public Usage read(MEStorage storage, long totalTypes, long totalBytes) {
        if (storage instanceof DriveWatcher watcher) storage = watcher.getCell();
        if (storage instanceof BasicCellInventory basic) {
            return usage(basic.getStoredItemTypes(), basic.getTotalItemTypes(), basic.getUsedBytes(), basic.getTotalBytes());
        }
        Usage direct = METHODS.get(storage.getClass()).read(storage);
        if (direct != null) return direct;
        if (totalTypes <= 0 && totalBytes <= 0) return Usage.EMPTY;
        pending.add(new Pending(storage, totalTypes, totalBytes));
        Cached previous = cached.get(storage);
        return previous == null ? Usage.EMPTY : previous.usage();
    }

    /** 每轮最多枚举一个未知元件；最久未采样的优先，避免大网络里后面的元件一直轮不到。 */
    public void endScan() {
        Map<MEStorage, Boolean> active = new IdentityHashMap<>();
        Pending oldest = null;
        long oldestTick = Long.MAX_VALUE;
        for (Pending candidate : pending) {
            active.put(candidate.storage(), Boolean.TRUE);
            Cached previous = cached.get(candidate.storage());
            long sampled = previous == null ? Long.MIN_VALUE : previous.tick();
            if ((previous == null || tick - sampled >= REFRESH_TICKS) && (oldest == null || sampled < oldestTick)) {
                oldest = candidate;
                oldestTick = sampled;
            }
        }
        cached.keySet().retainAll(active.keySet());
        if (!allowEnumeration || oldest == null) return;
        try {
            var contents = oldest.storage().getAvailableStacks();
            long bytes = 0;
            for (var entry : contents) {
                long amount = Math.max(0, entry.getLongValue());
                int perByte = Math.max(1, entry.getKey().getType().getAmountPerByte());
                long next = amount / perByte + (amount % perByte == 0 ? 0 : 1);
                bytes = bytes > Long.MAX_VALUE - next ? Long.MAX_VALUE : bytes + next;
            }
            cached.put(oldest.storage(), new Cached(tick,
                    usage(contents.size(), oldest.totalTypes(), bytes, oldest.totalBytes())));
        } catch (RuntimeException unavailable) {
            // 一台第三方元件读失败仍要让出下一轮，保留上次成功的占用值。
            Cached previous = cached.get(oldest.storage());
            cached.put(oldest.storage(), new Cached(tick, previous == null ? Usage.EMPTY : previous.usage()));
        }
    }

    private static Usage usage(long usedTypes, long totalTypes, long usedBytes, long totalBytes) {
        return new Usage(ratio(usedTypes, totalTypes), ratio(usedBytes, totalBytes));
    }

    private static float ratio(long used, long total) {
        return total <= 0 ? 0 : (float) Math.clamp((double) used / total, 0, 1);
    }
}
