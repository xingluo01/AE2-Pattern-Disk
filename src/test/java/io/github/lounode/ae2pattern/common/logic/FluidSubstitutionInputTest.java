package io.github.lounode.ae2pattern.common.logic;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.LoadingModList;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 流体替换样板的每份用量。
 *
 * <p>AE2 的输入契约是「{@code getMultiplier()} 份模板，每份 {@code getPossibleInputs()[i].amount()} 个」：
 * 合成样板里物品模板的量恒为 1（样板槽只存单件），而流体替换把桶槽换成「一整桶」的流体模板——这份的量取自
 * 容器实际容量（原版桶是 1000 mB，自定义桶是多少就是多少），而不是这个物品本身的件数。所以一次执行该槽位
 * 消耗的是两者的乘积，而不是只有 {@code getMultiplier()}。</p>
 *
 * <p>这条用例盯的是实际扣料：以「1 个水桶槽」编码的样板（multiplier=1、模板 1000 mB）跑一次，元件缓冲必须被
 * 扣掉 1000 mB。只按 multiplier 扣的话这里只会扣 1 mB——同一份订单下就是「1000 mB 的原料只消耗了个位数」。</p>
 */
class FluidSubstitutionInputTest {

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

    /** 单槽样板：{@code multiplier} 份模板，每份 {@code templateAmount} 个单位。 */
    private static IPatternDetails singleSlotPattern(AEKey key, long templateAmount, long multiplier, AEKey product) {
        var input = mock(IPatternDetails.IInput.class);
        when(input.getPossibleInputs())
                .thenReturn(new GenericStack[] { new GenericStack(key, templateAmount) });
        when(input.getMultiplier()).thenReturn(multiplier);
        when(input.getRemainingKey(any())).thenReturn(null);

        var pattern = mock(IPatternDetails.class);
        when(pattern.getInputs()).thenReturn(new IPatternDetails.IInput[] { input });
        when(pattern.getOutputs()).thenReturn(List.of(new GenericStack(product, 1)));
        return pattern;
    }

    private static BatchSynthesisEngine engineOver(CellBuffer cells, SmoothReturnQueue returns) {
        return new BatchSynthesisEngine(cells, new PatternPlanCache(), returns,
                () -> null, () -> null, () -> true, state -> {
                });
    }

    private static CellBuffer generousCells() {
        var cells = mock(CellBuffer.class);
        // 元件总是「刚好够」：模拟与真实提取都返回请求量，于是扣多少完全由被测代码决定。
        when(cells.extract(any(), anyLong(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        return cells;
    }

    @Test
    void oneCraftOfABucketSubstitutedSlotConsumesTheWholeBucket() {
        var water = mock(AEKey.class);
        var product = mock(AEKey.class);
        var plan = PatternPlan.of(singleSlotPattern(water, 1000, 1, product));

        var cells = generousCells();
        var returns = new SmoothReturnQueue(4, 100);
        var engine = engineOver(cells, returns);

        assertTrue(engine.craftOnce(plan, new KeyCounter()), "材料够就应当执行成功");

        verify(cells).extract(water, 1000L, Actionable.MODULATE);
        assertEquals(1L, returns.snapshot().get(product), "产物仍按声明记一份");
    }

    /**
     * 蜂蜜瓶与水瓶编进样板时仍是<b>物品</b>模板，不是流体模板。
     *
     * <p>AE2 的流体替换只对 {@code BucketItem} 与 {@code MilkBucketItem} 生效（{@code AECraftingPattern} 的
     * {@code getItemOrFluidInput} 里的 {@code isBucket} 判断），而且要求配方余料恰为原版空桶；蜂蜜瓶、水瓶都
     * 不在这两类里，所以它们装的液体不会进消耗——一份只扣 1 个瓶子，不会按 {@code 250 mB} 之类的容量换算。</p>
     */
    @Test
    void honeyAndWaterBottlesStayItemSlots() {
        var product = AEItemKey.of(new ItemStack(Items.STONE));
        for (var bottle : List.of(
                AEItemKey.of(new ItemStack(Items.HONEY_BOTTLE)),
                AEItemKey.of(new ItemStack(Items.POTION)))) {
            var plan = PatternPlan.of(singleSlotPattern(bottle, 1, 1, product));
            var cells = generousCells();
            var engine = engineOver(cells, new SmoothReturnQueue(4, 100));

            assertTrue(engine.craftOnce(plan, new KeyCounter()), "瓶子够就应当执行成功");

            verify(cells).extract(bottle, 1L, Actionable.MODULATE);
        }
    }

    /**
     * 若上游把蜂蜜瓶声明成 250 mB 的流体模板（模组桶类容器就可能这样），扣的就是容器自己的容量。
     *
     * <p>两个槽取同一份 250 mB 蜜——{@code multiplier = 2}——所以总量是 500 mB。这里既不硬编码 1000，
     * 也不把「瓶子」当成 1 个单位。</p>
     */
    @Test
    void aBottleDeclaredAsFluidTemplateUsesItsOwnCapacity() {
        var honey = mock(AEKey.class);
        var product = mock(AEKey.class);
        var plan = PatternPlan.of(singleSlotPattern(honey, 250, 2, product));

        var cells = generousCells();
        var returns = new SmoothReturnQueue(4, 100);
        var engine = engineOver(cells, returns);

        assertTrue(engine.craftOnce(plan, new KeyCounter()), "材料够就应当执行成功");

        verify(cells).extract(honey, 500L, Actionable.MODULATE);
        assertEquals(1L, returns.snapshot().get(product), "产物仍按声明记一份");
    }

    /**
     * 摊销块走的是另一条扣料代码：一次兑付 {@code jobs} 份。
     *
     * <p>这里用 5000 mB 的容器而不是 1000：模板的量来自 AE2 读出的容器实际容量，而不是桶这个物品本身，
     * 所以任何容量的自定义桶都得按它自己的数字扣。</p>
     */
    @Test
    void amortisedBlockScalesWithWhateverTheContainerHolds() {
        var water = mock(AEKey.class);
        var stone = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE);
        var product = appeng.api.stacks.AEItemKey.of(stone);

        var supported = mock(appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern.class);
        var input = mock(IPatternDetails.IInput.class);
        when(input.getPossibleInputs()).thenReturn(new GenericStack[] { new GenericStack(water, 5000) });
        when(input.getMultiplier()).thenReturn(1L);
        when(input.getRemainingKey(any())).thenReturn(null);
        when(supported.getInputs()).thenReturn(new IPatternDetails.IInput[] { input });
        when(supported.getOutputs()).thenReturn(List.of(new GenericStack(product, 1)));
        when(supported.assemble(any(), any())).thenReturn(stone);
        when(supported.getRemainingItems(any())).thenReturn(net.minecraft.core.NonNullList.create());

        var plan = PatternPlan.of(supported);
        var cells = mock(CellBuffer.class);
        when(cells.extract(any(), anyLong(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        var returns = new SmoothReturnQueue(4, 100);
        var engine = new BatchSynthesisEngine(cells, new PatternPlanCache(), returns,
                () -> mock(net.minecraft.world.level.Level.class), () -> null, () -> true, state -> {
                });

        assertTrue(engine.amortise(supported, plan, 3L), "材料够、配方对得上，就应当摊销成功");

        verify(cells).extract(water, 15000L, Actionable.MODULATE);
        assertEquals(3L, returns.snapshot().get(product), "产物按批量记三份");

        // 建格用的是「一份」的量：批量倍率已经由调用次数承担，格子里的水量不能跟着乘 jobs。
        var gridCaptor = org.mockito.ArgumentCaptor.forClass(KeyCounter[].class);
        verify(supported).fillCraftingGrid(gridCaptor.capture(), any());
        assertEquals(5000L, gridCaptor.getValue()[0].get(water),
                "建格时每个槽放一份模板的量，而不是整批的总量");
    }
}
