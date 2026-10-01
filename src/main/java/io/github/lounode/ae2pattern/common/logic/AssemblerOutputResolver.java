package io.github.lounode.ae2pattern.common.logic;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;

/**
 * 把样板与输入摆成合成表、实际跑一次合成，得到产物与剩余物。
 *
 * <p>计数与顺序无关，所以先把 3&times;3 摆出来再压缩边距（去掉空行列），交给 {@code assemble}。算不出来时
 * 返回 {@code null}，调用方据此放弃这次推送而不是记一条空产物。</p>
 *
 * <p>结果按样板缓存：同一张样板反复执行时省掉重算。缓存按插入顺序清理（{@code ReferenceArrayList} 记顺序），
 * 上限很小——机器同时打交道的样板就那么几张，而每一条缓存都握着一份产物表。</p>
 */
public final class AssemblerOutputResolver {

    /** 产物缓存上限：同一张样板反复执行时省掉重算。 */
    private static final int CACHE_LIMIT = 10;

    private final Reference2ObjectArrayMap<IPatternDetails, List<GenericStack>> cache =
            new Reference2ObjectArrayMap<>(CACHE_LIMIT);
    private final ReferenceArrayList<IPatternDetails> order = new ReferenceArrayList<>(CACHE_LIMIT + 4);

    /**
     * @param level 归属世界；为 {@code null} 时无法执行合成，返回 {@code null}
     * @return 产物与剩余物（顺序：先产物后剩余物），或 {@code null} 表示这张样板这次算不出来
     */
    @Nullable
    public List<GenericStack> resolve(IMolecularAssemblerSupportedPattern pattern, KeyCounter[] inputHolder,
            @Nullable Level level) {
        List<GenericStack> cached = cache.get(pattern);
        if (cached != null) {
            return cached;
        }
        if (level == null) {
            return null;
        }

        final ItemStack[] grid3x3 = new ItemStack[9];
        for (int i = 0; i < 9; i++) {
            grid3x3[i] = ItemStack.EMPTY;
        }
        try {
            KeyCounter[] inputHolderCopy = new KeyCounter[inputHolder.length];
            for (int i = 0; i < inputHolder.length; i++) {
                KeyCounter counter = inputHolder[i];
                KeyCounter copy = new KeyCounter();
                if (counter != null) {
                    copy.addAll(counter);
                }
                inputHolderCopy[i] = copy;
            }
            pattern.fillCraftingGrid(inputHolderCopy, (slot, stack) -> {
                if (slot >= 0 && slot < 9) {
                    grid3x3[slot] = (stack == null) ? ItemStack.EMPTY : stack;
                }
            });
        } catch (RuntimeException e) {
            // 摆不出来说明这次输入不稳定，直接放弃。
            return null;
        }

        // 压缩边距：只留非空行列。
        int minX = 3, minY = 3, maxX = -1, maxY = -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = grid3x3[slot];
            if (stack != null && !stack.isEmpty()) {
                int x = slot % 3;
                int y = slot / 3;
                if (x < minX) {
                    minX = x;
                }
                if (y < minY) {
                    minY = y;
                }
                if (x > maxX) {
                    maxX = x;
                }
                if (y > maxY) {
                    maxY = y;
                }
            }
        }

        if (maxX < 0) {
            return null;
        }

        final int width = (maxX - minX + 1);
        final int height = (maxY - minY + 1);

        final List<ItemStack> compressedItems = new ArrayList<>(width * height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int srcSlot = (minX + x) + (minY + y) * 3;
                ItemStack stack = grid3x3[srcSlot];
                compressedItems.add(stack == null ? ItemStack.EMPTY : stack);
            }
        }

        final CraftingInput input = CraftingInput.of(width, height, compressedItems);

        ItemStack output = pattern.assemble(input, level);
        if (output == null || output.isEmpty()) {
            return null;
        }
        NonNullList<ItemStack> remainders = pattern.getRemainingItems(input);

        List<GenericStack> finalOutput = new ArrayList<>();
        GenericStack outputStack = GenericStack.fromItemStack(output);
        if (outputStack != null) {
            finalOutput.add(outputStack);
        }
        for (ItemStack stack : remainders) {
            GenericStack remainingStack = GenericStack.fromItemStack(stack);
            if (remainingStack != null) {
                finalOutput.add(remainingStack);
            }
        }

        cache.put(pattern, finalOutput);
        order.add(pattern);
        while (cache.size() > CACHE_LIMIT && !order.isEmpty()) {
            IPatternDetails oldest = order.removeFirst();
            cache.remove(oldest);
        }
        return finalOutput;
    }

    /** 丢掉全部缓存。目前唯一的调用点是设备被清空时；缓存没有别的失效触发点。 */
    public void clear() {
        cache.clear();
        order.clear();
    }
}
