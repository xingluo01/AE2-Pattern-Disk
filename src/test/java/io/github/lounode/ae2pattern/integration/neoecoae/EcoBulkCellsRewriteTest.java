package io.github.lounode.ae2pattern.integration.neoecoae;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.LoadingModList;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypesInternal;
import appeng.api.stacks.GenericStack;
import appeng.util.ConfigInventory;

import io.github.lounode.ae2pattern.common.menu.cell.CellBulkCutoffCompat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 大宗矩阵标记名单的纯逻辑：逐格比对、整份写入与回滚、按压缩链去重。
 *
 * <p>这三个入口都不碰 ECO 的类型，只认 AE2 的 {@link ConfigInventory} 与 {@link GenericStack}，
 * 所以不必把可选依赖拉进测试运行期；缺 ECO 时它们照样按契约工作。</p>
 */
class EcoBulkCellsRewriteTest {

    @BeforeAll
    static void bootstrap() throws ReflectiveOperationException {
        SharedConstants.tryDetectVersion();
        try (var loading = mockStatic(LoadingModList.class)) {
            var mods = mock(LoadingModList.class);
            when(mods.getModFiles()).thenReturn(List.of());
            loading.when(LoadingModList::get).thenReturn(mods);
            Bootstrap.bootStrap();
        }
        // ConfigInventory 的静态初始化会读 AEKeyTypesInternal.allTypes；单测没有模组启动流程，
        // 手动把物品 key type 放进去，否则第一次碰 ConfigInventory 就抛 "AE2 isn't initialized yet"。
        var allTypes = AEKeyTypesInternal.class.getDeclaredField("allTypes");
        allTypes.setAccessible(true);
        allTypes.set(null, Set.of(AEItemKey.of(new ItemStack(Items.DIAMOND)).getType()));
    }

    /** 与 ECO 的 createPreservingConfigInventory 同形：标记恒为 amount=0，configStacks 会把已占格的这种写入当成清格。 */
    private static ConfigInventory config(int size) {
        return ConfigInventory.configTypes(size).supportedTypes(Set.of(AEKeyType.items())).build();
    }

    private static ItemStack item(net.minecraft.world.item.Item source) {
        return new ItemStack(source);
    }

    private static GenericStack stack(ItemStack source) {
        return new GenericStack(AEItemKey.of(source), 0L);
    }

    /**
     * 指定格写不进去的库存：模拟上游过滤器的静默拒收（调了 setStack，回读还是旧值）。
     *
     * <p>不用 {@code slotFilter}：它只作用于菜单包装，{@code ConfigInventory.setStack} 根本不读它。</p>
     */
    private static ConfigInventory refusingSlot(int size, int slot) {
        var config = spy(config(size));
        doNothing().when(config).setStack(eq(slot), any());
        return config;
    }

    @Test
    void sameMarkerTreatsBothEmptyAsEqual() {
        assertTrue(EcoBulkCells.sameMarker(null, null));
        assertFalse(EcoBulkCells.sameMarker(AEItemKey.of(item(Items.DIAMOND)), null));
        assertFalse(EcoBulkCells.sameMarker(null, stack(item(Items.DIAMOND))));
    }

    @Test
    void sameMarkerComparesKeyOnly() {
        var diamond = AEItemKey.of(item(Items.DIAMOND));
        assertTrue(EcoBulkCells.sameMarker(diamond, stack(item(Items.DIAMOND))));
        assertFalse(EcoBulkCells.sameMarker(diamond, stack(item(Items.IRON_INGOT))));
    }

    @Test
    void rewriteRejectsOversizedListWithoutTouchingConfig() {
        var config = config(2);
        config.setStack(0, stack(item(Items.DIAMOND)));
        var tooMany = List.of(item(Items.IRON_INGOT), item(Items.GOLD_INGOT), item(Items.EMERALD));
        assertFalse(EcoBulkCells.rewrite(config, tooMany));
        assertEquals(AEItemKey.of(item(Items.DIAMOND)), config.getKey(0));
        assertNull(config.getKey(1));
    }

    @Test
    void rewriteWritesMarkersInOrderAndReportsChange() {
        var config = config(3);
        var markers = List.of(item(Items.DIAMOND), item(Items.IRON_INGOT));
        assertTrue(EcoBulkCells.rewrite(config, markers));
        assertEquals(AEItemKey.of(item(Items.DIAMOND)), config.getKey(0));
        assertEquals(AEItemKey.of(item(Items.IRON_INGOT)), config.getKey(1));
        assertNull(config.getKey(2));
    }

    @Test
    void rewriteIsNoOpWhenListAlreadyMatches() {
        var config = config(2);
        var markers = List.of(item(Items.DIAMOND), item(Items.IRON_INGOT));
        assertTrue(EcoBulkCells.rewrite(config, markers));
        assertFalse(EcoBulkCells.rewrite(config, markers));
    }

    @Test
    void rewriteClearsTrailingSlots() {
        var config = config(3);
        assertTrue(EcoBulkCells.rewrite(config,
                List.of(item(Items.DIAMOND), item(Items.IRON_INGOT), item(Items.GOLD_INGOT))));
        assertTrue(EcoBulkCells.rewrite(config, List.of(item(Items.DIAMOND))));
        assertEquals(AEItemKey.of(item(Items.DIAMOND)), config.getKey(0));
        assertNull(config.getKey(1));
        assertNull(config.getKey(2));
    }

    @Test
    void dedupeByChainKeepsFirstMarkerPerChain() {
        var chain = Set.<AEKey>of(AEItemKey.of(item(Items.DIAMOND)), AEItemKey.of(item(Items.IRON_INGOT)));
        var other = Set.<AEKey>of(AEItemKey.of(item(Items.GOLD_INGOT)));
        var markers = List.of(
                new CellBulkCutoffCompat.Marker(0, item(Items.DIAMOND), true, chain),
                new CellBulkCutoffCompat.Marker(1, item(Items.IRON_INGOT), true, chain),
                new CellBulkCutoffCompat.Marker(2, item(Items.GOLD_INGOT), false, other));
        var deduped = CellBulkCutoffCompat.dedupeByChain(markers);
        assertEquals(List.of(0, 2), deduped.stream().map(CellBulkCutoffCompat.Marker::slot).toList());
    }

    /** 写了一半被拒：前面几格必须整份回滚，不能报「改过了」却只改一半。 */
    @Test
    void rewriteRollsBackWrittenSlotsWhenALaterSlotIsRefused() {
        var config = refusingSlot(3, 1);
        config.setStack(0, stack(item(Items.DIAMOND)));

        assertFalse(EcoBulkCells.rewrite(config,
                List.of(item(Items.IRON_INGOT), item(Items.GOLD_INGOT), item(Items.EMERALD))));
        assertEquals(AEItemKey.of(item(Items.DIAMOND)), config.getKey(0), "已写的第一格要回滚成原值");
        assertNull(config.getKey(1));
        assertNull(config.getKey(2), "被拒之后不再往后写");
    }

    @Test
    void rewriteWritesNothingWhenTheFirstSlotIsRefused() {
        var config = refusingSlot(2, 0);

        assertFalse(EcoBulkCells.rewrite(config, List.of(item(Items.IRON_INGOT), item(Items.GOLD_INGOT))));
        assertNull(config.getKey(0));
        assertNull(config.getKey(1));
    }

    /** 标记比的是键而不是物品：同名不同组件是两枚不同的标记。 */
    @Test
    void sameMarkerDistinguishesItemComponents() {
        var named = item(Items.DIAMOND);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("标记"));

        assertFalse(EcoBulkCells.sameMarker(AEItemKey.of(item(Items.DIAMOND)), stack(named)));
        assertTrue(EcoBulkCells.sameMarker(AEItemKey.of(named), stack(named)));
    }

    /** 链的身份是成员集合：起点随截断点变，成员顺序不同仍是同一条链。 */
    @Test
    void dedupeByChainTreatsChainMembershipAsIdentity() {
        var diamond = AEItemKey.of(item(Items.DIAMOND));
        var iron = AEItemKey.of(item(Items.IRON_INGOT));
        var markers = List.of(
                new CellBulkCutoffCompat.Marker(0, item(Items.DIAMOND), true, Set.of(diamond, iron)),
                new CellBulkCutoffCompat.Marker(1, item(Items.IRON_INGOT), true, Set.of(iron, diamond)));

        assertEquals(List.of(0), CellBulkCutoffCompat.dedupeByChain(markers).stream()
                .map(CellBulkCutoffCompat.Marker::slot).toList());
    }

    @Test
    void dedupeByChainKeepsDistinctChainsInOrder() {
        var markers = List.of(
                new CellBulkCutoffCompat.Marker(2, item(Items.GOLD_INGOT), false,
                        Set.of(AEItemKey.of(item(Items.GOLD_INGOT)))),
                new CellBulkCutoffCompat.Marker(0, item(Items.DIAMOND), false,
                        Set.of(AEItemKey.of(item(Items.DIAMOND)))),
                new CellBulkCutoffCompat.Marker(1, item(Items.IRON_INGOT), false,
                        Set.of(AEItemKey.of(item(Items.IRON_INGOT)))));

        assertEquals(List.of(2, 0, 1), CellBulkCutoffCompat.dedupeByChain(markers).stream()
                .map(CellBulkCutoffCompat.Marker::slot).toList());
    }

    @Test
    void dedupeByChainOfEmptyListIsEmpty() {
        assertTrue(CellBulkCutoffCompat.dedupeByChain(List.of()).isEmpty());
    }

    /** 名单里的空栈是「这一格清掉」，不是「跳过这一格」。 */
    @Test
    void rewriteClearsSlotsGivenEmptyStacks() {
        var config = config(2);
        config.setStack(0, stack(item(Items.DIAMOND)));
        config.setStack(1, stack(item(Items.IRON_INGOT)));

        assertTrue(EcoBulkCells.rewrite(config, List.of(ItemStack.EMPTY, item(Items.IRON_INGOT))));
        assertNull(config.getKey(0));
        assertEquals(AEItemKey.of(item(Items.IRON_INGOT)), config.getKey(1));
    }

    @Test
    void rewriteWithEmptyListClearsEverySlot() {
        var config = config(2);
        config.setStack(0, stack(item(Items.DIAMOND)));

        assertTrue(EcoBulkCells.rewrite(config, List.of()));
        assertNull(config.getKey(0));
        assertFalse(EcoBulkCells.rewrite(config, List.of()), "已经空了就不再报改动");
    }
}
