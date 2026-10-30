package io.github.lounode.ae2pattern.common.logic;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.LoadingModList;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 合并发配的合并步：把按槽的到货清单收成按 key 的总量。
 *
 * <p>AE2 递来的输入是「一槽一个 {@link KeyCounter}」，而一份合成常常拿同一种物品占好几格——9:1 的压缩配方
 * 就是九格同一种东西。元件是按 key 记账的，逐槽写入会把同一种物品在元件里重写九遍。这里固定的就是
 * 「同一种物品只算一次」这条规则本身；跨 tick 的攒批要另算元件容量，不在这一层。</p>
 */
class CellBufferMergeTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        try (var loading = mockStatic(LoadingModList.class)) {
            var mods = mock(LoadingModList.class);
            when(mods.getModFiles()).thenReturn(List.of());
            loading.when(LoadingModList::get).thenReturn(mods);
            Bootstrap.bootStrap();
        }
    }

    private static KeyCounter slot(AEKey key, long amount) {
        var counter = new KeyCounter();
        counter.set(key, amount);
        return counter;
    }

    @Test
    void oneKeyAcrossSlotsBecomesOneEntry() {
        var cobble = AEItemKey.of(Items.COBBLESTONE);
        var perSlot = new KeyCounter[9];
        for (int i = 0; i < 9; i++) {
            perSlot[i] = slot(cobble, 1);
        }

        var totals = CellBuffer.totalsByKey(perSlot);

        assertEquals(1, totals.size());
        assertEquals(9L, totals.get(cobble));
    }

    @Test
    void differentKeysKeepTheirOwnTotals() {
        var cobble = AEItemKey.of(Items.COBBLESTONE);
        var deepslate = AEItemKey.of(Items.DEEPSLATE);

        var totals = CellBuffer.totalsByKey(new KeyCounter[] {
                slot(cobble, 3), slot(deepslate, 5), slot(cobble, 4),
        });

        assertEquals(2, totals.size());
        assertEquals(7L, totals.get(cobble));
        assertEquals(5L, totals.get(deepslate));
    }

    @Test
    void flatTotalsArePassedThroughUnchanged() {
        var cobble = AEItemKey.of(Items.COBBLESTONE);
        var whole = new KeyCounter();
        whole.add(cobble, 900L);

        var totals = CellBuffer.totalsByKey(new KeyCounter[] { whole });

        assertEquals(900L, totals.get(cobble));
    }

    @Test
    void emptyAndNonPositiveEntriesAreDropped() {
        var cobble = AEItemKey.of(Items.COBBLESTONE);

        var totals = CellBuffer.totalsByKey(new KeyCounter[] {
                null, new KeyCounter(), slot(cobble, 0), slot(cobble, -7L), slot(cobble, 2L),
        });

        assertEquals(1, totals.size());
        assertEquals(2L, totals.get(cobble));
    }

    @Test
    void nothingAtAllYieldsEmptyTotals() {
        assertTrue(CellBuffer.totalsByKey(new KeyCounter[0]).isEmpty());
        assertTrue(CellBuffer.totalsByKey(new KeyCounter[] { new KeyCounter() }).isEmpty());
    }

    @Test
    void sumsSaturateInsteadOfWrappingAround() {
        var cobble = AEItemKey.of(Items.COBBLESTONE);

        var totals = CellBuffer.totalsByKey(new KeyCounter[] {
                slot(cobble, Long.MAX_VALUE), slot(cobble, Long.MAX_VALUE),
        });

        // A wrapped total would read as "there is still room" and let a refusal become an acceptance.
        assertEquals(Long.MAX_VALUE, totals.get(cobble));
    }
}
