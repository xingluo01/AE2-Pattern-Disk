package io.github.lounode.ae2pattern.client.gui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.stack.EmiStack;

/**
 * EMI 侧的催化剂查询。EMI 是编译期可选依赖，所以除本类外任何地方都不得出现它的类型。
 *
 * <p>物品按两档找：类别登记的工作台表（{@code addWorkstation} 那份），查不到再翻该类别下配方自带的催化剂。
 * 很多模组只登记配方、不登记工作台。翻配方要限量：一个大类别可能有上千条配方，而这里只要几个能代表该类别
 * 的物品。</p>
 *
 * <p>两档都没有物品时，还有类别贴图可用（{@link #categoryIcon}）——MEK 的粉碎机就是这一类。</p>
 */
final class EmiCatalysts {

    /** 从配方里翻催化剂时最多翻几条。 */
    private static final int RECIPE_SCAN_LIMIT = 24;

    /** 一个类别最多露出几种催化剂，再多也没地方摆。 */
    private static final int RESULT_LIMIT = 8;

    private EmiCatalysts() {
    }

    static List<ItemStack> findItems(ResourceLocation categoryId) {
        var manager = EmiApi.getRecipeManager();
        if (manager == null) {
            return List.of();
        }
        for (var category : manager.getCategories()) {
            if (!category.getId().equals(categoryId)) {
                continue;
            }
            var out = fromWorkstations(manager, category);
            return out.isEmpty() ? fromRecipes(manager, category) : out;
        }
        return List.of();
    }

    /** 类别自己的贴图（EMI 的类别图标通常就是那台机器）。找不到类别时返回 {@code null}。 */
    @Nullable
    static MarkCatalysts.Icon categoryIcon(ResourceLocation categoryId) {
        var manager = EmiApi.getRecipeManager();
        if (manager == null) {
            return null;
        }
        for (var category : manager.getCategories()) {
            if (!category.getId().equals(categoryId)) {
                continue;
            }
            var match = category;
            return (graphics, x, y) -> match.render(graphics, x, y, 0f);
        }
        return null;
    }

    /** 按类别名反查 EMI 的类别 id；命中多个时优先完全相等的那个。 */
    @Nullable
    static ResourceLocation categoryByTitle(String title) {
        var manager = EmiApi.getRecipeManager();
        if (manager == null) {
            return null;
        }
        ResourceLocation fallback = null;
        for (var category : manager.getCategories()) {
            if (!MarkCategoryIndex.titleMatches(category.getName(), title)) {
                continue;
            }
            if (MarkCategoryIndex.titleEquals(category.getName(), title)) {
                return category.getId();
            }
            if (fallback == null) {
                fallback = category.getId();
            }
        }
        return fallback;
    }

    private static List<ItemStack> fromWorkstations(EmiRecipeManager manager, EmiRecipeCategory category) {
        var out = new ArrayList<ItemStack>();
        var seen = new HashSet<Item>();
        for (var workstation : manager.getWorkstations(category)) {
            collect(workstation.getEmiStacks(), out, seen);
        }
        return out;
    }

    private static List<ItemStack> fromRecipes(EmiRecipeManager manager, EmiRecipeCategory category) {
        var out = new ArrayList<ItemStack>();
        var seen = new HashSet<Item>();
        var recipes = manager.getRecipes(category);
        for (int i = 0; i < recipes.size() && i < RECIPE_SCAN_LIMIT && out.size() < RESULT_LIMIT; i++) {
            for (var catalyst : recipes.get(i).getCatalysts()) {
                collect(catalyst.getEmiStacks(), out, seen);
            }
        }
        return out;
    }

    /** 按物品去重收集：同一个工作方块在多张配方里重复出现，只留一张图标。 */
    private static void collect(List<EmiStack> stacks, List<ItemStack> out, Set<Item> seen) {
        for (var stack : stacks) {
            var itemStack = stack.getItemStack();
            if (itemStack.isEmpty() || !seen.add(itemStack.getItem())) {
                continue;
            }
            out.add(itemStack.copyWithCount(1));
            if (out.size() >= RESULT_LIMIT) {
                return;
            }
        }
    }
}
