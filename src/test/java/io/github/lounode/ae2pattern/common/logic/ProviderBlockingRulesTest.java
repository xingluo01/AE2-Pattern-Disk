package io.github.lounode.ae2pattern.common.logic;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.LoadingModList;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 智能阻挡的判据：目标内容物是否完全由本样板的原材料构成。
 *
 * <p>「完全匹配」的边界按以下三条固定下来——空目标放行（否则第一份材料永远投不进去）、全为本配方原料放行
 * （同配方可以继续堆，倍增要靠它）、混入任何外来项就阻挡。</p>
 */
class ProviderBlockingRulesTest {

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

    private static AEItemKey key(net.minecraft.world.item.Item item) {
        return AEItemKey.of(new ItemStack(item));
    }

    private static KeyCounter counter(AEItemKey... keys) {
        var counter = new KeyCounter();
        for (var key : keys) {
            counter.add(key, 1);
        }
        return counter;
    }

    @Test
    void emptyTargetIsNotBlocked() {
        var own = Set.<AEKey>of(key(Items.IRON_INGOT));
        assertFalse(ProviderBlockingRules.hasForeignInput(new KeyCounter(), own),
                "空目标必须放行，否则第一份材料投不进去");
    }

    @Test
    void sameRecipeInputsAreNotBlocked() {
        var own = Set.<AEKey>of(key(Items.IRON_INGOT), key(Items.GOLD_INGOT));
        var target = counter(key(Items.IRON_INGOT), key(Items.GOLD_INGOT));
        assertFalse(ProviderBlockingRules.hasForeignInput(target, own),
                "同配方的原料可以继续堆");
    }

    @Test
    void foreignInputBlocks() {
        var own = Set.<AEKey>of(key(Items.IRON_INGOT));
        var target = counter(key(Items.IRON_INGOT), key(Items.DIAMOND));
        assertTrue(ProviderBlockingRules.hasForeignInput(target, own),
                "混进不属于本样板的原料就必须换目标");
    }
}
