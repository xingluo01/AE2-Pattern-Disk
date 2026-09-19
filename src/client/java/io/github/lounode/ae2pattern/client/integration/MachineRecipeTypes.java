package io.github.lounode.ae2pattern.client.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import net.neoforged.fml.ModList;

/**
 * 「鼠标上拿着哪个工作方块 → 它跑哪类配方」的反查：磁盘的标记（{@code DISK_PREFIX}）靠它决定该写哪个类别。
 *
 * <p>没有这条反查，标记就只能来自「刚刚导入过配方」这个会话状态：切一次模式或重开一次界面就没了，于是
 * 拿工作方块右键只能退回模式标记（显示成「合成」/「处理样板」）——写下的标记与光标上那个工作方块无关。</p>
 *
 * <p>索引在首次使用时从配方查看器反查一次（JEI 的类别催化剂、EMI 的类别工作站）后留在内存里，不落盘：
 * 类别会随 mod 列表与 {@code /reload} 变化，写文件只会多出一份需要失效、需要同步的真相源；而这份反查
 * 一次只有毫秒级。</p>
 */
public final class MachineRecipeTypes {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.mark");

    /**
     * 原版工作方块 → 类别 id 的兜底表，只在两个查看器都给不出答案时用。
     *
     * <p>查看器理论上都登记了这几条（EMI 在 {@code VanillaPlugin} 里 {@code addWorkstation}，JEI 在催化剂
     * 注册里登记），但反查链路上任何一环出问题——查看器还没 bake、资源包覆盖了类别图标、玩家把该物品设成
     * 隐藏——光标上最普通的工作台就会认不出来，而这恰好是最不该失败的一档。</p>
     *
     * <p>只写这七个原版类别：它们的 id 在 EMI 与 JEI 里完全一致（都取自原版 {@code RecipeType} 注册表键）。
     * 铁砧 / 磨石 / 堆肥桶 / 燃料没有写死——两边的 id 并不一致（EMI {@code emi:anvil_repairing} vs JEI
     * {@code minecraft:anvil}），写死只会让其中一边把原始 id 当名字显示。</p>
     */
    private static final Map<Item, ResourceLocation> VANILLA_WORKSTATIONS = Map.ofEntries(
            Map.entry(Items.CRAFTING_TABLE, ResourceLocation.withDefaultNamespace("crafting")),
            Map.entry(Items.FURNACE, ResourceLocation.withDefaultNamespace("smelting")),
            Map.entry(Items.BLAST_FURNACE, ResourceLocation.withDefaultNamespace("blasting")),
            Map.entry(Items.SMOKER, ResourceLocation.withDefaultNamespace("smoking")),
            Map.entry(Items.CAMPFIRE, ResourceLocation.withDefaultNamespace("campfire_cooking")),
            Map.entry(Items.SOUL_CAMPFIRE, ResourceLocation.withDefaultNamespace("campfire_cooking")),
            Map.entry(Items.STONECUTTER, ResourceLocation.withDefaultNamespace("stonecutting")),
            Map.entry(Items.SMITHING_TABLE, ResourceLocation.withDefaultNamespace("smithing")));

    /**
     * 查看器自造的「资源／信息类」伪类别：它们确实能被物品“运行”，但那不是一台机器。
     *
     * <p>JEI 把 {@code FUELING}/{@code COMPOSTING}/{@code INFORMATION} 当普通类别登记，还给熔炉、烟熏炉、
     * 高炉挂了「燃料」催化剂——不排掉的话，光标上拿着熔炉反查会同时得到 {@code minecraft:fuel} 与
     * {@code minecraft:smelting}，按 id 取最小就写成了「燃料」。EMI 侧对应的 {@code emi:fuel}／
     * {@code emi:info} 本来就没有工作站登记、图标也不是方块，列在这里只是让两边的口径一致。</p>
     */
    private static final Set<ResourceLocation> PSEUDO_CATEGORIES = Set.of(
            ResourceLocation.withDefaultNamespace("fuel"),
            ResourceLocation.withDefaultNamespace("compostable"),
            ResourceLocation.parse("jei:information"),
            ResourceLocation.parse("emi:fuel"),
            ResourceLocation.parse("emi:info"));

    private MachineRecipeTypes() {
    }

    /**
     * 光标上拿着 {@code held} 时该往标记里写哪个类别 id；它不是任何类别的工作方块时返回 {@code null}，调用方
     * 保持原有行为（退回刚导入的配方类别，两样都没有就这次右键不写标记）。
     *
     * <p>取舍顺序：先看这个工作方块能不能跑 {@code importedCategory}——刚导入的配方就是它的话，那是最
     * 贴切的答案；否则优先与工作方块同命名空间的类别（AE2 的工作方块配 AE2 的类别）；都挑不出来就取 id
     * 最小的一个，保证同一个工作方块每次写出同一个标记。</p>
     */
    @Nullable
    public static String forHeldMachine(ItemStack held, @Nullable String importedCategory) {
        if (held.isEmpty()) {
            return null;
        }
        var candidates = candidates(held.getItem());
        if (candidates.isEmpty()) {
            return null;
        }
        if (importedCategory != null && !importedCategory.isEmpty()) {
            var parsed = ResourceLocation.tryParse(importedCategory);
            if (parsed != null && candidates.contains(parsed)) {
                return importedCategory;
            }
        }
        var namespace = itemNamespace(held.getItem());
        if (namespace != null) {
            for (var candidate : candidates) {
                if (namespace.equals(candidate.getNamespace())) {
                    return candidate.toString();
                }
            }
        }
        return candidates.get(0).toString();
    }

    /**
     * 认不出工作方块时，把反查链路的现场拼成一行给日志用：只知道「认不出」，没法区分是查看器没就绪、索引
     * 是空的，还是这个物品真的没被任何类别登记。诊断本身允许按需建表——它只在失败路径上调用。
     */
    public static String diagnose(ItemStack held) {
        var item = held.getItem();
        var parts = new ArrayList<String>();
        if (ModList.get().isLoaded("emi")) {
            parts.add(EmiMarkNames.diagnose(item));
        }
        if (ModList.get().isLoaded("jei")) {
            parts.add(JeiMarkNames.diagnose(item));
        }
        if (parts.isEmpty()) {
            parts.add("没有安装 EMI / JEI：反查表没有来源");
        }
        var vanilla = VANILLA_WORKSTATIONS.get(item);
        parts.add("原版兜底表=" + (vanilla == null ? "无此方块" : vanilla.toString()));
        return String.join("；", parts);
    }

    /** 装了哪个配方查看器就问哪个，与标记的显示同一口径（EMI 优先，其次 JEI）。
     *
     * <p>候选分三档：①查看器里明确登记的工作站（能跑这类配方的机器）；②「这个物品是哪些类别的图标」
     * （只有 EMI 有这档，且只收方块）——图标未必是机器，EMI 自己的 {@code emi:tag}、AE2 的
     * {@code ae2:item_transformation} 拿的是命名牌、产物当图标，所以它排在工作站档之后；③原版兜底表，
     * 只在查看器都给不出答案时才用。</p>
     */
    private static List<ResourceLocation> candidates(Item item) {
        var found = byTier(item);
        // 排掉资源／信息类伪类别：光标上拿着熔炉时 JEI 会同时给出 {minecraft:fuel, minecraft:smelting}，不清掉前者
        // 就会按 id 取最小写成「燃料」。全被清空时保留原位——堆肥桶这类物品本来就只有伪类别对得上，认出来
        // 总比认不出来强。
        var real = found.stream().filter(id -> !PSEUDO_CATEGORIES.contains(id)).toList();
        return real.isEmpty() ? found : real;
    }

    /** 三档依次取候选：工作站 → 类别图标 → 原版兜底表。 */
    private static List<ResourceLocation> byTier(Item item) {
        var workstations = byViewer(item, true);
        if (!workstations.isEmpty()) {
            return workstations;
        }
        var icons = byViewer(item, false);
        if (!icons.isEmpty()) {
            return icons;
        }
        // 查看器都给不出答案时才落到原版兜底表：光标上拿着工作台却认不出类别，是这一整条链路最不该出现的失败。
        var vanilla = VANILLA_WORKSTATIONS.get(item);
        return vanilla == null ? List.of() : List.of(vanilla);
    }

    private static List<ResourceLocation> byViewer(Item item, boolean workstations) {
        if (ModList.get().isLoaded("emi")) {
            try {
                var ids = workstations ? EmiMarkNames.machineCategories(item) : EmiMarkNames.iconCategories(item);
                if (!ids.isEmpty()) {
                    return ids;
                }
            } catch (Throwable failed) {
                // 别把异常吞掉：认不出时最需要知道的就是「是不是这条路炸了」，日志是唯一线索。
                LOGGER.warn("EMI 工作方块反查失败：{}", item, failed);
            }
        }
        if (ModList.get().isLoaded("jei")) {
            try {
                // JEI 的类别图标是绘制对象（getIcon 返回 IDrawable），没有物品可拿，所以图标档只有 EMI 有。
                return workstations ? JeiMarkNames.machineCategories(item) : List.of();
            } catch (Throwable failed) {
                LOGGER.warn("JEI 工作方块反查失败：{}", item, failed);
            }
        }
        return List.of();
    }

    /** 查看器重新装载（JEI 的运行时对象每次装载都会换一个）后丢弃索引，下一次重新反查。 */
    public static void invalidate() {
        JeiMarkNames.invalidateMachineIndex();
        if (ModList.get().isLoaded("emi")) {
            // EMI 缺席时不必去碰它的类（虽然那条路径不动 EMI 类型，少一次类加载更干净）。
            EmiMarkNames.invalidateMachineIndex();
        }
    }

    @Nullable
    private static String itemNamespace(Item item) {
        var key = BuiltInRegistries.ITEM.getKey(item);
        return key == null ? null : key.getNamespace();
    }
}
