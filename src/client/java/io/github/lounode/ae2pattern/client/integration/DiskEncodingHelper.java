package io.github.lounode.ae2pattern.client.integration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import com.google.common.math.LongMath;

import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.network.PacketDistributor;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.core.network.ServerboundPacket;
import appeng.core.network.serverbound.InventoryActionPacket;
import appeng.helpers.InventoryAction;
import appeng.menu.me.common.GridInventoryEntry;
import appeng.menu.me.common.MEStorageMenu;
import appeng.menu.slot.FakeSlot;
import appeng.parts.encoding.EncodingMode;
import appeng.util.CraftingRecipeUtil;

import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;
import io.github.lounode.ae2pattern.integration.rechiseledae.ChiselingRecipes;

/**
 * 配方填充工具：把 EMI 配方导入样板磁盘编码终端的编码网格。
 * 移植自 AE2 {@code EncodingHelper}，将菜单类型替换为 {@link PatternDiskEncodingTermMenu}。
 */
public final class DiskEncodingHelper {
    private DiskEncodingHelper() {
    }

    /** 优先级：可合成 > 未损坏 > 玩家拥有最多。 */
    static final Comparator<GridInventoryEntry> ENTRY_COMPARATOR = Comparator
            .comparing(GridInventoryEntry::isCraftable)
            .thenComparing(DiskEncodingHelper::isUndamaged)
            .thenComparing(GridInventoryEntry::getStoredAmount);

    private static Boolean isUndamaged(GridInventoryEntry entry) {
        return !(entry.getWhat() instanceof AEItemKey itemKey) || !itemKey.isDamaged();
    }

    public static void encodeProcessingRecipe(PatternDiskEncodingTermMenu menu,
            List<List<GenericStack>> genericIngredients,
            List<GenericStack> genericResults) {
        menu.setMode(EncodingMode.PROCESSING);

        // 仅在客户端运行，getClientRepo() 保证可用。
        var ingredientPriorities = getIngredientPriorities(menu, ENTRY_COMPARATOR);

        encodeBestMatchingStacksIntoSlots(
                genericIngredients,
                ingredientPriorities,
                menu.getProcessingInputSlots());
        encodeBestMatchingStacksIntoSlots(
                // 输出每槽只有一个候选
                genericResults.stream().map(List::of).toList(),
                ingredientPriorities,
                menu.getProcessingOutputSlots());
    }

    /**
     * 从配方页导入前先把档位调到这份配方对应的那一档（映射表见 {@code docs/ENCODING_MODES.md}）。
     *
     * <p>先关掉三个额外档：导入只写编辑区的普通输入输出，而额外档是并列的开关，留着会让屏幕停在
     * 另一个档的面板上——看起来就像「转移到了高级/过载档」，实际什么也没发生。</p>
     *
     * <p>雕凿走另一条道（候选来自 Rechiseled 自己的配方表，不在本类的输入输出路径里），见
     * {@link #selectChiselingTierForImport}。</p>
     */
    public static void selectTierForImport(PatternDiskEncodingTermMenu menu, @Nullable Recipe<?> recipe) {
        closeExtraTiers(menu);
        menu.setMode(modeForRecipe(recipe));
    }

    /**
     * 对照表：这份配方该落到哪一档（见 {@code docs/ENCODING_MODES.md} 的「配方页导入的档位对照」）。
     *
     * <table>
     * <caption>对照</caption>
     * <tr><td>合成</td><td>{@link EncodingMode#CRAFTING}</td></tr>
     * <tr><td>切石</td><td>{@link EncodingMode#STONECUTTING}</td></tr>
     * <tr><td>锻造</td><td>{@link EncodingMode#SMITHING_TABLE}</td></tr>
     * <tr><td>其余加工</td><td>{@link EncodingMode#PROCESSING}（无固定类别，一律处理）</td></tr>
     * <tr><td>雕凿</td><td>不走本表，走 {@link #selectChiselingTierForImport}</td></tr>
     * </table>
     *
     * <p>没有配方本体的（JEI 交上来的不是 {@code RecipeHolder}）算「其余加工」，与它随后的导入路径一致
     * ——那条路走的是处理导入。</p>
     */
    private static EncodingMode modeForRecipe(@Nullable Recipe<?> recipe) {
        if (recipe == null) {
            return EncodingMode.PROCESSING;
        }
        if (recipe.getType() == RecipeType.STONECUTTING) {
            return EncodingMode.STONECUTTING;
        }
        if (recipe.getType() == RecipeType.SMITHING) {
            return EncodingMode.SMITHING_TABLE;
        }
        return isSupportedCraftingRecipe(recipe) ? EncodingMode.CRAFTING : EncodingMode.PROCESSING;
    }

    /**
     * 雕凿导入：把「输入方块 + 产物」反推成面板上的输入槽与候选序号，并切到雕凿档。
     *
     * <p>反过来推不出来的（输入或产物为空、或那个产物不在输入方块的候选里）就不切成雕凿档，
     * 同时返回 false——调用方按普通路径继续导。这样雕凿配方永远不会被当成合成/处理导进去，
     * 也不会把玩家丢进一个空面板。</p>
     *
     * @return 是否已按雕凿导入处理
     */
    public static boolean selectChiselingTierForImport(PatternDiskEncodingTermMenu menu,
            @Nullable GenericStack chiselingInput, @Nullable GenericStack chiselingOutput) {
        var inputKey = chiselingInput == null ? null : itemOf(chiselingInput);
        var outputKey = chiselingOutput == null ? null : itemOf(chiselingOutput);
        if (inputKey == null || outputKey == null) {
            return false;
        }
        var index = ChiselingRecipes.clientIndexOf(inputKey, outputKey);
        if (index < 0) {
            return false;
        }
        closeExtraTiers(menu);
        // 顺序要紧：先调档，再填输入、最后选中。面板每帧看输入槽是否变过，一变就把选中项作废并重算候选
        // ——选中写在填输入之前会被那一下清掉。
        menu.setChiselingMode(true);
        menu.setChiselingInput(new ItemStack(inputKey));
        menu.setChiseling(index);
        return true;
    }

    /** 关掉三个并列的额外档；导入只认常规档与雕凿档。 */
    private static void closeExtraTiers(PatternDiskEncodingTermMenu menu) {
        if (menu.advancedMode) {
            menu.setAdvancedMode(false);
        }
        if (menu.chiselingMode) {
            menu.setChiselingMode(false);
        }
        if (menu.overloadedMode) {
            menu.setOverloadedMode(false);
        }
    }

    /** 从配方页给的一格输入里取物品；取不到返回 null（非物品的输入不参与雕凿反推）。 */
    @Nullable
    private static Item itemOf(GenericStack stack) {
        return stack.what() instanceof AEItemKey itemKey ? itemKey.getItem() : null;
    }

    private static void encodeBestMatchingStacksIntoSlots(List<List<GenericStack>> possibleInputsBySlot,
            Map<AEKey, Integer> ingredientPriorities,
            FakeSlot[] slots) {
        var encodedInputs = new ArrayList<GenericStack>();
        for (var genericIngredient : possibleInputsBySlot) {
            if (!genericIngredient.isEmpty()) {
                addOrMerge(encodedInputs, findBestIngredient(ingredientPriorities, genericIngredient));
            }
        }

        for (int i = 0; i < slots.length; i++) {
            var slot = slots[i];
            var stack = (i < encodedInputs.size()) ? GenericStack.wrapInItemStack(encodedInputs.get(i))
                    : ItemStack.EMPTY;
            ServerboundPacket message = new InventoryActionPacket(
                    InventoryAction.SET_FILTER, slot.index, stack);
            PacketDistributor.sendToServer(message);
        }
    }

    public static boolean isSupportedCraftingRecipe(@Nullable Recipe<?> recipe) {
        if (recipe == null) {
            return false;
        }
        var recipeType = recipe.getType();

        return recipeType == RecipeType.CRAFTING
                || recipeType == RecipeType.STONECUTTING
                || recipeType == RecipeType.SMITHING;
    }

    public static void encodeCraftingRecipe(PatternDiskEncodingTermMenu menu,
            @Nullable RecipeHolder<?> recipe,
            List<List<GenericStack>> genericIngredients,
            Predicate<ItemStack> visiblePredicate) {
        selectTierForImport(menu, recipe == null ? null : recipe.value());
        if (recipe != null && recipe.value().getType().equals(RecipeType.STONECUTTING)) {
            menu.setStonecuttingRecipeId(recipe.id());
        }

        // 仅在客户端运行，getClientRepo() 保证可用。
        var prioritizedNetworkInv = getIngredientPriorities(menu, ENTRY_COMPARATOR);

        var encodedInputs = NonNullList.withSize(menu.getCraftingGridSlots().length, ItemStack.EMPTY);

        if (recipe != null) {
            // 有合成配方时可模糊匹配，找到合适的材料。
            var ingredients3x3 = CraftingRecipeUtil.ensure3by3CraftingMatrix(recipe.value());

            for (int slot = 0; slot < ingredients3x3.size(); slot++) {
                var ingredient = ingredients3x3.get(slot);
                if (ingredient.isEmpty()) {
                    continue;
                }

                var bestNetworkIngredient = prioritizedNetworkInv.entrySet().stream()
                        .filter(ni -> ni.getKey() instanceof AEItemKey itemKey && itemKey.matches(ingredient))
                        .max(Comparator.comparingInt(Map.Entry::getValue))
                        .map(entry -> entry.getKey() instanceof AEItemKey itemKey ? itemKey.toStack() : null);

                var bestIngredient = bestNetworkIngredient.orElseGet(() -> {
                    for (var stack : ingredient.getItems()) {
                        if (visiblePredicate.test(stack)) {
                            return stack;
                        }
                    }
                    return ingredient.getItems()[0];
                });

                encodedInputs.set(slot, bestIngredient);
            }
        } else {
            for (int slot = 0; slot < genericIngredients.size(); slot++) {
                var genericIngredient = genericIngredients.get(slot);
                if (genericIngredient.isEmpty()) {
                    continue;
                }

                var bestIngredient = findBestIngredient(prioritizedNetworkInv, genericIngredient).what();

                if (bestIngredient instanceof AEItemKey itemKey) {
                    encodedInputs.set(slot, itemKey.toStack());
                } else {
                    encodedInputs.set(slot, GenericStack.wrapInItemStack(bestIngredient, 1));
                }
            }
        }

        for (int i = 0; i < encodedInputs.size(); i++) {
            ItemStack encodedInput = encodedInputs.get(i);
            ServerboundPacket message = new InventoryActionPacket(
                    InventoryAction.SET_FILTER, menu.getCraftingGridSlots()[i].index, encodedInput);
            PacketDistributor.sendToServer(message);
        }

        // 清空处理输出
        for (var outputSlot : menu.getProcessingOutputSlots()) {
            ServerboundPacket message = new InventoryActionPacket(
                    InventoryAction.SET_FILTER, outputSlot.index, ItemStack.EMPTY);
            PacketDistributor.sendToServer(message);
        }
    }

    private static GenericStack findBestIngredient(Map<AEKey, Integer> ingredientPriorities,
            List<GenericStack> possibleIngredients) {
        return possibleIngredients.stream()
                .map(gi -> Pair.of(gi, ingredientPriorities.getOrDefault(gi.what(), Integer.MIN_VALUE)))
                .max(Comparator.comparingInt(Pair::getRight))
                .map(Pair::getLeft)
                .orElseThrow();
    }

    /** 处理模式下同类型栈合并。 */
    private static void addOrMerge(List<GenericStack> stacks, GenericStack newStack) {
        for (int i = 0; i < stacks.size(); i++) {
            var existingStack = stacks.get(i);
            if (Objects.equals(existingStack.what(), newStack.what())) {
                long newAmount = LongMath.saturatedAdd(existingStack.amount(), newStack.amount());
                stacks.set(i, new GenericStack(newStack.what(), newAmount));

                long overflow = newStack.amount() - (newAmount - existingStack.amount());
                if (overflow > 0) {
                    stacks.add(new GenericStack(newStack.what(), overflow));
                }
                return;
            }
        }

        stacks.add(newStack);
    }

    /** 计算网络库存中所有 key 的优先级映射，并补充玩家背包作为兜底。值越大优先级越高。 */
    public static Map<AEKey, Integer> getIngredientPriorities(MEStorageMenu menu,
            Comparator<GridInventoryEntry> comparator) {
        // 调用前置：须在客户端打开终端、menu.setClientRepo 已设置后调用；否则视为无可用库存。
        if (menu.getClientRepo() == null) {
            return Map.of();
        }
        var orderedEntries = menu.getClientRepo().getAllEntries()
                .stream()
                .sorted(comparator)
                .map(GridInventoryEntry::getWhat)
                .toList();

        var result = new HashMap<AEKey, Integer>(orderedEntries.size());
        for (int i = 0; i < orderedEntries.size(); i++) {
            result.put(orderedEntries.get(i), i);
        }

        for (var item : menu.getPlayerInventory().items) {
            var key = AEItemKey.of(item);
            if (key != null) {
                result.putIfAbsent(key, -1);
            }
        }

        return result;
    }
}