package io.github.lounode.ae2pattern.client.integration;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;

/**
 * 磁盘标记里需要 JEI 的那部分：把配方类别 id 换成它的可读名。对应 EMI 侧的 {@link EmiMarkNames}。
 *
 * <p>JEI 是编译期可选依赖，所以除本类外任何地方都不得出现它的类型；调用方先确认模组在场再进来。</p>
 */
public final class JeiMarkNames {

    /**
     * JEI 的运行时对象只在客户端会话内有效，由 {@code PatternDiskJeiPlugin#onRuntimeAvailable} 写入。
     */
    @Nullable
    private static IJeiRuntime runtime;

    private JeiMarkNames() {
    }

    public static void setRuntime(@Nullable IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    /** 当前 JEI 运行时；JEI 不在场或还没起来时为空（收藏夹那些读法都以它为前提）。 */
    @Nullable
    public static IJeiRuntime runtime() {
        return runtime;
    }

    /** 类别 {@code id} 的可读名（JEI 的类别标题），没有这个类别时为 {@code null}。 */
    @Nullable
    public static Component find(String id) {
        var category = findCategory(id);
        return category == null ? null : category.getTitle();
    }

    @Nullable
    private static IRecipeCategory<?> findCategory(String id) {
        var manager = recipeManager();
        var type = findType(id);
        if (manager == null || type == null) {
            return null;
        }
        // 未注册的类型必须先经 getRecipeType 判空：getRecipeCategory 对未知类型会抛异常。
        return manager.getRecipeCategory(type);
    }

    @Nullable
    private static RecipeType<?> findType(String id) {
        var manager = recipeManager();
        var parsed = id == null ? null : ResourceLocation.tryParse(id);
        if (manager == null || parsed == null) {
            return null;
        }
        return manager.getRecipeType(parsed).orElse(null);
    }

    @Nullable
    private static IRecipeManager recipeManager() {
        return runtime == null ? null : runtime.getRecipeManager();
    }
}
