package io.github.lounode.ae2pattern.client.gui;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import net.neoforged.fml.ModList;

import io.github.lounode.ae2pattern.common.menu.DiskMarkRules;

/**
 * 标记对应的「催化剂 / 工作方块」图标：JEI 与 EMI 各自存这一份数据，接口与叫法都不同，这里做一层收口。
 *
 * <p>查不到催化剂<b>物品</b>不等于查不到图标：MEK 的粉碎机、ECO 的集成工作站这类模组只给类别注册了一张
 * 贴图，没往催化剂注册表里放东西——所以在 JEI 界面里看得见那台机器，只查物品的写法却什么都拿不到。
 * 回退顺序因此是「物品优先，贴图兜底」：EMI 的工作台表 → EMI 配方自带的催化剂 → JEI 的催化剂表 →
 * EMI 类别贴图 → JEI 类别贴图 → 该标记对应的样板物品。</p>
 *
 * <p>标记指向哪个配方类别由 {@link MarkCategoryIndex} 那层隐藏对照表判定（盘上存的可能是原始类别 id，
 * 也可能是打标时从搜索栏抄下来的可读名）。图标按标记缓存：候选格子每帧都要它，而每次查询都要遍历
 * EMI/JEI 的类别表，21 个格子就是每秒上千次全表扫描；查不到的结果同样缓存。</p>
 */
public final class MarkCatalysts {

    private static final Logger LOGGER = LoggerFactory.getLogger(MarkCatalysts.class);

    /** 多个催化剂之间的轮换节拍（毫秒）。 */
    private static final long ROTATE_MILLIS = 1500L;

    /** 只报一次：结果按标记缓存，但异常可能每帧都来一遍。 */
    private static boolean warned;

    /** 标记 → 图标。 */
    private static final Map<String, Icon> CACHE = new ConcurrentHashMap<>();

    private MarkCatalysts() {
    }

    /** 候选格子的图标：一组轮换的物品，或一张类别贴图，或什么都没有。 */
    @FunctionalInterface
    public interface Icon {
        void render(GuiGraphics graphics, int x, int y);
    }

    /** 什么都没有：格子上只留三态底图。 */
    public static final Icon EMPTY = (graphics, x, y) -> {
    };

    /** 该标记的图标；一条都查不到时返回 {@link #EMPTY}。 */
    public static Icon iconFor(@Nullable String mark) {
        if (mark == null || mark.isEmpty()) {
            return EMPTY;
        }
        try {
            return CACHE.computeIfAbsent(mark, MarkCatalysts::lookup);
        } catch (Throwable failed) {
            // 图标查询走 EMI/JEI 的类别表与配方表，两边都是第三方实现：它们意外时宁可格子空着，
            // 也不能让供应器界面开不出来（这条链在每帧渲染里跑）。
            if (!warned) {
                warned = true;
                LOGGER.warn("查询标记 {} 的催化剂图标失败，该格子留空；后续同类异常不再重复记录", mark, failed);
            }
            return EMPTY;
        }
    }

    /** 资源重载（F3+T）后 EMI/JEI 的类别表可能换了一批，旧图标与旧映射都要作废。 */
    public static void clearCache() {
        CACHE.clear();
        MarkCategoryIndex.clear();
    }

    /** 一组催化剂物品，按固定节拍轮换；空表折叠成 {@link #EMPTY}，好让调用方用同一句判空。 */
    static Icon rotating(List<ItemStack> stacks) {
        if (stacks.isEmpty()) {
            return EMPTY;
        }
        var snapshot = List.copyOf(stacks);
        return (graphics, x, y) -> {
            int index = (int) ((System.currentTimeMillis() / ROTATE_MILLIS) % snapshot.size());
            graphics.renderItem(snapshot.get(index), x, y);
        };
    }

    private static Icon lookup(String mark) {
        var category = MarkCategoryIndex.of(mark);
        var emiPresent = ModList.get().isLoaded("emi");
        var jeiPresent = ModList.get().isLoaded("jei");

        if (category != null) {
            if (emiPresent) {
                var items = rotating(EmiCatalysts.findItems(category));
                if (items != EMPTY) {
                    return hit(mark, category, "EMI 工作台表", items);
                }
            }
            if (jeiPresent) {
                var items = rotating(JeiCatalysts.findItems(category));
                if (items != EMPTY) {
                    return hit(mark, category, "JEI 催化剂表", items);
                }
            }
            // 物品一条都没有：这些模组只给了类别贴图。
            if (emiPresent) {
                var drawable = EmiCatalysts.categoryIcon(category);
                if (drawable != null) {
                    return hit(mark, category, "EMI 类别贴图", drawable);
                }
            }
            if (jeiPresent) {
                var drawable = JeiCatalysts.categoryIcon(category);
                if (drawable != null) {
                    return hit(mark, category, "JEI 类别贴图", drawable);
                }
            }
        }

        // 最后一档：那个标记对应的样板物品本身。雕凿、处理档与手写标记没有唯一工作方块，
        // 空着会让玩家分不清「没画」与「查不到」。
        var patternItem = patternItemOf(mark);
        if (patternItem != null) {
            return hit(mark, category, "样板物品 " + patternItem,
                    rotating(List.of(new ItemStack(patternItem))));
        }

        return hit(mark, category, "什么都没查到（EMI=" + emiPresent + "，JEI=" + jeiPresent + "）", EMPTY);
    }

    /**
     * 记一条命中日志。用 INFO 而不是 DEBUG，是因为排查时没人想为此改日志配置；而结果按标记缓存，
     * 一个标记在一次会话里只会打这一行，不会刷屏。
     */
    private static Icon hit(String mark, @Nullable ResourceLocation category, String tier, Icon icon) {
        LOGGER.info("标记 {} 的候选图标来自 {}（类别 {}）", mark, tier, category);
        return icon;
    }

    /** 标记归一到的样板物品；认不出（玩家手写文本）或那台模组没装时为 {@code null}。 */
    @Nullable
    private static Item patternItemOf(String mark) {
        var patternId = DiskMarkRules.patternTypeForMark(mark);
        if (patternId == null) {
            return null;
        }
        var parsed = ResourceLocation.tryParse(patternId);
        if (parsed == null) {
            return null;
        }
        // 那个样板可能来自没装的模组（advanced_ae / rechiseledae）：取不到就当作没有，别拿空气去画。
        var item = BuiltInRegistries.ITEM.get(parsed);
        return item == null || item == Items.AIR ? null : item;
    }
}
