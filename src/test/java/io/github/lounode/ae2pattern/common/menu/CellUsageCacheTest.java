package io.github.lounode.ae2pattern.common.menu;

import org.junit.jupiter.api.Test;

import net.minecraft.network.chat.Component;

import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;

import static org.junit.jupiter.api.Assertions.*;

class CellUsageCacheTest {
    /** 模拟有汇总统计接口的可选元件；枚举一旦被调用就失败。 */
    public static class SummarizedStorage implements MEStorage {
        public long getStoredItemTypes() { return 2; }
        public long getTotalItemTypes() { return 8; }
        public long getUsedBytes() { return 512; }
        public long getTotalBytes() { return 1024; }
        @Override public void getAvailableStacks(KeyCounter out) { fail("不应枚举已有统计的元件"); }
        @Override public Component getDescription() { return Component.empty(); }
    }

    public static class UnknownStorage implements MEStorage {
        int enumerations;
        @Override public void getAvailableStacks(KeyCounter out) { enumerations++; }
        @Override public Component getDescription() { return Component.empty(); }
    }

    @Test void summarizedStorageNeverEnumeratesContents() {
        var cache = new CellUsageCache();
        cache.beginScan(0, false);
        assertEquals(new CellUsageCache.Usage(.25f, .5f), cache.read(new SummarizedStorage(), 0, 0));
        cache.endScan();
    }

    @Test void openingAndClicksDoNotEnumerateUnknownCells() {
        var cache = new CellUsageCache();
        var storage = new UnknownStorage();
        cache.beginScan(0, false);
        cache.read(storage, 8, 1024);
        cache.endScan();
        assertEquals(0, storage.enumerations);
    }

    @Test void unknownCellsHaveBoundedWorkAndDoNotStarve() {
        var cache = new CellUsageCache();
        var cells = new UnknownStorage[12];
        for (int i = 0; i < cells.length; i++) cells[i] = new UnknownStorage();
        for (int scan = 0; scan < cells.length; scan++) {
            cache.beginScan(scan * 10L, true);
            for (var cell : cells) cache.read(cell, 8, 1024);
            cache.endScan();
            int total = 0;
            for (var cell : cells) total += cell.enumerations;
            assertEquals(scan + 1, total);
        }
        for (var cell : cells) assertEquals(1, cell.enumerations);
    }

    @Test void cachedUnknownCellIsNotResampledBeforeExpiry() {
        var cache = new CellUsageCache();
        var storage = new UnknownStorage();
        for (long tick : new long[]{0, 10, 59, 60}) {
            cache.beginScan(tick, true);
            cache.read(storage, 8, 1024);
            cache.endScan();
        }
        assertEquals(2, storage.enumerations);
    }

    @Test void failedCellDoesNotPreventOtherCellsFromBeingSampled() {
        var cache = new CellUsageCache();
        var broken = new UnknownStorage() {
            @Override public void getAvailableStacks(KeyCounter out) { throw new IllegalStateException("offline"); }
        };
        var healthy = new UnknownStorage();
        for (int tick = 0; tick <= 10; tick += 10) {
            cache.beginScan(tick, true);
            cache.read(broken, 8, 1024);
            cache.read(healthy, 8, 1024);
            cache.endScan();
        }
        assertEquals(1, healthy.enumerations);
    }

    @Test void removedStorageDoesNotRetainASnapshotWhenReinserted() {
        var cache = new CellUsageCache();
        var storage = new UnknownStorage();
        cache.beginScan(0, true);
        cache.read(storage, 8, 1024);
        cache.endScan();
        cache.beginScan(10, true);
        cache.endScan();
        cache.beginScan(20, true);
        cache.read(storage, 8, 1024);
        cache.endScan();
        assertEquals(2, storage.enumerations);
    }
}
