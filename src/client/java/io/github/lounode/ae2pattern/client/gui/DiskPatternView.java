package io.github.lounode.ae2pattern.client.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import appeng.api.config.SortDir;
import appeng.api.config.SortOrder;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.crafting.pattern.EncodedPatternItem;

import io.github.lounode.ae2pattern.client.integration.JechPinyin;
import io.github.lounode.ae2pattern.client.sort.NaturalOrder;
import io.github.lounode.ae2pattern.client.sort.NaturalSort;
import io.github.lounode.ae2pattern.client.sort.NumericSeries;
import io.github.lounode.ae2pattern.client.sort.SortTiers;
import io.github.lounode.ae2pattern.common.menu.DiskEncodingLogic.SearchScope;

/**
 * 一张盘里样板的显示顺序：搜索筛掉的先出局，剩下的按终端的排序档位排，最后交回存储序号。
 *
 * <p>盘里的样板原来按写入次序铺，而写入次序对玩家没什么意义（按名字找一张合成表要一行行扫）。这里排一遍：
 * 名称档按格子显示的那个名字、mod 档先按它的 mod 分组；「数量」档没有可比的东西（一枚样板就是一件），保持
 * 原顺序。附加排序打开时名字按数值比，于是 1k/16k/256k 与 4/16/64 排得对。</p>
 *
 * <p><b>交出的是序号，不是排好的样板。</b>格子画什么、点下去取哪一枚，都得回到服务端的存储序号上（取件按
 * 序号说话），排样板列表会把那层对应关系拆散。所以返回值是「显示位次 → 存储序号」的数组，满盘 1024 张时分
 * 配与比较都只走 int。</p>
 *
 * <p><b>缓存拿内容列表的引用做键</b>：服务端每次推送内容都会换一个新列表对象，引用一变就说明该重算了。改成
 * 按值比较会让大列表每帧全量排序——这是这套缓存存在的全部理由。</p>
 *
 * <p>排序口径由外部给（{@code Supplier}）：终端的排序档位住在菜单/服务端设置里，本类只读它。</p>
 */
public final class DiskPatternView {

    private static final int[] NO_ORDER = new int[0];

    private final Supplier<SortOrder> sortBy;
    private final Supplier<SortDir> sortDir;
    private final BooleanSupplier naturalSort;

    /** 每张盘上一次的排序结果，键是盘序列号。 */
    private final Map<Long, Entry> cache = new HashMap<>();

    /** 一次排序的结果；内容或排序口径一变就重算（拿内容列表的引用比，服务端每次推送会换一个新列表）。 */
    private record Entry(List<ItemStack> contents, SortOrder order, SortDir dir, boolean natural,
            String needle, SearchScope searchScope, int[] storageIndexes) {

        boolean stillMatches(List<ItemStack> contents, SortOrder order, SortDir dir, boolean natural,
                String needle, SearchScope searchScope) {
            return this.contents == contents && this.order == order && this.dir == dir && this.natural == natural
                    && this.needle.equals(needle) && this.searchScope == searchScope;
        }
    }

    public DiskPatternView(Supplier<SortOrder> sortBy, Supplier<SortDir> sortDir, BooleanSupplier naturalSort) {
        this.sortBy = sortBy;
        this.sortDir = sortDir;
        this.naturalSort = naturalSort;
    }

    /**
     * 盘内样板的显示顺序（显示位次 → 存储序号）；内容与排序口径没变就直接用上次算的。
     *
     * @param contents   盘内样板（服务端推送的那份；{@code null} 表示内容尚未下发）
     * @param searchText 内容搜索栏的文本；空串/空白表示不筛
     */
    public int[] orderOf(long serial, @Nullable List<ItemStack> contents, @Nullable String searchText,
            SearchScope searchScope) {
        if (contents == null || contents.isEmpty()) {
            return NO_ORDER;
        }

        var order = sortBy.get();
        var dir = sortDir.get();
        boolean natural = naturalSort.getAsBoolean();
        var needle = searchText == null ? "" : searchText.strip().toLowerCase(Locale.ROOT);
        var cached = cache.get(serial);
        if (cached != null && cached.stillMatches(contents, order, dir, natural, needle, searchScope)) {
            return cached.storageIndexes();
        }

        // 内容搜索：不匹配的样板不进显示序（行数、命中、取件因此都自动按筛选后的结果走）。
        var matched = new ArrayList<Integer>(contents.size());
        for (int i = 0; i < contents.size(); i++) {
            if (matches(contents.get(i), needle, searchScope)) {
                matched.add(i);
            }
        }

        var indexes = matched.toArray(new Integer[0]);
        var comparator = comparatorOf(order, dir, natural);
        // 排序号而不是排堆：n log n，且不丢"显示位次 ↔ 存储序号"的对应（满盘 1024 张也不会在帧里抖）。
        Arrays.sort(indexes, (left, right) -> comparator.compare(contents.get(left), contents.get(right)));

        var storageIndexes = new int[indexes.length];
        for (int i = 0; i < indexes.length; i++) {
            storageIndexes[i] = indexes[i];
        }

        cache.put(serial, new Entry(contents, order, dir, natural, needle, searchScope, storageIndexes));
        return storageIndexes;
    }

    /** 丢掉已经不在盘清单里的盘（被取走、被搜索筛掉的盘不必再留着那份旧内容列表）。 */
    public void retainOnly(Collection<Long> serials) {
        cache.keySet().retainAll(serials);
    }

    /**
     * 样板是否匹配顶部内容搜索栏的文本；范围决定只看产物、只看输入，还是两边都看。
     *
     * <p>产物侧直接用格子显示的那个栈（与排序同一口径，流体等非物品产出也覆盖）；输入侧走 AE2 的样板解码，
     * 解不出的坏样板只在产物侧参与匹配，不让它把整帧弄崩。</p>
     *
     * <p>匹配本身走 {@link JechPinyin}：装了 JECH 时中文名也能按拼音与首字母搜，没装就退回小写子串——
     * 与编码终端的磁盘搜索同一口径。两侧都走它，所以一个搜索框不会只看中文产物不认中文输入。</p>
     */
    private boolean matches(ItemStack pattern, String needle, SearchScope scope) {
        if (needle.isEmpty()) {
            return true;
        }
        var onlyInput = scope == SearchScope.INPUT;
        var onlyOutput = scope == SearchScope.OUTPUT;
        if (!onlyInput && JechPinyin.contains(displayedName(pattern), needle)) {
            return true;
        }
        if (onlyOutput) {
            return false;
        }
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        var details = PatternDetailsHelper.decodePattern(pattern, level);
        if (details == null) {
            return false;
        }
        for (var input : details.getInputs()) {
            for (var possible : input.getPossibleInputs()) {
                if (possible != null && possible.what() != null
                        && JechPinyin.contains(possible.what().getDisplayName().getString(), needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 盘内样板的排序口径。
     *
     * <p>比的是格子里显示的那个名字（样板的主产物），不是样板本体：玩家在格子看到的是产物，按产物排才找得到
     * 东西。mod 档同理，比的是产物所属的 mod。名字档固定用字面序；「数值排序」开关只作用于按 mod 档
     * 的组内比较（见 {@link NumericSeries}）。</p>
     */
    private Comparator<ItemStack> comparatorOf(SortOrder order, SortDir dir, boolean natural) {
        return switch (order) {
            case MOD -> byModComparator(dir, natural);
            case NAME -> Comparator.comparing(DiskPatternView::displayedName, NaturalSort.names(dir, false));
            // 数量档：一枚样板就是一件，没有可比的东西，保持盘里的原顺序。
            case AMOUNT -> (left, right) -> 0;
        };
    }

    /**
     * 按 mod 排：附加排序打开时是四层——mod → 阶层（见 {@link SortTiers}）→ 去掉数字后的文本 → 名字的
     * 数值序（见 {@link NumericSeries}，只作用于配置里正则命中的名字）；关掉时退回 AE2 原本的两层
     * （mod → 名字字面序）。第三层才是数大小：先分组再排数，同一系列（只是容量不同）才会相邻，不会出现
     * 「1k存储元件、1k存储组件、4k存储元件」这种把同系列拆散的次序。
     */
    private Comparator<ItemStack> byModComparator(SortDir dir, boolean additional) {
        Comparator<ItemStack> ascending =
                Comparator.comparing(DiskPatternView::displayedModId, String::compareToIgnoreCase);
        if (additional) {
            // 与物品网格同一套口径：阶层层夹在 mod 与文本分组之间，命中的排在未命中的前面。四层都按
            // 格子实际显示的那个栈算（见 displayedItem），否则阶层会按样板本体去查，一个也命中不了。
            var tiers = SortTiers.rankerForItems();
            ascending = ascending
                    .thenComparingInt(pattern -> tiers.applyAsInt(displayedItem(pattern)))
                    .thenComparing(stack -> NaturalOrder.template(displayedName(stack)),
                            String::compareToIgnoreCase)
                    .thenComparing(DiskPatternView::displayedName, NumericSeries.strings());
        } else {
            ascending = ascending.thenComparing(DiskPatternView::displayedName, String::compareToIgnoreCase);
        }
        return dir == SortDir.DESCENDING ? ascending.reversed() : ascending;
    }

    /**
     * 样板在终端里该显示的主产物；不是 AE2 样板物品、或取不到主产物时返回空堆。
     *
     * <p>直接用 AE2 的 {@code EncodedPatternItem#getOutput}：它对非物品产出（流体等）会包一层伪物品，
     * 所以任何类型的产物都能直接显示；它也自带缓存，逐帧调用不会反复解码。该方法在"解出的样板报告零产出"
     * 时会在内部越界，而渲染路径不能因此炸掉整帧，所以在边界收口一次。</p>
     */
    public static ItemStack outputOf(ItemStack pattern) {
        if (!(pattern.getItem() instanceof EncodedPatternItem encodedPattern)) {
            return ItemStack.EMPTY;
        }
        try {
            return encodedPattern.getOutput(pattern);
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }

    /** 格子实际显示的那个栈：能解出主产物就用产物，解不出就用样板本体。四层排序口径都以它为准。 */
    public static ItemStack displayedItem(ItemStack pattern) {
        var output = outputOf(pattern);
        return output.isEmpty() ? pattern : output;
    }

    /** 格子里的名字。 */
    public static String displayedName(ItemStack pattern) {
        return displayedItem(pattern).getHoverName().getString();
    }

    /** 格子所属的 mod：同上，取产物那一侧。 */
    public static String displayedModId(ItemStack pattern) {
        return NaturalSort.modIdOf(displayedItem(pattern));
    }
}
