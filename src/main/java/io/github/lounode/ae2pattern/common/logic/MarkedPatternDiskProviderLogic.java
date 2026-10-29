package io.github.lounode.ae2pattern.common.logic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import appeng.api.networking.IManagedGridNode;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.util.inv.AppEngInternalInventory;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.api.IPatternDiskHost;
import io.github.lounode.ae2pattern.api.PatternDiskApi;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.menu.DiskMarkRules;
import io.github.lounode.ae2pattern.common.pattern.DiskSlotVersions;

/**
 * 标记式样板磁盘供应器：自己没有磁盘槽，把网格上带**任一选中标记**的磁盘里的样板镜像过来。
 *
 * <p>与普通的样板磁盘供应器共用镜像语义与三档设置，区别只有来源：样板不再取自本机磁盘，而是取自
 * {@link PatternDiskApi#diskHosts} 报上来的每一台宿主。因此它需要一条自己的变更判据——磁盘内容快照
 * 换成整张网格的视图（{@link DiskSlotVersions#ofGrid}），并在 tick 里定期重扫：别人的磁盘插拔不会通知
 * 到本机。</p>
 *
 * <p><b>选中标记可以不止一个</b>，一台机器可以同时镜像「合成」与「熔炼」两族磁盘。一个都没选时
 * 不暴露任何样板：一台没有磁盘、又没选标记的供应器若还去镜像，等于把网络里所有样板复制一遍。</p>
 */
public class MarkedPatternDiskProviderLogic extends PatternDiskProviderLogic {

    /** 选中标记列表的存档键。 */
    public static final String NBT_SELECTED_MARKS = "selectedMarks";

    /** 单选时期（0.9.x）的存档键，读档时迁移进列表。 */
    private static final String LEGACY_SELECTED_MARK = "selectedMark";

    /**
     * 镜像容量：按九张最大档磁盘估。真实上限由网格里带选中标记的磁盘总量决定，超过的部分会被
     * {@link #rewriteMirror} 截断并记一条日志。
     */
    public static final int MIRROR_SLOTS = 9 * 1024;

    /**
     * 网格重扫间隔（tick）。别人的磁盘变动没有事件可听，只能定期比对；版本比对只做引用/身份比较，
     * 每秒一次的成本可以忽略。
     */
    private static final int GRID_SCAN_INTERVAL = 20;

    /** 按选中先后保存；同一个归一值只留第一次选中的那种写法。 */
    private final Set<String> selectedMarks = new LinkedHashSet<>();

    private int gridScanCooldown;
    private List<Object> lastGridVersions;

    public MarkedPatternDiskProviderLogic(IManagedGridNode mainNode, PatternProviderLogicHost host,
            Supplier<AppEngInternalInventory> diskInventorySupplier) {
        super(mainNode, host, 0, diskInventorySupplier, MIRROR_SLOTS);
    }

    public Collection<String> getSelectedMarks() {
        return List.copyOf(selectedMarks);
    }

    public boolean hasSelectedMarks() {
        return !canonicalSelection().isEmpty();
    }

    /**
     * 点一个候选即加入选中集合；再点同一个（按归一值算同一个）即把它从集合里去掉。
     *
     * <p>集合里同一归一值只保留第一次选中的那种写法：{@code #mode:crafting} 与
     * {@code #minecraft:crafting} 是同一个格子，先点进去的那个决定了存档与内存卡里写的是哪个串。</p>
     */
    public void toggleMark(@Nullable String mark) {
        var canonical = canonicalMark(mark);
        if (canonical.isEmpty()) {
            return;
        }
        var same = new ArrayList<String>();
        for (var existing : selectedMarks) {
            if (canonicalMark(existing).equals(canonical)) {
                same.add(existing);
            }
        }
        if (same.isEmpty()) {
            selectedMarks.add(mark);
        } else {
            selectedMarks.removeAll(same);
        }
        saveChanges();
        refreshPatternsFromDisks(true);
    }

    public void clearSelectedMarks() {
        if (selectedMarks.isEmpty()) {
            return;
        }
        selectedMarks.clear();
        saveChanges();
        refreshPatternsFromDisks(true);
    }

    /**
     * 网格上出现过的全部标记，按归一 id 去重，保留每种标记第一次见到的原始写法。
     *
     * <p>这是「上传体系使用的标记列表」的服务端口径：候选只来自磁盘上真实存在的标记，不凭空造。</p>
     */
    public List<String> collectAvailableMarks() {
        var grid = mainNode.getGrid();
        if (grid == null) {
            return List.of();
        }
        Map<String, String> byCanonical = new LinkedHashMap<>();
        for (var host : PatternDiskApi.diskHosts(grid)) {
            var inventory = host.getDiskInventory();
            for (int i = 0; i < inventory.size(); i++) {
                var mark = markOf(inventory.getStackInSlot(i));
                if (mark == null) {
                    continue;
                }
                byCanonical.putIfAbsent(canonicalMark(mark), mark);
            }
        }
        return List.copyOf(byCanonical.values());
    }

    @Override
    public boolean refreshPatternsFromDisks(boolean force) {
        var grid = mainNode.getGrid();
        if (grid == null) {
            return false;
        }
        var hosts = PatternDiskApi.diskHosts(grid);
        var versions = DiskSlotVersions.ofGrid(hosts);
        if (!force && DiskSlotVersions.sameAs(lastGridVersions, versions)) {
            return false;
        }

        var patterns = new ArrayList<ItemStack>();
        var targets = canonicalSelection();
        if (!targets.isEmpty()) {
            for (var host : hosts) {
                collectPatterns(host, targets, patterns);
            }
        }

        rewriteMirror(patterns);
        updatePatterns();
        lastGridVersions = versions;
        return true;
    }

    @Override
    public void tickServer() {
        super.tickServer();
        if (--gridScanCooldown <= 0) {
            gridScanCooldown = GRID_SCAN_INTERVAL;
            refreshPatternsFromDisks(false);
        }
    }

    @Override
    public void writeToNBT(CompoundTag tag, HolderLookup.Provider registries) {
        super.writeToNBT(tag, registries);
        writeSelectedMarks(tag);
    }

    @Override
    public void readFromNBT(CompoundTag tag, HolderLookup.Provider registries) {
        super.readFromNBT(tag, registries);
        readSelectedMarks(tag);
        // 网格快照不落盘：读档后第一轮必须重建，否则会拿旧快照比出「没变」。
        lastGridVersions = null;
    }

    @Override
    protected void writeExtraSettings(CompoundTag tag) {
        writeSelectedMarks(tag);
    }

    @Override
    protected void readExtraSettings(CompoundTag tag) {
        readSelectedMarks(tag);
        lastGridVersions = null;
    }

    private void writeSelectedMarks(CompoundTag tag) {
        if (selectedMarks.isEmpty()) {
            return;
        }
        var list = new ListTag();
        for (var mark : selectedMarks) {
            list.add(StringTag.valueOf(mark));
        }
        tag.put(NBT_SELECTED_MARKS, list);
    }

    private void readSelectedMarks(CompoundTag tag) {
        selectedMarks.clear();
        if (tag.contains(NBT_SELECTED_MARKS, Tag.TAG_LIST)) {
            var list = tag.getList(NBT_SELECTED_MARKS, Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                var mark = list.getString(i);
                if (!mark.isEmpty()) {
                    selectedMarks.add(mark);
                }
            }
            return;
        }
        // 单选时期的存档：那一个标记就是当时的全部选择。
        var legacy = tag.getString(LEGACY_SELECTED_MARK);
        if (!legacy.isEmpty()) {
            selectedMarks.add(legacy);
        }
    }

    private Set<String> canonicalSelection() {
        var out = new HashSet<String>();
        for (var mark : selectedMarks) {
            out.add(canonicalMark(mark));
        }
        out.remove("");
        return out;
    }

    private void collectPatterns(IPatternDiskHost host, Set<String> targets, List<ItemStack> out) {
        var inventory = host.getDiskInventory();
        for (int i = 0; i < inventory.size(); i++) {
            var stack = inventory.getStackInSlot(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem disk)) {
                continue;
            }
            if (!targets.contains(canonicalMark(markOf(stack)))) {
                continue;
            }
            out.addAll(disk.contents(stack).patterns());
        }
    }

    private static @Nullable String markOf(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem)) {
            return null;
        }
        var mark = stack.get(AEPatternRegistries.DISK_PREFIX.get());
        return mark == null || mark.isEmpty() ? null : mark;
    }

    /**
     * 标记的归一值：认得出的标记归到样板类型，认不出的（玩家手写文本）保留原文。
     *
     * <p>与终端选盘同一套锚点（{@link DiskMarkRules#patternTypeForMark}），所以
     * {@code #mode:crafting} 的盘和 {@code #minecraft:crafting} 的盘会被同一个候选收进来。</p>
     */
    public static String canonicalMark(@Nullable String mark) {
        if (mark == null || mark.isEmpty()) {
            return "";
        }
        var type = DiskMarkRules.patternTypeForMark(mark);
        return type != null ? type : mark;
    }
}
