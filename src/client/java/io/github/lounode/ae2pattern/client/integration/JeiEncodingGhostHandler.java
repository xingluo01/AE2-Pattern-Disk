package io.github.lounode.ae2pattern.client.integration;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.menu.slot.FakeSlot;

import io.github.lounode.ae2pattern.client.gui.PatternDiskEncodingTermScreen;

/**
 * JEI 的幽灵物品处理器：把 JEI 里的物品拖进样板编辑区。
 *
 * <p>每个活动的 {@code FakeSlot} 各是一个目标，把 JEI 里的物品拖上去就设成那一格的内容——与 AE2 自带
 * EMI 集成给 AE2 屏幕的通用拖放同一套规则（那边也是遍历 {@code menu.slots}、只认 {@code isActive()} 的
 * {@code FakeSlot}），所以两条路的手感一致。</p>
 *
 * <p>只设值、不自动编码写盘：是否写盘由玩家按「编写样板」决定。</p>
 *
 * <p>曾接过 JEI 的 {@code quickMove}（「Shift+点击物品 → 用它的一条「产物是它」的配方填满编辑区」），两轮都
 * 没能稳定触发（JEI 只在鼠标下有可拖拽物品时才走这条路），已整体移除：连同那条配方查找链与 {@code runtime}
 * 注入一起删掉了，只保留拖拽。要复活它得先有实机证据说明 JEI 确实会调进来。</p>
 */
public class JeiEncodingGhostHandler implements IGhostIngredientHandler<PatternDiskEncodingTermScreen> {

    @Override
    public <I> List<Target<I>> getTargetsTyped(PatternDiskEncodingTermScreen screen,
            ITypedIngredient<I> ingredient, boolean doStart) {
        // 只认得出来的物品/流体：转换不了的东西（能量之类）不给目标，免得拖上去没反应还高亮一片。
        if (toGenericStack(ingredient.getIngredient()) == null) {
            return List.of();
        }

        var targets = new ArrayList<Target<I>>();
        for (var slot : screen.getMenu().slots) {
            // 只看活动槽：当前编码模式不在用的那些格既画不出来，也不该接东西。
            if (slot.isActive() && slot instanceof FakeSlot fakeSlot) {
                targets.add(new SlotTarget<>(screen, fakeSlot));
            }
        }
        return targets;
    }

    @Override
    public void onComplete() {
        // 每次放入都各自发包收尾，这里没有需要清理的状态。
    }

    /**
     * 一个格子的拖放目标。落在格子矩形里就调 {@link FakeSlot#setFilterTo}——它自己会把动作包发给服务端，
     * 所以这里不用再拼包（AE2 给 EMI 用的那条拖放路径也是这么写的）。
     */
    private record SlotTarget<I>(PatternDiskEncodingTermScreen screen, FakeSlot slot) implements Target<I> {

        @Override
        public Rect2i getArea() {
            return new Rect2i(screen.getGuiLeft() + slot.x, screen.getGuiTop() + slot.y, 16, 16);
        }

        @Override
        public void accept(I ingredient) {
            var stack = toGenericStack(ingredient);
            if (stack == null) {
                return;
            }
            var filter = wrapFilterAsItem(stack);
            if (!slot.canSetFilterTo(filter)) {
                return; // 这一格不收这种东西：与 JEI 里拖到别的格子上一样，什么都不做
            }
            slot.setFilterTo(filter);
        }
    }

    /**
     * 把 JEI 的物品/流体转成 AE2 的栈；不认识的类型返回 {@code null}。只认这两类是因为本模组的编码槽也就只收
     * 这两类，别的转不出来也没处放。
     */
    private static @Nullable GenericStack toGenericStack(@Nullable Object ingredient) {
        if (ingredient instanceof ItemStack itemStack && !itemStack.isEmpty()) {
            return GenericStack.fromItemStack(itemStack);
        }
        if (ingredient instanceof FluidStack fluidStack && !fluidStack.isEmpty()) {
            return GenericStack.fromFluidStack(fluidStack);
        }
        return null;
    }

    /** 编码槽内部用一个被包成 ItemStack 的 GenericStack 表示非物品，这里与 {@code FakeSlot} 的取值口径对齐。 */
    private static ItemStack wrapFilterAsItem(GenericStack stack) {
        var amount = Math.max(1, stack.amount());
        if (stack.what() instanceof AEItemKey itemKey) {
            return itemKey.toStack(amount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) amount);
        }
        return GenericStack.wrapInItemStack(stack.what(), amount);
    }
}
