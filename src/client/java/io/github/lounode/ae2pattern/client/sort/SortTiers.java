package io.github.lounode.ae2pattern.client.sort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.ToIntFunction;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

import io.github.lounode.ae2pattern.config.AEPDConfig;

/**
 * 附加排序的层级表：把「基础工厂 / 高级工厂 / 精英工厂」这类不带数字的名字，按配置里给的阶层顺序排。
 *
 * <p>表在 {@code config/ae2_pattern_disk-client.toml} 里，每条是一组：可选 mod 范围 + 从低到高的层级词。
 * 组之间按声明顺序匹配，第一组命中就用它的顺序；组前带 mod 范围是为了隔开同名层级词在不同模组里的含义
 * （「基础」在 Mekanism 与 Powah 里都有，不隔开就会互相抢）。</p>
 *
 * <p>层级序号插在「mod」和「去数字分组」之间：命中层级的排在没命中的前面，同一层级组内再按原有的分组与
 * 数值序排。没命中任何组的返回 {@link #UNRANKED}（最大整数），于是退回改动前的行为——附加排序只是多给
 * 已知的阶层加了序，没配的东西排序照旧。</p>
 */
public final class SortTiers {

    /** 没命中任何层级组：排在所有命中的层级之后，不改变它们之间的原有次序。 */
    public static final int UNRANKED = Integer.MAX_VALUE;

    /** 一组：{@code modFilter} 为 {@code null} 表示不限模组；{@code words} 从低阶到高阶，每项是若干同义写法。 */
    private record Group(@Nullable String modFilter, List<List<String>> words) {

        /** 名称命中的最低层级下标；哪一组都不命中返回 -1。 */
        int rankOf(String displayName, String registryName) {
            for (int index = 0; index < this.words.size(); index++) {
                for (var spelling : this.words.get(index)) {
                    if (contains(displayName, spelling) || contains(registryName, spelling)) {
                        return index;
                    }
                }
            }
            return -1;
        }

        boolean covers(String modId) {
            if (this.modFilter == null) {
                return true;
            }
            if (this.modFilter.endsWith("*")) {
                return modId.startsWith(this.modFilter.substring(0, this.modFilter.length() - 1));
            }
            return modId.equals(this.modFilter);
        }
    }

    /** 解析结果；{@code parsedFrom} 是解析时那份配置值，换了一份就重新解析。 */
    private static List<Group> groups = List.of();
    private static @Nullable Object parsedFrom;

    private SortTiers() {
    }

    /**
     * 拿一个带缓存的排名函数（物品网格：每个元素是 {@code AEKey}）。一次重排里同一件东西会被比很多次，
     * 缓存省掉重复的字符串扫描；缓存活得和返回的函数一样长，每次重排新建一个，不会跨屏积起来。
     */
    public static ToIntFunction<AEKey> ranker() {
        // 按 key 缓存而不是按显示名：阶还取决于 mod 与注册名，同名不同来源的两件东西不能共用一次结果。
        var cache = new HashMap<AEKey, Integer>();
        return key -> cache.computeIfAbsent(key, k -> rankOf(k.getModId(), registryNameOf(k),
                k.getDisplayName().getString()));
    }

    /**
     * 同上，但给管理终端的盘内样板用——调用方传的是格子实际显示的那个栈（样板的主产物；主产物解不出来
     * 时是样板本体）。缓存键用注册名加显示名拼成，不拿 {@code ItemStack} 本身当键：它会变，当 map 键不稳。
     */
    public static ToIntFunction<ItemStack> rankerForItems() {
        var cache = new HashMap<String, Integer>();
        return stack -> {
            var registryName = registryNameOf(stack);
            var displayName = stack.getHoverName().getString();
            return cache.computeIfAbsent(registryName + '\u0000' + displayName,
                    ignored -> rankOf(NaturalSort.modIdOf(stack), registryName, displayName));
        };
    }

    /** 丢掉解析结果，下次排名按当前配置重来。配置对象自己换了时这里会自动重解析，这个入口留给显式调用。 */
    public static void invalidate() {
        groups = List.of();
        parsedFrom = null;
    }

    private static int rankOf(String modId, String registryName, String displayName) {
        var name = displayName.toLowerCase(Locale.ROOT);
        var registry = registryName.toLowerCase(Locale.ROOT);
        for (var group : groups()) {
            if (!group.covers(modId)) {
                continue;
            }
            int rank = group.rankOf(name, registry);
            if (rank >= 0) {
                return rank;
            }
        }
        return UNRANKED;
    }

    private static String registryNameOf(AEKey key) {
        return key instanceof AEItemKey itemKey
                ? BuiltInRegistries.ITEM.getKey(itemKey.getItem()).toString()
                : "";
    }

    private static String registryNameOf(ItemStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static List<Group> groups() {
        var configured = configuredTiers();
        if (configured != parsedFrom) {
            groups = parse(configured);
            parsedFrom = configured;
        }
        return groups;
    }

    /**
     * 当前配置里的表。配置还没加载时（例如在专用服务端、或配置界面正在重建 spec 的间隙）拿到的是空表，
     * 层级序整体不参与排序——这种时候不该因为一个视图设置把游戏弄崩。
     */
    private static List<? extends String> configuredTiers() {
        try {
            var value = AEPDConfig.ADDITIONAL_SORT_TIERS.get();
            return value == null ? List.of() : value;
        } catch (Throwable unavailable) {
            return List.of();
        }
    }

    /**
     * 解析一行一组：{@code <mod filter>:词,词,...}。范围可以省略；{@code *} 与空串都当「不限」。
     * 单个词写成 {@code 中文|english} 时任一写法命中即可；默认表直接用注册名里的英文片段，
     * 所以中英文客户端都能命中。
     */
    private static List<Group> parse(List<? extends String> raw) {
        var parsed = new ArrayList<Group>();
        for (var entry : raw) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            // 中文输入法下的全角冒号与逗号一并认：不认的话整行会退化成“一个词”而静默不命中。
            var body = entry.trim().replace('：', ':').replace('，', ',');
            String modFilter = null;
            int colon = body.indexOf(':');
            if (colon >= 0) {
                var filter = body.substring(0, colon).trim();
                body = body.substring(colon + 1);
                if (!filter.isEmpty() && !filter.equals("*")) {
                    modFilter = filter;
                }
            }
            var words = new ArrayList<List<String>>();
            for (var word : body.split(",")) {
                var spellings = new ArrayList<String>();
                for (var spelling : word.split("\\|")) {
                    var trimmed = spelling.trim().toLowerCase(Locale.ROOT);
                    if (!trimmed.isEmpty()) {
                        spellings.add(trimmed);
                    }
                }
                if (!spellings.isEmpty()) {
                    words.add(List.copyOf(spellings));
                }
            }
            if (!words.isEmpty()) {
                parsed.add(new Group(modFilter, List.copyOf(words)));
            }
        }
        return List.copyOf(parsed);
    }

    private static boolean contains(String haystack, String needle) {
        return !haystack.isEmpty() && !needle.isEmpty() && haystack.contains(needle);
    }
}
