package io.github.lounode.ae2pattern.common.pattern;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.ItemStack;

import appeng.api.inventories.InternalInventory;

import io.github.lounode.ae2pattern.common.item.PatternDiskItem;

/**
 * Writable {@link InternalInventory} view over the encoded patterns stored across all inserted pattern
 * disks, exposed to AE2 pattern access terminals (PAT) as the provider's terminal inventory.
 *
 * <p>Unlike the read-only adapter it replaces, {@link #extractItem}/{@link #removeItems}/{@link #setItemDirect}
 * actually mutate the source disk: removing a pattern physically drops it from the disk's stored contents.
 * Every real extraction first asks the attached ME network for a {@code blank pattern} (via the
 * {@link BlankPatternSink}); extraction is refused when the network holds none. Simulated operations
 * ({@code simulate=true}) never mutate anything, keeping terminal previews cost-free and idempotent.</p>
 *
 * <p>A {@code DiskRef}({@code diskSlot}, {@code patternIndex}) table maps the flattened terminal index to a
 * concrete disk slot + in-disk index, so removal lands on the correct source pattern.</p>
 *
 * @param diskInventory the provider's disk slot inventory; only {@link PatternDiskItem} slots count
 * @param blankPatternSink draws one blank pattern from the ME network; {@code null} refuses extraction
 * @param onChange       invoked after a real mutation so the provider can persist and rebuild the view
 */
public class PatternDiskRemoveInventory implements InternalInventory {

    /** Draws {@code count} blank patterns from the attached ME network, all-or-nothing. */
    @FunctionalInterface
    public interface BlankPatternSink {
        boolean drawBlankPatterns(int count);
    }

    /** Flat terminal index -> (disk slot, in-disk index). Rebuilt whenever the disk layout changes. */
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

    /** Re-syncs the disk-slot -> pattern mapping. Call whenever the disk inventory may have changed. */
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
        var stack = diskInventory.getStackInSlot(ref.diskSlot);
        if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem disk)) {
            return ItemStack.EMPTY;
        }
        var patterns = disk.contents(stack).patterns();
        return ref.patternIndex < patterns.size() ? patterns.get(ref.patternIndex) : ItemStack.EMPTY;
    }

    /**
     * Clearing a slot is the terminal's extraction sink: the source disk pattern is removed, but only
     * after one blank pattern is drawn from the network to materialise the encoded pattern.
     */
    @Override
    public void setItemDirect(int index, ItemStack stack) {
        if (!stack.isEmpty()) {
            return; // disk-backed provider does not accept pattern writes placed from a terminal
        }
        var ref = refAt(index);
        if (ref == null) {
            return;
        }
        if (blankPatternSink == null || !blankPatternSink.drawBlankPatterns(1)) {
            return;
        }
        removePatternAt(ref);
    }

    @Override
    public ItemStack extractItem(int index, int amount, boolean simulate) {
        var stack = getStackInSlot(index);
        if (stack.isEmpty() || amount <= 0) {
            return ItemStack.EMPTY;
        }
        var ref = refAt(index);
        if (ref == null) {
            return ItemStack.EMPTY;
        }
        int toTake = Math.min(amount, stack.getCount());
        var result = stack.copy();
        result.setCount(toTake);
        if (simulate) {
            return result;
        }
        // Cost one blank pattern per extracted pattern. Refuse the whole extraction when the network
        // cannot cover every item, so a partial take never leaves the disk (and the player) short.
        if (!blankPatternSink.drawBlankPatterns(toTake)) {
            return ItemStack.EMPTY;
        }
        removePatternAt(ref);
        return result;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return false; // disk-backed provider does not accept pattern writes from a terminal
    }

    private DiskRef refAt(int index) {
        return index >= 0 && index < refs.length ? refs[index] : null;
    }

    private void removePatternAt(DiskRef ref) {
        var stack = diskInventory.getStackInSlot(ref.diskSlot);
        if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem disk)) {
            return;
        }
        var next = disk.contents(stack).remove(ref.patternIndex);
        stack.set(io.github.lounode.ae2pattern.core.AEPatternComponents.DISK_CONTENTS.get(), next);
        diskInventory.setItemDirect(ref.diskSlot, stack);
        onChange.run();
    }

    private record DiskRef(int diskSlot, int patternIndex) {
    }
}
