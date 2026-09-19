package io.github.lounode.ae2pattern.client.integration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;

/**
 * 磁盘标记里需要 JEI 的那部分：配方类别名与运行它的机器名。对应 EMI 侧的 {@link EmiMarkNames}。
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

    /** 类别 {@code id} 的可读名（JEI 的类别标题），没有这个类别时为 {@code null}。 */
    @Nullable
    public static Component find(String id) {
        var category = findCategory(id);
        return category == null ? null : category.getTitle();
    }

    /**
     * 跑这类配方的机器名（如“烟熏炉”），没有这个类别或它没有机器物品时为 {@code null}。
     *
     * <p>JEI 把机器放在类别的催化剂里（EMI 放在 category.icon），所以取第一件可用的催化剂物品当机器名。</p>
     */
    @Nullable
    public static String machineName(String id) {
        var manager = recipeManager();
        var type = findType(id);
        if (manager == null || type == null) {
            return null;
        }
        return manager.createRecipeCatalystLookup(type)
                .includeHidden()
                .getItemStack()
                .filter(stack -> !stack.isEmpty())
                .findFirst()
                .map(stack -> stack.getHoverName().getString())
                .orElse(null);
    }

    /**
     * 这台机器（物品）能跑的配方类别 id。
     *
     * <p>JEI 把机器登记成类别的催化剂，所以反查要遍历所有类别的催化剂；结果按 id 排序，同一台机器对应
     * 多个类别时调用方才能得到稳定的取舍。索引只建一次、只放内存（见 {@code MachineRecipeTypes}）。</p>
     */
    public static List<ResourceLocation> machineCategories(Item item) {
        return machineIndex().getOrDefault(item, List.of());
    }

    /** JEI 重新装载（运行时对象重建）后，索引必须跟着重建，否则会拿着上一份类别的快照。 */
    public static void invalidateMachineIndex() {
        machineIndex = null;
    }

    /** 诊断用：运行时是否就绪、反查表里有多少物品、本物品在不在里面。反查失败时才调用。 */
    public static String diagnose(Item item) {
        if (runtime == null) {
            return "JEI：运行时尚未就绪（插件还没拿到 IJeiRuntime）";
        }
        if (recipeManager() == null) {
            return "JEI：运行时在，但配方管理器为 null";
        }
        var index = machineIndex();
        return "JEI：催化剂反查表 " + index.size() + " 种物品，本物品在表="
                + (index.containsKey(item) ? "是" : "否");
    }

    @Nullable
    private static Map<Item, List<ResourceLocation>> machineIndex;

    private static Map<Item, List<ResourceLocation>> machineIndex() {
        var cached = machineIndex;
        if (cached != null) {
            return cached;
        }
        var manager = recipeManager();
        if (manager == null) {
            // 运行时还没到位：别把空结果缓存下来，下一次再来建。
            return Map.of();
        }
        var index = new HashMap<Item, List<ResourceLocation>>();
        manager.createRecipeCategoryLookup().includeHidden().get().forEach(category -> {
            var type = category.getRecipeType();
            manager.createRecipeCatalystLookup(type)
                    .includeHidden()
                    .getItemStack()
                    .filter(stack -> !stack.isEmpty())
                    .map(ItemStack::getItem)
                    .forEach(item -> index.computeIfAbsent(item, key -> new ArrayList<>()).add(type.getUid()));
        });
        var frozen = new HashMap<Item, List<ResourceLocation>>();
        index.forEach((item, ids) -> frozen.put(item, ids.stream().distinct().sorted().toList()));
        machineIndex = Map.copyOf(frozen);
        return machineIndex;
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
