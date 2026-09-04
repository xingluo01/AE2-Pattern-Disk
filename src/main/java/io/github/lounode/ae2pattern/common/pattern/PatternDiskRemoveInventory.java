package io.github.lounode.ae2pattern.common.pattern;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.item.ItemStack;

import appeng.api.inventories.InternalInventory;

import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.AEPatternRegistries;

/**
 * Writable {@link InternalInventory} view over the encoded patterns stored across all inserted pattern
 * disks, exposed to AE2 pattern access terminals (PAT) as the provider's terminal inventory.
 *
 * <p>Pattern rows may only be <em>taken</em>, never written: any real extraction first draws one blank
 * pattern from the attached ME network (via {@link BlankPatternSink}) and is refused when the network
 * holds none; simulated operations ({@code simulate=true}) never mutate anything.</p>
 *
 * <p>AE2's PAT swap protocol (carried-item click on a populated row) performs
 * {@code setItemDirect(EMPTY)} then, when the carried item cannot be placed, a restore write-back of
 * the original row stack. Because the two-step take and the swap's first step are indistinguishable
 * here, an extraction is executed immediately (disk pattern physically removed, row emptied in place)
 * while the original stack is cached in {@link #lastRemoved}; the swap's restore write-back then
 * re-inserts the original pattern at its original position and returns the drawn blank pattern, so the
 * swap leaves the disk completely unchanged. Non-restore writes (anything not matching a row this view
 * just took) stay rejected — the disk accepts no pattern writes from a terminal.</p>
 *
 * <p>Row count is frozen per open PAT session (AE2 keeps a fixed slot count per session, so shrinking
 * would desync the open terminal). Removed rows are therefore emptied <em>in place</em>; the real disk
 * contents re-flow, and the next terminal view (a fresh instance re-scanning the disks) shows the
 * compacted layout.</p>
 *
 * @param diskInventory the provider's disk slot inventory; only {@link PatternDiskItem} slots count
 * @param blankPatternSink draws/returns one blank pattern from the ME network; {@code null} refuses extraction
 * @param onChange       invoked after a real mutation so the provider can persist and rebuild the view
 */
public class PatternDiskRemoveInventory implements InternalInventory {

    /** Draws {@code count} blank patterns from the attached ME network, all-or-nothing. */
    @FunctionalInterface
    public interface BlankPatternSink {
        boolean drawBlankPatterns(int count);

        /**
         * Read-only check: whether the attached ME network currently holds at least {@code count}
         * blank patterns. Defaults to {@code true} (compatible with sinks without a pre-check).
         */
        default boolean hasBlankPatterns(int count) {
            return true;
        }

        /**
         * Returns {@code count} blank patterns to the attached ME network (undo of
         * {@link #drawBlankPatterns}). Called when a swap restore re-inserts a just-taken pattern.
         * Defaults to a no-op for sinks without a restore path.
         */
        default boolean returnBlankPatterns(int count) {
            return true;
        }
    }

    /**
     * Rows this view recently took (blank cost drawn, pattern physically removed, row emptied): the
     * original stack plus its flat terminal index so an AE2 swap restore can re-insert it exactly at
     * its former position. Entries whose restore never arrives are harmless (a real take) and are
     * discarded on the next view rebuild.
     */
    private final Map<DiskRef, RemovedRow> lastRemoved = new HashMap<>();

    /**
     * Flat terminal index -> (disk slot, in-disk index), or {@code null} for a row emptied within the
     * current PAT session (removed pattern whose slot is kept so the open terminal's row count stays
     * stable). Rebuilt compactly by {@link #rebuild()} whenever a fresh terminal view is created.
     */
    private DiskRef[] refs = new DiskRef[0];

    private final InternalInventory diskInventory;
    private final BlankPatternSink blankPatternSink;
    private final Runnable onChange;

    public PatternDiskRemoveInventory(InternalInventory diskInventory, BlankPatternSink blankPatternSink,
            Runnable onChange) {
        this.diskInventory = diskInventory;
        this.blankPatternSink = blankPatternSink;
        this.onChange = onChange;
        rebuild();
    }

    /** Re-scans the disk slots into a compacted, hole-free row mapping (fresh view / rebuild). */
    public void rebuild() {
        var list = new ArrayList<DiskRef>();
        for (int slot = 0; slot < diskInventory.size(); slot++) {
            var stack = diskInventory.getStackInSlot(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem disk)) {
                continue;
            }
            var contents = disk.contents(stack);
            for (int idx = 0; idx < contents.patterns().size(); idx++) {
                list.add(new DiskRef(slot, idx));
            }
        }
        refs = list.toArray(new DiskRef[0]);
        lastRemoved.clear();
    }

    @Override
    public int size() {
        return refs.length;
    }

    @Override
    public ItemStack getStackInSlot(int index) {
        var ref = refAt(index);
        if (ref == null) {
            return ItemStack.EMPTY;
        }
        // Return a copy so callers (AE2 MOVE_REGION etc.) can never mutate disk contents in place.
        return getPatternAt(ref).copy();
    }

    /**
     * Clearing a slot is the terminal's extraction sink: the source disk pattern is removed (blank
     * pattern drawn first to materialise it) and the row is emptied in place.
     */
    @Override
    public void setItemDirect(int index, ItemStack stack) {
        var ref = refAt(index);
        if (ref == null) {
            return;
        }
        setItemDirectImpl(ref, stack);
    }

    @Override
    public ItemStack extractItem(int index, int amount, boolean simulate) {
        var ref = refAt(index);
        if (ref == null) {
            return ItemStack.EMPTY;
        }
        return extractItemImpl(ref, amount, simulate);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return false; // disk-backed provider does not accept pattern writes from a terminal
    }

    private DiskRef refAt(int index) {
        if (index < 0 || index >= refs.length) {
            return null;
        }
        return refs[index]; // may be null for a session-emptied row
    }

    private ItemStack getPatternAt(DiskRef ref) {
        var stack = diskInventory.getStackInSlot(ref.diskSlot);
        if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem disk)) {
            return ItemStack.EMPTY;
        }
        var patterns = disk.contents(stack).patterns();
        return ref.patternIndex < patterns.size() ? patterns.get(ref.patternIndex) : ItemStack.EMPTY;
    }

    private void setItemDirectImpl(DiskRef ref, ItemStack stack) {
        // 磁盘样板槽仅可取出，不可放入。非空写入只能是 AE2 swap 的恢复写回：若该行刚被本视图取出
        // （lastRemoved 命中），把原样板原位插回并返还空白——swap 整体无效果；其余非空写入（如
        // 恶意携带物、非恢复写回）一律拒绝，磁盘不接受终端写入。
        if (!stack.isEmpty()) {
            if (restoreLastRemoval(ref)) {
                return; // swap 回滚成功：磁盘与视图均恢复原状
            }
            return; // 非恢复写回：拒绝
        }
        // EMPTY 清槽 = 取走：空行（占位/无样板）直接早退（防双击/陈旧行白扣）。
        if (getPatternAt(ref).isEmpty()) {
            return;
        }
        if (blankPatternSink == null || !blankPatternSink.drawBlankPatterns(1)) {
            return;
        }
        // 缓存原样板供 swap 恢复，然后立即真删：磁盘内容移除（退行补位），视图行占位置空
        // （PAT 会话内行数冻结）。无恢复写回到达 = 一次真实的取出。
        removePatternAt(ref);
    }

    /**
     * Tries to roll back the most recent take of {@code ref} (AE2 swap restore): re-inserts the cached
     * original pattern at its original position, returns the drawn blank pattern and re-links the row.
     * Returns true when a matching take existed and was rolled back.
     */
    private boolean restoreLastRemoval(DiskRef ref) {
        var removed = lastRemoved.get(ref);
        if (removed == null || removed.stack().isEmpty()) {
            return false;
        }
        var diskStack = diskInventory.getStackInSlot(ref.diskSlot);
        if (diskStack.isEmpty() || !(diskStack.getItem() instanceof PatternDiskItem disk)) {
            return false;
        }
        var contents = disk.contents(diskStack);
        // 恢复的样板来自该盘：类型必为磁盘锁定类型；磁盘若因删空而失去类型则退回按样板本身推断。
        String patternType = contents.type() != null ? contents.type() : inferTypeFromPattern(removed.stack());
        if (patternType == null) {
            return false;
        }
        var restored = contents.insert(ref.patternIndex, removed.stack(), patternType);
        if (restored == null) {
            return false; // 恢复失败（满/类型异常）：保持已删状态，不强行写回
        }
        diskStack.set(AEPatternRegistries.DISK_CONTENTS.get(), restored);
        diskInventory.setItemDirect(ref.diskSlot, diskStack);
        // 精确填回该行原 flat 位置（磁盘已原位恢复，其它行未动）。
        if (removed.flatIndex() >= 0 && removed.flatIndex() < refs.length && refs[removed.flatIndex()] == null) {
            refs[removed.flatIndex()] = ref;
        }
        lastRemoved.remove(ref);
        if (blankPatternSink != null) {
            blankPatternSink.returnBlankPatterns(1);
        }
        onChange.run();
        return true;
    }

    /** 从已编码样板物品本身推断磁盘类型键（ae2:crafting_pattern 等），无需 level。 */
    private static String inferTypeFromPattern(ItemStack stack) {
        var itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return itemId == null ? null : itemId.toString();
    }

    /**
     * Extraction with the terminal's flat index resolved to a concrete disk ref. Keeps the guard
     * semantics (blank-pattern cost, all-or-nothing).
     */
    private ItemStack extractItemImpl(DiskRef ref, int amount, boolean simulate) {
        var stack = getPatternAt(ref);
        if (stack.isEmpty() || amount <= 0) {
            return ItemStack.EMPTY;
        }
        // 磁盘样板按单张存储（count=1）；对异常 count>1 的数据拒绝部分提取，避免整行误删。
        if (stack.getCount() != 1) {
            return ItemStack.EMPTY;
        }
        int toTake = Math.min(amount, stack.getCount());
        var result = stack.copy();
        result.setCount(toTake);
        if (simulate) {
            return result;
        }
        if (blankPatternSink == null || !blankPatternSink.drawBlankPatterns(toTake)) {
            return ItemStack.EMPTY;
        }
        // SPLIT/半取路径无 swap 恢复写回；removePatternAt 仍会缓存 lastRemoved（无害残留，
        // 该行已占位置空、rebuild 时清理），磁盘即刻真删。
        removePatternAt(ref);
        return result;
    }

    private void removePatternAt(DiskRef ref) {
        var stack = diskInventory.getStackInSlot(ref.diskSlot);
        if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem disk)) {
            return;
        }
        var contents = disk.contents(stack);
        if (ref.patternIndex >= contents.patterns().size()) {
            return; // 越界保护：外部已改动该盘布局
        }
        var original = contents.patterns().get(ref.patternIndex).copy();
        var next = contents.remove(ref.patternIndex);
        stack.set(AEPatternRegistries.DISK_CONTENTS.get(), next);
        diskInventory.setItemDirect(ref.diskSlot, stack);
        // 本行置空（占位），保持 PAT 会话内行数冻结：AE2 PAT 固定每个打开会话的行数，refs 收缩
        // 会使已打开终端与服务端失同步（末尾残留幽灵行）。磁盘内容已真删并自动退行补位，
        // 下一次重建视图（重开 PAT / 新会话 rebuild 扫盘）即呈现紧凑补位布局。
        int flat = markRowEmptied(ref);
        if (flat >= 0) {
            // 记录原样板与原位置，供 AE2 swap 恢复原位插回。真实取走（无恢复写回到达）时该记录
            // 是仅占内存的无害残留，rebuild（新建视图）时统一清理。
            lastRemoved.put(ref, new RemovedRow(original, flat));
        }
        onChange.run();
    }

    /** Marks the terminal row holding {@code ref} as emptied without shrinking the row count.
     * Returns the row's flat index, or -1 when the row was not found. */
    private int markRowEmptied(DiskRef ref) {
        for (int i = 0; i < refs.length; i++) {
            if (ref.equals(refs[i])) {
                refs[i] = null;
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns a single-slot guard wrapper for the terminal's take-path (PAT calls this via
     * {@code getSlotInv(slot)}). {@link #getStackInSlot()} re-checks blank-pattern availability before
     * handing out the pattern sample, so the AE2 PAT two-step take (getStackInSlot → setItemDirect) is
     * aborted early when the network holds no blank pattern — no item copy is emitted and the disk is
     * left untouched. The display path (direct {@link #getStackInSlot(int)}) is unaffected.
     */
    @Override
    public InternalInventory getSlotInv(int index) {
        var ref = refAt(index);
        if (ref == null) {
            return appeng.api.inventories.InternalInventory.empty();
        }
        return new SingleSlotGuard(ref);
    }

    /**
     * Single-slot guard for the PAT take-path: pre-checks blank-pattern availability before handing
     * out the pattern, and delegates mutation to the parent inventory's own guards.
     */
    private final class SingleSlotGuard implements InternalInventory {
        private final DiskRef ref;

        private SingleSlotGuard(DiskRef ref) {
            this.ref = ref;
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int index) {
            if (index != 0) {
                return ItemStack.EMPTY;
            }
            // 取走路径预检：网络无空白样板时，不发物（PAT 服务端 setCarried 到空栈后自然短路）
            if (!blankPatternSink.hasBlankPatterns(1)) {
                return ItemStack.EMPTY;
            }
            // 返回副本，防止调用方 mutate 磁盘内容活引用。
            return PatternDiskRemoveInventory.this.getPatternAt(ref).copy();
        }

        @Override
        public void setItemDirect(int index, ItemStack stack) {
            if (index != 0) {
                return;
            }
            PatternDiskRemoveInventory.this.setItemDirectImpl(ref, stack);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false;
        }

        @Override
        public ItemStack extractItem(int index, int amount, boolean simulate) {
            if (index != 0) {
                return ItemStack.EMPTY;
            }
            return PatternDiskRemoveInventory.this.extractItemImpl(ref, amount, simulate);
        }
    }

    private record RemovedRow(ItemStack stack, int flatIndex) {
    }

    private record DiskRef(int diskSlot, int patternIndex) {
    }
}
