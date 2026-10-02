package io.github.lounode.ae2pattern.client.gui;

import java.util.Locale;
import java.util.regex.Pattern;

import org.jetbrains.annotations.Nullable;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.client.integration.JechPinyin;
import io.github.lounode.ae2pattern.client.gui.DiskListPanel.DiskEntry;

/**
 * 磁盘列表的搜索口径：搜索栏文本怎么解析、一张盘为什么留下或被剔除。
 *
 * <p><b>没有搜索条件就什么都不剔除</b>（含无标记的空盘）——这是"常态显示"的字面意思，也是旧实现写错的地方
 * （它不带任何搜索条件也按标记默认剔一遍）。</p>
 *
 * <p>一个搜索条件只筛它自己那一维：名字搜索只比名字（无标记的盘照常参与，它也有名字），{@code #} 标记搜索
 * 只比标记（无标记的盘没东西可匹配，自然不出现）。开关打开 = 无标记的盘不受本次搜索约束，一律留下。</p>
 *
 * <p>名字侧走 {@link JechPinyin}：装了 JECH 时中文名可用拼音与首字母搜，没装就退回小写子串。标记侧先用原文
 * 比，再用标记的可读名比——玩家搜的是他看见的那个名字。</p>
 */
public final class DiskEntryFilter {

    /**
     * 解析后的搜索条件。
     *
     * @param needle       比较用的针（已小写；标记搜索时已去掉前导 {@code #}）
     * @param markSearch   这一针是标记搜索（输入以 {@code #} 开头）
     * @param preferOrder  写了「按列表顺序」修饰词（{@code @order} / {@code @顺位}）——这是**写入策略**，
     *                     不是筛选条件，{@link #isActive()} 不看它。**默认按剩余空间挑**：同组的盘谁还剩得多
     *                     先写谁（大容量盘优先填）；写了它才改成沿列表顺序把一张填满再下一张。
     */
    public record Query(String needle, boolean markSearch, boolean preferOrder) {

        public static Query of(@Nullable String raw) {
            // 输入先 strip，免得一串空格被当成搜索条件把列表清空。
            var text = raw == null ? "" : raw.strip();
            // 剥掉修饰词前后不一样 = 玩家写了修饰词（两个策略词都剥，所以不能拿它判是哪一个）。
            var stripped = stripModifiers(text);
            // 策略词在末尾（含 `$`），find 即匹配末尾。
            var preferOrder = ORDER_MODIFIER.matcher(text).find();
            if (stripped.isEmpty()) {
                return new Query("", false, preferOrder);
            }
            var needle = stripped.toLowerCase(Locale.ROOT);
            var markSearch = needle.startsWith("#");
            // `#` 之后也 strip：玩家习惯输入 `# 合成`，多一个空格不该把结果清空（标记本身也存不下首尾空格）。
            return new Query(markSearch ? needle.substring(1).strip() : needle, markSearch, preferOrder);
        }

        /** 有没有搜索条件。只有空白、或者只有规则本身，都不算。 */
        public boolean isActive() {
            return markSearch || !needle.isEmpty();
        }
    }

    private DiskEntryFilter() {
    }

    /**
     * 末尾的写入策略修饰词，如 {@code #合成 @order}、{@code @顺位}。**默认按剩余空间挑**，
     * 写它才改成沿列表顺序把一张填满再下一张。
     *
     * <p>只从末尾剥，不是按空格切 token：标记与盘名都可能含空格，切开会把 {@code #铁 厂} 这种搜索拆坏。
     * 剥剩下的部分照旧走名称/标记两维，所以修饰词与筛选是正交的两件事——它只影响“写进哪张盘”，
     * 不影响“列表里剩哪几张”。</p>
     *
     * <p>认不出的 {@code @xxx} 不动它，照旧当普通搜索文本（静默，不报错也不提示）。</p>
     */
    private static final Pattern ORDER_MODIFIER = Pattern.compile("(?i)\\s*@(?:order|顺位|顺序)\\s*$");

    /**
     * 剥掉末尾的修饰词后剩下的搜索原文。
     *
     * <p>打标组合技也要用它（Shift+右键把搜索栏文本写进磁盘标记）：修饰词是写入策略，
     * 不该被写进标记里。</p>
     */
    public static String stripModifiers(@Nullable String raw) {
        var out = raw == null ? "" : raw;
        String previous;
        do {
            previous = out;
            out = ORDER_MODIFIER.matcher(out).replaceFirst("");
        } while (!out.equals(previous));
        return out.strip();
    }

    /**
     * 这张盘在当前条件下要不要留在列表里。
     *
     * @param showUnmarked 无标记磁盘的显示开关：打开时无标记的盘不受标记搜索约束
     */
    public static boolean keep(DiskEntry entry, Query query, boolean showUnmarked) {
        if (!query.isActive()) {
            return true;
        }
        if (!hasMark(entry)) {
            // 无标记：开关打开时一律留下；常态下仅在标记搜索里被筛掉（它没有标记可匹配）。
            return showUnmarked || !query.markSearch();
        }
        return matchesSearch(entry, query.needle(), query.markSearch());
    }

    /** 这张盘有没有标记——标记就是它属于哪个配方类型的记录。 */
    public static boolean hasMark(DiskEntry entry) {
        var mark = entry.stack().get(AEPatternRegistries.DISK_PREFIX.get());
        return mark != null && !mark.isEmpty();
    }

    /** 磁盘是否匹配当前搜索：{@code markSearch} 时比标记（原文或可读名），否则比显示名。 */
    private static boolean matchesSearch(DiskEntry entry, String needle, boolean markSearch) {
        if (markSearch) {
            return matchesMark(entry, needle);
        }
        // 走 JechPinyin：装了 JECH 时中文名可用拼音/首字母搜，没装就是小写子串（见那个类）。
        return JechPinyin.contains(entry.displayName(), needle);
    }

    /** Whether {@code entry} carries a mark matching {@code needle} (already lower-cased). */
    private static boolean matchesMark(DiskEntry entry, String needle) {
        var raw = entry.stack().get(AEPatternRegistries.DISK_PREFIX.get());
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        if (raw.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        var label = PatternDiskMarks.displayName(entry.stack());
        return label != null && JechPinyin.contains(label.getString(), needle);
    }

    /** The search term that selects disks carrying {@code mark}: the {@code #} marker plus its label. */
    public static String markSearchTerm(String mark) {
        var label = PatternDiskMarks.displayName(mark);
        return "#" + (label == null ? mark : label.getString());
    }
}
