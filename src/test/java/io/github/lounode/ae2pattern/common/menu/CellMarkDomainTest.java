package io.github.lounode.ae2pattern.common.menu;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.world.item.ItemStack;

import appeng.api.stacks.AEKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 标记管理域的纯逻辑：目标白名单、数量筛选、覆盖键并集、填充顺序与空位查找。
 *
 * <p>视图用替身，不构造真的元件栈：这些只关心站点上的名单与标记，与宿主取放无关。
 * 真正的取放写回要实机验证，不在这份测试里。</p>
 */
class CellMarkDomainTest {

    private static AEKey key() {
        return mock(AEKey.class);
    }

    private static CellMarkDomain.Site site(boolean bulk, Integer hostPriority, Set<AEKey> covered) {
        var view = mock(CellMarkDomain.View.class);
        when(view.coveredKeys()).thenReturn(covered);
        when(view.bulk()).thenReturn(bulk);
        when(view.trusted()).thenReturn(true);
        return new CellMarkDomain.Site("host", 0, mock(ItemStack.class),
                view, 0, hostPriority, false);
    }

    @Test
    void onlyTheTwoSingularityCellsAreMarkTargets() {
        assertTrue(CellMarkDomain.isMarkTarget("neoecoae:eco_digital_singularity_item_storage_cell"));
        assertTrue(CellMarkDomain.isMarkTarget("bigger_ae2:digital_singularity_item_storage_cell"));
        // 同族其它通道、旧 MEGA 大宗盘与普通 AE2 元件都不能被快捷标记改写。
        for (String other : List.of(
                "neoecoae:eco_digital_singularity_fluid_storage_cell",
                "neoecoae:eco_mega_long_bulk_cell",
                "ae2:item_storage_cell_1k",
                "ae2:creative_cell",
                "bigger_ae2:quantum_item_storage_cell",
                "")) {
            assertEquals(false, CellMarkDomain.isMarkTarget(other), other);
        }
    }

    @Test
    void amountFilterSkipsThresholdAndSentinelValues() {
        long threshold = 20_000L;
        assertEquals(false, CellMarkDomain.acceptsAmount(0, threshold));
        assertEquals(false, CellMarkDomain.acceptsAmount(threshold, threshold));
        assertTrue(CellMarkDomain.acceptsAmount(threshold + 1, threshold));
        // 2.1G 与 9.2E：无限 / 虚拟存储的报数，不能占标记格。
        assertFalse(CellMarkDomain.acceptsAmount(Integer.MAX_VALUE, threshold));
        assertFalse(CellMarkDomain.acceptsAmount(Long.MAX_VALUE, threshold));
        assertTrue(CellMarkDomain.isSentinelAmount(Integer.MAX_VALUE));
        assertTrue(CellMarkDomain.isSentinelAmount(Long.MAX_VALUE));
        // 21 亿以上的真实库存仍按正常大宗物品处理。
        assertTrue(CellMarkDomain.acceptsAmount((long) Integer.MAX_VALUE + 1, threshold));
        assertTrue(CellMarkDomain.acceptsAmount(Long.MAX_VALUE - 1, threshold));
        assertFalse(CellMarkDomain.isSentinelAmount(2_000_000_000L));
    }

    @Test
    void coveredKeysIsTheUnionOfEverySite() {
        var a = key();
        var b = key();
        var c = key();
        // 压缩矩阵按整条链展开；普通元件只报自己标过的那几个键。
        var sites = List.of(
                site(true, 10, Set.of(a, b)),
                site(false, 5, Set.of(b, c)),
                site(false, null, Set.of(c)));

        assertEquals(Set.of(a, b, c), CellMarkDomain.coveredKeys(sites));
    }

    @Test
    void targetOrderPutsBulkFirstThenHigherPriority() {
        var sites = List.of(
                site(false, 100, Set.of()),  // 0：普通元件，优先级最高
                site(true, 1, Set.of()),    // 1：压缩矩阵，优先级最低
                site(false, null, Set.of()), // 2：普通元件，优先级读不到
                site(true, 50, Set.of()),   // 3：压缩矩阵，优先级高
                site(false, 5, Set.of()));  // 4：普通元件

        assertEquals(List.of(3, 1, 0, 4, 2), CellMarkDomain.targetOrder(sites));
    }

    @Test
    void firstTargetFollowsOrderAndSkipsFullSites() {
        var order = List.of(2, 0, 1);
        assertEquals(2, CellMarkDomain.firstTarget(order, new int[] { 0, 0, 3 }));
        // 第一台装满后自然轮到下一台，不按元件类型挑。
        assertEquals(0, CellMarkDomain.firstTarget(order, new int[] { 2, 0, 0 }));
        assertEquals(1, CellMarkDomain.firstTarget(order, new int[] { 0, 1, 0 }));
        assertEquals(-1, CellMarkDomain.firstTarget(order, new int[] { 0, 0, 0 }));
    }
}
