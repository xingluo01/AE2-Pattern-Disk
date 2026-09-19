package io.github.lounode.ae2pattern.client.integration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.stack.EmiStack;

/**
 * The part of the disk-mark tooltip that needs EMI: recipe category names and the machines they run on.
 * A category is where a mod's machine gets its localized name ("精密锯木机", "烟熏", ...), which is what a
 * mark should read as, and its workstation is the block that machine is ("烟熏炉").
 *
 * <p>EMI is a compile-time dependency only, so nothing outside this class may name its types - a client
 * without EMI would otherwise fail to load whatever touches them. Callers check that the mod is present
 * before coming here.</p>
 */
public final class EmiMarkNames {

    private EmiMarkNames() {
    }

    /** The display name of the recipe category {@code id} names, or {@code null} when there is none. */
    @Nullable
    public static Component find(String id) {
        var category = findCategory(id);
        return category == null ? null : category.getName();
    }

    /**
     * The name of the block that runs recipes of category {@code id}, such as "烟熏炉", or {@code null}
     * when there is no such category or it has no block to show.
     *
     * <p>EMI keeps the machine on the category's icon (the recipe itself only names it as a catalyst), which
     * for vanilla categories is the workstation block. Categories advertised by a fluid or a custom renderable
     * have nothing to take a name from.</p>
     */
    @Nullable
    public static String machineName(String id) {
        var category = findCategory(id);
        if (category == null) {
            return null;
        }
        if (category.icon instanceof EmiStack stack) {
            var itemStack = stack.getItemStack();
            if (!itemStack.isEmpty()) {
                return itemStack.getHoverName().getString();
            }
        }
        return null;
    }

    /**
     * 这台机器（物品）明确登记为工作站的类别：查看器里「能跑这类配方的机器」这份表。
     *
     * <p>结果按 id 排序，同一台机器对应多个类别时调用方才能得到稳定取舍。索引只建一次、只放内存
     * （见 {@code MachineRecipeTypes}）。</p>
     */
    public static List<ResourceLocation> machineCategories(Item item) {
        return indices().workstations().getOrDefault(item, List.of());
    }

    /**
     * 第二档：把物品当作**类别图标**的那批类别。不少模组只填类别图标、从不登记工作站，只靠上一档会
     * 反查不到它们——但图标不一定就是机器（EMI 自己的 {@code emi:tag} 图标是命名牌、AE2 的
     * {@code ae2:item_transformation} 图标是配方产物充能石英），所以这里只收方块类物品，且由调用方
     * 列在工作站档之后当降级。
     */
    public static List<ResourceLocation> iconCategories(Item item) {
        return indices().icons().getOrDefault(item, List.of());
    }

    /** 诊断用：管理器是否就绪、两张表各有多少物品、本物品在不在里面。反查失败时才调用。 */
    public static String diagnose(Item item) {
        var manager = EmiApi.getRecipeManager();
        if (manager == null) {
            return "EMI：配方管理器未就绪（还没 bake，或正在重载）";
        }
        var index = indices();
        if (index.workstations().isEmpty() && index.icons().isEmpty()) {
            return "EMI：管理器就绪，但类别列表为空（bake 未完成或正在重载）";
        }
        return "EMI：类别 " + manager.getCategories().size() + " 个，工作站表 " + index.workstations().size()
                + " 种物品、图标表 " + index.icons().size() + " 种物品，本物品在工作站表="
                + (index.workstations().containsKey(item) ? "是" : "否") + "、在图标表="
                + (index.icons().containsKey(item) ? "是" : "否");
    }

    /**
     * EMI rebuilds its recipe tree on resource reloads and starts out on an empty one, so the index is tied to
     * the manager instance it was read from. EMI-only clients have no invalidate hook of their own - the
     * instance check plus the "an empty result is not cached" rule below are what keep it honest.
     */
    public static void invalidateMachineIndex() {
        indices = null;
        indexedManager = null;
    }

    /** 两档候选：显式登记的工作站，以及只能靠类别图标认出来的那批。 */
    private record Indices(Map<Item, List<ResourceLocation>> workstations,
            Map<Item, List<ResourceLocation>> icons) {
    }

    private static final Indices EMPTY_INDICES = new Indices(Map.of(), Map.of());

    @Nullable
    private static Indices indices;

    /** 索引是根据哪个 manager 实例建的；EMI 每次 bake 都换一个实例，换了就说明该重建。 */
    @Nullable
    private static EmiRecipeManager indexedManager;

    private static Indices indices() {
        var manager = EmiApi.getRecipeManager();
        if (manager == null) {
            return EMPTY_INDICES;
        }
        var cached = indices;
        if (cached != null && manager == indexedManager) {
            return cached;
        }
        var workstations = new HashMap<Item, List<ResourceLocation>>();
        var icons = new HashMap<Item, List<ResourceLocation>>();
        for (var category : manager.getCategories()) {
            var id = category.getId();
            if (category.icon instanceof EmiStack iconStack) {
                var iconItem = iconStack.getItemStack();
                // 只要方块：非方块的图标（命名牌/书/指南针/充能石英）是「类别代表物」，拿它们反查会把
                // 与手中物品无关的类别写进标记，比认不出来更糟。
                if (!iconItem.isEmpty() && iconItem.getItem() instanceof BlockItem) {
                    icons.computeIfAbsent(iconItem.getItem(), key -> new ArrayList<>()).add(id);
                }
            }
            for (var workstation : manager.getWorkstations(category)) {
                for (var stack : workstation.getEmiStacks()) {
                    var itemStack = stack.getItemStack();
                    if (itemStack.isEmpty()) {
                        continue;
                    }
                    workstations.computeIfAbsent(itemStack.getItem(), key -> new ArrayList<>()).add(id);
                }
            }
        }
        if (workstations.isEmpty() && icons.isEmpty()) {
            // 两档都空只可能是「EMI 还没 bake」或者「正在重载」：缓存下来会让这个会话里的「光标上拿工作方块右键」永久失效。
            return EMPTY_INDICES;
        }
        indices = new Indices(freeze(workstations), freeze(icons));
        indexedManager = manager;
        return indices;
    }

    private static Map<Item, List<ResourceLocation>> freeze(Map<Item, List<ResourceLocation>> raw) {
        var frozen = new HashMap<Item, List<ResourceLocation>>();
        raw.forEach((item, ids) -> frozen.put(item, ids.stream().distinct().sorted().toList()));
        return Map.copyOf(frozen);
    }

    @Nullable
    private static EmiRecipeCategory findCategory(String id) {
        var parsed = ResourceLocation.tryParse(id);
        if (parsed == null) {
            return null;
        }
        for (var category : EmiApi.getRecipeManager().getCategories()) {
            if (category.getId().equals(parsed)) {
                return category;
            }
        }
        return null;
    }
}
