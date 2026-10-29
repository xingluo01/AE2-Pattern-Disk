package io.github.lounode.ae2pattern.client.gui;

import java.util.HashSet;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import mezz.jei.api.recipe.category.IRecipeCategory;

import io.github.lounode.ae2pattern.client.integration.JeiMarkNames;

/**
 * JEI 侧的催化剂查询。JEI 是编译期可选依赖，所以除本类外任何地方都不得出现它的类型。
 *
 * <p>JEI 的催化剂挂在配方类型上而不是类别对象上：先由类别 id 反查类型，再向配方管理器要催化剂。</p>
 *
 * <p>不少模组（MEK 的粉碎机、ECO 的集成工作站）只给了类别图标、没往催化剂注册表里放东西，
 * 所以物品查不到时还有 {@link #categoryIcon} 这一档。</p>
 */
final class JeiCatalysts {

    /** 一个类别最多露出几种催化剂，再多也没地方摆。 */
    private static final int RESULT_LIMIT = 8;

    private JeiCatalysts() {
    }

    static List<ItemStack> findItems(ResourceLocation categoryId) {
        var manager = recipeManager();
        var type = manager == null ? null : manager.getRecipeType(categoryId).orElse(null);
        if (type == null) {
            return List.of();
        }
        // 标准库没有带比较器的 distinct，按物品去重只能自己带一个集合。
        var seen = new HashSet<Item>();
        return manager.createRecipeCatalystLookup(type)
                .getItemStack()
                .filter(stack -> !stack.isEmpty() && seen.add(stack.getItem()))
                .limit(RESULT_LIMIT)
                .toList();
    }

    /** 类别图标（JEI 界面里挂在标题旁的那张贴图）。查不到时返回 {@code null}。 */
    @Nullable
    static MarkCatalysts.Icon categoryIcon(ResourceLocation categoryId) {
        var manager = recipeManager();
        var type = manager == null ? null : manager.getRecipeType(categoryId).orElse(null);
        if (type == null) {
            return null;
        }
        IRecipeCategory<?> category;
        try {
            // getRecipeCategory 对未注册的类型会抛，先经 getRecipeType 判空仍不保证这步安全。
            category = manager.getRecipeCategory(type);
        } catch (RuntimeException e) {
            return null;
        }
        if (category == null) {
            return null;
        }
        var icon = category.getIcon();
        if (icon == null) {
            return null;
        }
        return (graphics, x, y) -> icon.draw(graphics, x, y);
    }

    /** 按类别标题反查 JEI 的配方类别 id；命中多个时优先完全相等的那个。 */
    @Nullable
    static ResourceLocation categoryByTitle(String title) {
        var manager = recipeManager();
        if (manager == null) {
            return null;
        }
        ResourceLocation fallback = null;
        try (var categories = manager.createRecipeCategoryLookup().includeHidden().get()) {
            var iterator = categories.iterator();
            while (iterator.hasNext()) {
                var category = iterator.next();
                if (!MarkCategoryIndex.titleMatches(category.getTitle(), title)) {
                    continue;
                }
                if (MarkCategoryIndex.titleEquals(category.getTitle(), title)) {
                    return category.getRecipeType().getUid();
                }
                if (fallback == null) {
                    fallback = category.getRecipeType().getUid();
                }
            }
        } catch (RuntimeException failed) {
            // JEI 的类别枚举偶发抛错时，已经认到的那个仍然可用。
            return fallback;
        }
        return fallback;
    }

    @Nullable
    private static mezz.jei.api.recipe.IRecipeManager recipeManager() {
        var runtime = JeiMarkNames.runtime();
        return runtime == null ? null : runtime.getRecipeManager();
    }
}
