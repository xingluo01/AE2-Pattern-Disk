package io.github.lounode.ae2pattern.client.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import appeng.client.gui.widgets.AETextField;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.client.gui.DiskListPanel.DiskEntry;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.menu.pattern.DiskMarkRules;
import io.github.lounode.ae2pattern.common.menu.pattern.PatternDiskEncodingTermMenu;

/**
 * 编码终端的「磁盘列表」控件组：从服务端同步的磁盘列表构建条目、搜索/标记过滤、写盘顺位，
 * 以及列表的左键写盘 / 右键打标交互都收在这里。屏幕只保留 {@code protected} 转调给子类复用。
 *
 * <p>本类不持有屏幕，构造期只收窄依赖：菜单对象、磁盘列表面板、迷你搜索框，以及一个
 * {@code Supplier<String>} 回调（当前档位编出的样板类型 id，由 {@link EncodingTierWidgets} 提供）。
 * 详见 {@code docs/ENCODING_MODES.md} 第七节。</p>
 */
final class EncodingDiskListWidgets {

    private final PatternDiskEncodingTermMenu menu;
    private final DiskListPanel diskListPanel;
    private final AETextField miniSearchField;
    private final Supplier<String> currentPatternTypeId;

    /** 当前磁盘条目列表（含 serial，用于回调映射）。 */
    private final List<DiskEntry> diskEntries = new ArrayList<>();

    /** 已经填过的那次导入（菜单里的导入修订号）：搜索栏只在导入发生时填，见 updateBeforeRender。 */
    private int seenImportRevision;

    EncodingDiskListWidgets(PatternDiskEncodingTermMenu menu, DiskListPanel diskListPanel,
            AETextField miniSearchField, Supplier<String> currentPatternTypeId) {
        this.menu = menu;
        this.diskListPanel = diskListPanel;
        this.miniSearchField = miniSearchField;
        this.currentPatternTypeId = currentPatternTypeId;
        // 与菜单对齐，不假设「新屏幕一定配新菜单」：万一菜单是复用的，开屏第一帧就不该把旧导入填回去。
        this.seenImportRevision = menu.getCategoryImportRevision();
    }

    /** 每帧：导入引起的搜索栏自动填充，随后刷新磁盘条目。 */
    void updateBeforeRender() {
        // 刷新磁盘列表（过滤 PatternDiskItem + 搜索过滤）。
        // 搜索栏的自动填充只跟「导入配方」有关：JEI/EMI 配方页点「编写样板」那一刻在菜单里记一次修订号，
        // 这里跟着填。绑定标记、切换模式、放样板回流同样会改掉标记的值，但它们都不该动玩家正在用的搜索
        // 条件——判据是「发生了导入」，而不是「标记值变了」（后者曾把右键写标记也变成改写搜索框）。
        var importRevision = menu.getCategoryImportRevision();
        if (importRevision != seenImportRevision) {
            seenImportRevision = importRevision;
            // 用导入当场记下的类别，而不是此刻的「当前类别」：后者可能已被右键（光标上的工作方块）改掉，或被
            // 切模式清空。类别为空时不填——那会退回模式标记（#mode:...），不是导入者想要的筛选词。
            var imported = menu.getLastImportedCategory();
            if (imported != null && !imported.isEmpty()) {
                miniSearchField.setValue(DiskEntryFilter.markSearchTerm("#" + imported));
            }
        }

        updateDiskEntries();
    }

    // ---- 磁盘列表数据 --------------------------------------------------------

    /**
     * 从服务端同步的磁盘列表（供应器磁盘槽扫描结果）构建磁盘条目，应用配方前缀过滤与迷你搜索过滤，
     * 并传递给磁盘列表面板。
     */
    private void updateDiskEntries() {
        diskEntries.clear();

        // 磁盘来自服务端扫描的供应器磁盘槽（DiskListPayload 同步）
        for (var entry : menu.getDiskList()) {
            var stack = entry.stack();
            if (!(stack.getItem() instanceof PatternDiskItem disk)) continue;

            var contents = disk.contents(stack);
            diskEntries.add(new DiskEntry(
                    stack,
                    stack.getHoverName().getString(),
                    contents.used(),
                    contents.capacity(),
                    1,
                    entry.serial()));
        }

        // 搜索过滤：**没有搜索条件就什么都不剔除**（含无标记的空盘）——这是「常态显示」的字面意思，也是旧
        // 实现写错的地方（它不带任何搜索条件也按标记默认剔一遍）。
        //
        // 一个搜索条件只筛它自己那一维：名字搜索只比名字（无标记的盘照常参与，它也有名字），`#` 标记搜索
        // 只比标记（无标记的盘没东西可匹配，自然不出现）。开关打开 = 无标记的盘不受本次搜索约束，一律留下。
        // 输入先 strip，免得一串空格被当成搜索条件把列表清空。
        var query = diskSearchQuery();
        if (query.isActive()) {
            diskEntries.removeIf(d -> !DiskEntryFilter.keep(d, query, menu.isShowUnmarkedDisks()));
        }

        // 没有搜索条件时什么都不剔除（含无标记的空盘）——规则写在上面那段注释里。

        // 按显示名排序
        diskEntries.sort(Comparator.comparing(DiskEntry::displayName));

        // 传给面板
        diskListPanel.setDiskEntries(List.copyOf(diskEntries));

        // 编码按钮要不要直接落盘。这一步必须等过滤做完：玩家点按钮时看到的就是这份列表，早一帧算出来就可能
        // 把目标算成此刻已经看不到的那张盘。
        // ① 写了 `@order`/`@顺位`：按列表顺序把一张填满再下一张；
        // ② 其余：默认按剩余空间挑（大容量盘优先填）——**搜索只负责“剩哪几张”，不改变“写哪张”**。
        //    tierGroupByUsage 遍历的就是已过滤的 diskEntries，所以“筛出两张、在这两张里挑少的”天然成立。
        long[] autoDisks;
        if (query.preferOrder()) {
            autoDisks = capCandidates(diskEntries.stream().mapToLong(DiskListPanel.DiskEntry::serial).toArray());
        } else {
            autoDisks = tierGroupByUsage();
        }
        menu.setClientAutoDisks(autoDisks);
    }

    /**
     * 这张盘是不是「当前档位编出的样板」的候选目标：可能落盘，且判断只认盘自己的东西，不猜显示名。
     *
     * <p><b>判据只有一条：这张盘属于的那类样板 == 当前档位编出的那类。</b>而「属于哪类」两个来源有主次：</p>
     *
     * <ol>
     * <li>盘自己的标记（{@code DISK_PREFIX}）——玩家的组织意图，优先级高，按标记反查它代表哪类样板；</li>
     * <li>没打过标记时，用它锁定的类型——类型是数据事实，这里当默认值。</li>
     * </ol>
     *
     * <p><b>为什么锚点是样板类型而不是标记字面量</b>：标记是类型的一种写法（{@code #minecraft:crafting}
     * 或 {@code #mode:crafting} 都是「合成类」），两者在 {@code DiskMarkRules} 里已经可互转。拿标记字面量
     * 当锚点会掉进两个坑：玩家右键打标写的任意文本没有对应类型（得判无效），而高级/过载档没有专属标记
     * （于是锁了这两类类型、却没打过标记的盘就被排除——写盘侧它们盖的只是继承来的常规档标记）。以类型为锚点，
     * 两边都自然对上。</p>
     *
     * <p>判不出来的一律不算（否则每张空盘都会来抢）：空盘既没标记也没锁类型；玩家写了个认不出的标记
     * （如「铁厂」）；万象那个类型尚未接编码，反推不出档位。</p>
     */
    private boolean isInCurrentTierGroup(DiskEntry entry) {
        var type = tierTypeOf(entry);
        return type != null && type.equals(currentPatternTypeId.get());
    }

    /**
     * 这张盘归到哪一类样板：认得出的标记优先（玩家的组织意图），否则退回它锁定的类型。
     *
     * <p><b>退回而不是排除</b>：标记认不出（玩家自定义文本，或处理档导入的 `#<非规范类别>` 如
     * {@code #minecraft:smelting}）时，那张盘不能因此就进不了组——它的类型锁很可能正好能收当前样板。
     * 后果就是上面那句「盘明明对得上、点编写样板却没反应」。</p>
     *
     * <p>两个来源都拿不到时返回 null：空盘（既没标记也没锁类型）。反查链在
     * {@code DiskMarkRules.patternTypeForMark}：雕凿的固定字面量、{@code #mode:<模式>} 与
     * {@code #<配方类别>} 三种写法都认。</p>
     */
    @Nullable
    private String tierTypeOf(DiskEntry entry) {
        var raw = entry.stack().get(AEPatternRegistries.DISK_PREFIX.get());
        if (raw != null && !raw.isEmpty()) {
            var byMark = DiskMarkRules.patternTypeForMark(raw);
            // 认得出的标记优先（玩家/导入留下的组织意图）；**认不出的不屏蔽类型兜底**，否则下面这种盘就没法用了：
            // 导入一条熔炉配方会把 {@code #minecraft:smelting} 写进标记，而处理档没有规范类别（{@code
            // categoryForMode} 返回 null），反查不出类型——那张盘类型锁明明是处理样板、却进不了处理组，
            // 症状正是「盘明明对得上、点编写样板却没反应」。
            if (byMark != null) {
                return byMark;
            }
        }
        // 没标记，或标记认不出：用它锁定的类型——类型是数据事实，这里当默认值。
        return lockedTypeOf(entry);
    }

    /** 这张盘锁定的样板类型；它不是磁盘、或还是空盘（未锁定）时返回 null。 */
    @Nullable
    private static String lockedTypeOf(DiskEntry entry) {
        return entry.stack().getItem() instanceof PatternDiskItem disk
                ? disk.contents(entry.stack()).type()
                : null;
    }

    /**
     * 没搜索条件时的写盘候选：当前档位那一组盘，<b>按剩余空间降序</b>全部列出。
     *
     * <p>写进还剩得最多的那张（大容量盘优先填），而不是要求全网只剩一张：让玩家为了写一枚样板
     * 先去把其他盘搜掉、或点中某一张，是没有理由的负担。</p>
     *
     * <p><b>“逐张试”不在这里做</b>：列表交给菜单的 {@code transferToFirstWritable}，它按这个顺序逐张
     * 试，第一张能收的就收了。所以「剩余最多」只是候选顺序，不是唯一目标——写不进自然顺位到次多的
     * 那张，而「能不能写」由那里的 {@code PatternDiskItem.tryInsert} 说了算（容量 / 类型锁 / 主产物
     * 互斥），不在本方法里预判：判它得先有编好的样板，而这份候选是在编码之前算的。</p>
     *
     * <p><b>两个终端共用</b>：管理终端的写盘目标多半来自右键选中的那张盘，但在「没选中、也没搜索」
     * 那一格上它走的是本方法。</p>
     */
    long[] tierGroupByUsage() {
        var matched = new ArrayList<DiskEntry>();
        for (var entry : diskEntries) {
            if (isInCurrentTierGroup(entry)) {
                matched.add(entry);
            }
        }
        // 剩余空间降序：还剩得多的那张先写（大容量盘优先填）。平手时保持列表原序（显示名序）——
        // 稳定，不会每次刷新换一张盘。想改成沿列表顺序依次填满，写 {@code @order} 修饰词。
        matched.sort(Comparator.comparingInt((DiskEntry entry) -> entry.capacity() - entry.used()).reversed());
        var serials = new long[matched.size()];
        for (int i = 0; i < serials.length; i++) {
            serials[i] = matched.get(i).serial();
        }
        return capCandidates(serials);
    }

    // ---- 磁盘列表交互 --------------------------------------------------------

    /** 子类（管理终端）用：把「磁盘序列号」映射到本屏幕列表里的下标，好复用下面的点击交互。 */
    int indexOfDisk(long serial) {
        for (int i = 0; i < diskEntries.size(); i++) {
            if (diskEntries.get(i).serial() == serial) {
                return i;
            }
        }
        return -1;
    }

    /** 没有写盘目标时传给服务端的哨兵（空数组 = 这次编码不自动写盘）。 */
    private static final long[] NO_DISKS = new long[0];

    /**
     * 客户端动作的参数有 32767 字符的硬上限（AE2 的 {@code AEBaseMenu}），候选整表上传时超了会在
     * 点击处抛异常。顺位到 {@link #MAX_AUTO_DISKS} 张早已超出实用范围，更长的候选只会变成一条超长消息。
     */
    private static final int MAX_AUTO_DISKS = 256;

    /** 把候选截到可发送的长度内。 */
    static long[] capCandidates(long[] serials) {
        return serials.length <= MAX_AUTO_DISKS ? serials : java.util.Arrays.copyOf(serials, MAX_AUTO_DISKS);
    }

    /** 磁盘搜索框里有没有内容；编码与管理的自动写盘都按它决定要不要顺位。 */
    /**
     * 当前搜索栏的解析结果。筛选与写入策略共用这一份，避免两处各解析一次而分叉。
     *
     * <p>它比 {@link #isDiskSearchActive()} 多分出一层：那边只问「有没有内容」，这边还分出
     * 「{@code @order} 一类修饰词」（见 {@link DiskEntryFilter#stripModifiers}）。修饰词只改写入策略，
     * 不是筛选条件——所以 {@code DiskEntryFilter.Query.isActive()} 不看它。</p>
     */
    DiskEntryFilter.Query diskSearchQuery() {
        return DiskEntryFilter.Query.of(diskListPanel.getSearchText());
    }

    boolean isDiskSearchActive() {
        var search = miniSearchField.getValue();
        return search != null && !search.isBlank();
    }

    /** 子类用：过滤后列表里第 {@code index} 张盘的条目；越界返回 {@code null}。 */
    @Nullable
    DiskEntry diskEntryAt(int index) {
        return index >= 0 && index < diskEntries.size() ? diskEntries.get(index) : null;
    }

    void onDiskClick(int index) {
        var entry = getDiskEntryAt(index);
        if (entry != null) {
            menu.transferToDisk(entry.serial());
        }
    }

    /**
     * 用搜索栏里写的那个标记覆写这张盘的标记。它不依赖“当前导入的配方类型”，所以玩家可以先搜出某类
     * 磁盘，再把同一个标记标到别的盘上。搜索栏为空时反过来清掉这张盘的标记。
     *
     * <p>手势：编码终端的磁盘列表右键；管理终端磁盘格 Shift+右键。</p>
     */
    void overwriteMarkFromSearch(int index) {
        var entry = getDiskEntryAt(index);
        if (entry == null) {
            return;
        }
        // 搜索栏为空 = 没有标记可打，那就把这张盘已有的标记去掉。
        // 先剥掉 `@order` 一类修饰词：它是写入策略，不该被写进磁盘标记。
        var search = DiskEntryFilter.stripModifiers(diskListPanel.getSearchText());
        menu.setPendingMarkText(search);
        menu.bindSearchMark(entry.serial());
    }

    @Nullable
    private DiskEntry getDiskEntryAt(int index) {
        if (index < 0 || index >= diskEntries.size()) {
            return null;
        }
        return diskEntries.get(index);
    }
}
