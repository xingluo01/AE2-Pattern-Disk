package io.github.lounode.ae2pattern.common.pattern;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.inventories.InternalInventory;

import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.integration.extendedae_plus.ExtendedAEPlusCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writable {@link InternalInventory} view over the encoded patterns stored across all inserted pattern
 * disks, exposed to AE2 pattern access terminals (PAT) as the provider's terminal inventory.
 *
 * <p>Pattern rows are only ever <em>taken</em> from a terminal: any real extraction first draws one blank
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
 * just took) stay rejected — with one exception, the upload write described below.</p>
 *
 * <p>Row count is frozen per open PAT session (AE2 keeps a fixed slot count per session, so shrinking
 * would desync the open terminal). Removed rows are therefore emptied <em>in place</em>; the real disk
 * contents re-flow, and the next terminal view (a fresh instance re-scanning the disks) shows the
 * compacted layout.</p>
 *
 * <p>The one write this view accepts is {@link #insertItem}, and only while ExtendedAE Plus is
 * installed (see {@link ExtendedAEPlusCompat}): that mod uploads a pattern to a provider by looking for
 * an empty row and writing into it, and for this provider the disks are the only place such a write can
 * land.</p>
 *
 * @param diskInventory the provider's disk slot inventory; only {@link PatternDiskItem} slots count
 * @param blankPatternSink draws/returns one blank pattern from the ME network; {@code null} refuses extraction
 * @param onChange       invoked after a real mutation so the provider can persist and rebuild the view
 * @param levelSupplier  resolves the level lazily, for decoding a pattern before it is written to a disk
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

    private static final Logger LOGGER =
            LoggerFactory.getLogger("ae2_pattern_disk.pattern.PatternDiskRemoveInventory");

    private final InternalInventory diskInventory;
    private final BlankPatternSink blankPatternSink;
    private final Runnable onChange;
    private final Supplier<Level> levelSupplier;

    public PatternDiskRemoveInventory(InternalInventory diskInventory, BlankPatternSink blankPatternSink,
            Runnable onChange, Supplier<Level> levelSupplier) {
        this.diskInventory = diskInventory;
        this.blankPatternSink = blankPatternSink;
        this.onChange = onChange;
        this.levelSupplier = levelSupplier;
        rebuild();
    }

    /** Re-scans the disk slots into a compacted, hole-free row mapping (called by the constructor). */
    private void rebuild() {
        var list = new ArrayList<DiskRef>();
        int freeCapacity = 0;
        for (int slot = 0; slot < diskInventory.size(); slot++) {
            var stack = diskInventory.getStackInSlot(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem disk)) {
                continue;
            }
            var contents = disk.contents(stack);
            for (int idx = 0; idx < contents.patterns().size(); idx++) {
                // A copy, not the live element: PatternDiskContents shares its ItemStack elements and its own
                // javadoc warns they are mutable - a snapshot taken as a live reference would silently follow any
                // in-place rewrite and could then never tell a rewritten recipe from the one this row was built for.
                list.add(new DiskRef(slot, idx, contents.patterns().get(idx).copy()));
            }
            freeCapacity += Math.max(0, contents.capacity() - contents.used());
        }
        var stored = list.toArray(new DiskRef[0]);
        if (ExtendedAEPlusCompat.wantsFreeRow(levelSupplier != null, freeCapacity)) {
            // One null row stands for "this provider has room". Every accessor below already answers "empty
            // row" for a null ref, so the stored patterns stay at their flat indices and the free space
            // simply follows them - no second kind of row, and no change to the take path that walks this
            // array. A view without a level supplier is not a provider face an upload could land on, and
            // keeps its old row count.
            //
            // One row rather than one per free slot: the caller that looks for a free row walks every row
            // it is offered on paths that do not write, so 9 disks x 1024 slots would become thousands of
            // slot probes per call. All it needs to learn is that a row is free.
            refs = Arrays.copyOf(stored, stored.length + 1);
        } else {
            refs = stored;
        }
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
        return false; // AE2 终端的插入路径一律拒绝（ExtendedAE Plus 的上传走 insertItem，不经此处）
    }

    /**
     * Writes one encoded pattern onto a disk, on behalf of ExtendedAE Plus's "upload pattern to a
     * provider" button.
     *
     * <p>That button looks for an empty row in this view and then writes into it (see {@link #rebuild()}
     * for the rows it sees). The write has to land on a disk: the provider's pattern inventory is only a
     * mirror with no disk behind it, so a write landing there would be gone at the next refresh. The
     * blank pattern the upload frees is returned to the ME network, the same accounting the encoding
     * terminal applies when a player writes a disk by hand.</p>
     *
     * <p>Honoured only while ExtendedAE Plus is installed, so for every other caller the take path stays
     * the only way a pattern leaves this view. A simulated call probes the disks and reports as if the
     * write had happened, without writing and without returning a blank pattern.</p>
     *
     * <p>Compatibility shim: its scope and its exit condition are documented on {@link ExtendedAEPlusCompat}.</p>
     *
     * <p>The {@code index} belongs to the caller's row scan: rows move between rebuilds and a disk-backed
     * provider decides where a pattern lands, so the index is range-checked but never used to pick a disk.</p>
     *
     * @return {@link ItemStack#EMPTY} when the pattern was taken, otherwise {@code stack} unchanged
     */
    @Override
    public ItemStack insertItem(int index, ItemStack stack, boolean simulate) {
        // Encoded patterns are single items; a stack of them is not something a disk can store, and taking
        // it would drop the surplus.
        if (stack.isEmpty() || stack.getCount() != 1 || !ExtendedAEPlusCompat.isPresent()) {
            return stack;
        }
        if (index < 0 || index >= refs.length) {
            return stack; // a row this view no longer has: the caller's snapshot is stale
        }
        var level = levelSupplier == null ? null : levelSupplier.get();
        if (level == null) {
            return stack; // not in a level yet: there is no disk state to decode a pattern against
        }
        // The first disk that takes it wins, in slot order: a disk-backed provider decides where a pattern
        // lands, and the upload path offers no way to say otherwise.
        for (int slot = 0; slot < diskInventory.size(); slot++) {
            var diskStack = diskInventory.getStackInSlot(slot);
            if (diskStack.isEmpty() || !(diskStack.getItem() instanceof PatternDiskItem disk)) {
                continue;
            }
            if (!disk.canInsert(diskStack, stack, level)) {
                continue; // room, locked type and same-result exclusion all live in canInsert
            }
            if (simulate) {
                return ItemStack.EMPTY; // a disk takes it: report as if the write had landed
            }
            var updated = diskStack.copy();
            if (!disk.tryInsert(updated, stack, level)) {
                continue; // canInsert said yes: a refusal here means the disk changed, so keep looking
            }
            diskInventory.setItemDirect(slot, updated);
            returnBlankPattern(slot);
            onChange.run(); // rebuild the provider's pattern list and drop the cached view
            return ItemStack.EMPTY;
        }
        return stack; // no disk takes it: hand the pattern back so the caller reports a failure
    }

    /**
     * Returns one blank pattern this view owes the ME network, logging the loss when the network will not
     * take it back. Best effort by design: whatever freed the pattern is already done and is not rolled
     * back, since the caller offers no fallback destination for it.
     *
     * @param diskSlot the disk involved, for the log line
     */
    private void returnBlankPattern(int diskSlot) {
        if (blankPatternSink != null && !blankPatternSink.returnBlankPatterns(1)) {
            LOGGER.warn("A blank pattern owed to the ME network could not be returned (disk slot {}); "
                    + "one blank pattern is lost", diskSlot);
        }
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
        if (ref.patternIndex >= patterns.size()) {
            return ItemStack.EMPTY;
        }
        // The row is only what it was built for. Removing a recipe moves everything behind it up one, and this
        // view's own removals shift its refs to follow; a removal from outside it - the pattern management screens
        // take recipes out through the store - does not, and the row would then show, take, or charge for the
        // neighbour the player never picked. A snapshot that no longer matches what sits there makes the row
        // empty instead, which every caller already handles: nothing is displayed, nothing is handed out, and
        // nothing is emptied. The next view a terminal opens is scanned fresh.
        var current = patterns.get(ref.patternIndex);
        return ItemStack.isSameItemSameComponents(current, ref.expected()) ? current : ItemStack.EMPTY;
    }

    private void setItemDirectImpl(DiskRef ref, ItemStack stack) {
        // 磁盘样板槽仅可取出，不可放入。非空写入只能是 AE2 swap 的恢复写回：若该行刚被本视图取出
        // （lastRemoved 命中），把原样板原位插回并返还空白——swap 整体无效果；其余非空写入（如
        // 恶意携带物、非恢复写回）一律拒绝。ExtendedAE Plus 的上传是唯一的另一条写入口，走 insertItem。
        if (!stack.isEmpty()) {
            if (restoreLastRemoval(ref, stack)) {
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
    private boolean restoreLastRemoval(DiskRef ref, ItemStack stack) {
        var removed = lastRemoved.get(ref);
        if (removed == null || removed.stack().isEmpty()) {
            return false;
        }
        // Only a write-back of the very stack this row just gave up is a swap restore. Anything else - an entry
        // left behind by a path that never restores, or a row that merely happens to share the address after a
        // reindex - must not put a recipe back that was already taken.
        if (!ItemStack.matches(removed.stack(), stack)) {
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
        // Taking the entry shifted this disk's later rows down by one; putting it back at its original position
        // undoes that, so the rows have to come back too. This row is still null here, which is what keeps it
        // from shifting itself.
        for (int i = 0; i < refs.length; i++) {
            DiskRef other = refs[i];
            if (other != null && other.diskSlot == ref.diskSlot && other.patternIndex >= ref.patternIndex) {
                // Same instance as before, for the reason given in reindexAfterRemoval: DiskRef equality and the
                // lastRemoved lookup are built from it.
                refs[i] = new DiskRef(ref.diskSlot, other.patternIndex + 1, other.expected());
            }
        }
        // 精确填回该行原 flat 位置（磁盘已原位恢复，其它行未动）。
        if (removed.flatIndex() >= 0 && removed.flatIndex() < refs.length && refs[removed.flatIndex()] == null) {
            refs[removed.flatIndex()] = ref;
        }
        lastRemoved.remove(ref);
        returnBlankPattern(ref.diskSlot);
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
        // The SPLIT path never writes back, so it must not leave behind a restore entry: a later write aimed at
        // whatever row ends up at this address would otherwise resurrect this recipe and hand out a blank for a
        // take that already happened.
        lastRemoved.remove(ref);
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
        // Checked again here even though every caller just did: the blank this removal costs is drawn in between,
        // and a disk that moved under that call would otherwise have an entry deleted from the wrong address.
        if (!ItemStack.isSameItemSameComponents(contents.patterns().get(ref.patternIndex), ref.expected())) {
            return;
        }
        var next = contents.remove(ref.patternIndex);
        stack.set(AEPatternRegistries.DISK_CONTENTS.get(), next);
        diskInventory.setItemDirect(ref.diskSlot, stack);
        // 本行置空（占位），保持 PAT 会话内行数冻结：AE2 PAT 固定每个打开会话的行数，refs 收缩
        // 会使已打开终端与服务端失同步（末尾残留幽灵行）。磁盘内容已真删并自动退行补位，
        // 下一次重建视图（重开 PAT / 新会话 rebuild 扫盘）即呈现紧凑补位布局。
        int flat = markRowEmptied(ref);
        // The entry is gone from the disk, so every later index on that same disk now addresses the next
        // recipe. Leaving the rows alone made them show - and extract - something other than the recipe the
        // row was built for, which is the worst possible failure here: the row the player sees is not the
        // row the delete lands on.
        reindexAfterRemoval(ref.diskSlot, ref.patternIndex);
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
     * Shifts the rows of {@code diskSlot} that sat after {@code removedIndex} down one.
     *
     * <p>Rows are addressed by their position inside the disk, and removing an entry moves everything behind it
     * up one. The row count itself stays frozen - the terminal needs that - so only the addresses change. The
     * taken row is already nulled by the caller and is skipped by that.</p>
     */
    private void reindexAfterRemoval(int diskSlot, int removedIndex) {
        for (int i = 0; i < refs.length; i++) {
            DiskRef other = refs[i];
            if (other != null && other.diskSlot == diskSlot && other.patternIndex > removedIndex) {
                // The snapshot travels with the address: the pattern behind the removed one is the same pattern,
                // it just sits one place earlier now.
                // expected stays the same instance: DiskRef equality - and with it the lastRemoved key a later
                // swap restore looks up - is built from it, so copying here would silently break that lookup.
                refs[i] = new DiskRef(diskSlot, other.patternIndex - 1, other.expected());
            }
        }
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
            if (blankPatternSink == null || !blankPatternSink.hasBlankPatterns(1)) {
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

    private record DiskRef(int diskSlot, int patternIndex, ItemStack expected) {
    }
}
