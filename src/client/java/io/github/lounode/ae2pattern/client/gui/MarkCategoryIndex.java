package io.github.lounode.ae2pattern.client.gui;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import net.neoforged.fml.ModList;

import io.github.lounode.ae2pattern.common.menu.DiskMarkRules;

/**
 * 标记 → 配方类别原始 id 的**隐藏对照表**。
 *
 * <p>磁盘上的标记有两种写法：导入配方时写下的 {@code #<类别 id>}（{@code mekanism:crusher}），以及打标
 * 链路从搜索栏抄下来的 {@code #<可读名>}（{@code DiskEntryFilter.markSearchTerm} 会先把 id 翻成「粉碎」
 * 这样的名字，右键打标写进盘的就是那串）。后者在盘上没留下任何 id，只能运行期回 EMI/JEI 的类别表里
 * 按名字找回来。</p>
 *
 * <p>这一层是<b>隐藏</b>的：结果只喂给确实需要知道类别的功能（目前只有标记式供应器取催化剂图标），
 * 不回写磁盘，也不参与样板磁盘终端的分组与搜索——那两处仍按 {@code DiskMarkRules} 的原始 id 口径走，
 * 行为与从前一致。</p>
 */
public final class MarkCategoryIndex {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("ae2_pattern_disk.marks");

    /** 只报一次：查询按标记缓存，但异常可能每帧都来一遍。 */
    private static boolean warned;

    /** 标记 → 类别 id；查不到也要记下来（{@code Optional.empty()}），否则每帧都要重跑一遍类别遍历。 */
    private static final Map<String, Optional<ResourceLocation>> CACHE = new ConcurrentHashMap<>();

    private MarkCategoryIndex() {
    }

    /** 这个标记指向哪个配方类别；认不出（玩家自写的名字在两边都没有同名类别）时返回 {@code null}。 */
    @Nullable
    public static ResourceLocation of(@Nullable String mark) {
        if (mark == null || mark.isEmpty()) {
            return null;
        }
        try {
            return CACHE.computeIfAbsent(mark, MarkCategoryIndex::resolve).orElse(null);
        } catch (Throwable failed) {
            // 这条链在屏幕每帧渲染里跑，抛出去就是崩游戏（实机已经因为一个 null 枚举 switch 崩过一次）。
            // 配方查看器意外时按「认不出类别」处理：图标退到兜底档，界面照常开。
            if (!warned) {
                warned = true;
                LOGGER.warn("解析标记 {} 指向的配方类别失败，按「认不出类别」处理；后续同类异常不再重复记录",
                        mark, failed);
            }
            return null;
        }
    }

    /** 资源重载（F3+T）后类别表可能换了一批，旧映射要作废。 */
    public static void clear() {
        CACHE.clear();
    }

    private static Optional<ResourceLocation> resolve(String mark) {
        var byId = fromIdentifier(mark);
        if (byId != null) {
            return Optional.of(byId);
        }
        // 手写/抄来的可读名：回配方查看器按类别标题找。
        if (!mark.startsWith("#") || mark.length() < 2) {
            return Optional.empty();
        }
        var title = mark.substring(1);
        if (ModList.get().isLoaded("emi")) {
            var fromEmi = EmiCatalysts.categoryByTitle(title);
            if (fromEmi != null) {
                return Optional.of(fromEmi);
            }
        }
        if (ModList.get().isLoaded("jei")) {
            var fromJei = JeiCatalysts.categoryByTitle(title);
            if (fromJei != null) {
                return Optional.of(fromJei);
            }
        }
        return Optional.empty();
    }

    /**
     * 标记本身就带着类别 id 的那两种写法：{@code #<类别 id>} 与 {@code #mode:<模式>}。
     * 归一规则收在 {@link DiskMarkRules#categoryIdOfMark}（那里对认不出的模式名返回 null，
     * 不会把 null 喂给枚举 switch——实机崩过一次），这里只负责把字符串解析成资源 id。
     */
    @Nullable
    private static ResourceLocation fromIdentifier(String mark) {
        var categoryId = DiskMarkRules.categoryIdOfMark(mark);
        return categoryId == null ? null : ResourceLocation.tryParse(categoryId);
    }

    /**
     * 类别标题是否指的就是这个标记：先认完全相等，再认「标题里含这个标记」。
     *
     * <p>实测的三种写法都要认：{@code #粉碎} 对「粉碎」、{@code #融合机} 对「融合机」，以及
     * {@code #集成工作站} 对「<b>大型</b>集成工作站」——最后这种是标记省了前缀，只有子串包含认得出来。
     * 命中多个时由调用方优先取完全相等的那个。</p>
     */
    static boolean titleMatches(@Nullable Component title, String wanted) {
        if (title == null || wanted.isEmpty()) {
            return false;
        }
        var text = title.getString();
        return !text.isEmpty()
                && (text.equalsIgnoreCase(wanted) || text.contains(wanted) || wanted.contains(text));
    }

    /** 完全相等优先：命中多个候选时用它挑出最像的那一个。 */
    static boolean titleEquals(@Nullable Component title, String wanted) {
        return title != null && title.getString().equalsIgnoreCase(wanted);
    }
}
