package io.github.lounode.ae2pattern.common.pattern;

import java.util.List;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.ItemStack;

import appeng.api.inventories.BaseInternalInventory;
import appeng.api.inventories.InternalInventory;

/**
 * Flattens several {@link InternalInventory} views into one contiguous terminal view.
 *
 * <p>Used to expose a machine's slot-based patterns and its pattern-disk patterns to AE2's pattern
 * access terminal as a single inventory: AE2 only ever asks a {@code PatternContainer} for one
 * terminal inventory, so the two sources have to be concatenated rather than returned separately.</p>
 *
 * <p>Index routing is positional: part {@code i} owns the index range
 * {@code [starts[i], starts[i] + parts[i].size())}. The composite holds no state of its own, so a
 * caller that rebuilds its parts (as {@link PatternDiskRemoveInventory} does per terminal session)
 * gets a correctly re-flowed view.</p>
 *
 * <p><b>{@link #getSlotInv(int)} must be routed to the owning part.</b> AE2's pattern access terminal
 * takes patterns through {@code getSlotInv} and relies on the part's own single-slot guard (the blank
 * pattern pre-check that prevents duplicating a disk pattern). The interface default would wrap
 * <em>this</em> composite in a {@code SubInventoryProxy}, bypassing that guard entirely.</p>
 */
public class CompositePatternInventory extends BaseInternalInventory {

    private final InternalInventory[] parts;
    private final int[] starts;
    private final int total;

    private CompositePatternInventory(InternalInventory[] parts) {
        this.parts = parts;
        this.starts = new int[parts.length];
        int cursor = 0;
        for (int i = 0; i < parts.length; i++) {
            this.starts[i] = cursor;
            cursor += parts[i].size();
        }
        this.total = cursor;
    }

    public static InternalInventory of(InternalInventory... parts) {
        return new CompositePatternInventory(parts);
    }

    public static InternalInventory of(List<InternalInventory> parts) {
        return new CompositePatternInventory(parts.toArray(new InternalInventory[0]));
    }

    /**
     * Wraps {@code source} so that only stacks accepted by {@code accept} are visible. Used to hide
     * pattern disks from the slot part of a composite (the disks are shown by their expanded contents
     * instead).
     *
     * <p>Hidden slots are <b>read-only</b>: a write that would land on a slot whose real content is
     * filtered out is rejected. AE2's pattern access terminal clears a row with
     * {@code setItemDirect(EMPTY)} after reading it, so a pass-through empty write here would wipe the
     * pattern disk occupying that slot.</p>
     */
    public static InternalInventory filtering(InternalInventory source, Predicate<ItemStack> accept) {
        return new FilteredInternalInventory(source, accept);
    }

    /**
     * Wraps {@code source} so that only the first {@code limit} slots are visible. Used to clip a
     * machine's full backing array to its currently visible slots (the FD Smart Pattern Bus allocates
     * all pages up front but only exposes the active ones).
     */
    public static InternalInventory limited(InternalInventory source, int limit) {
        return new LimitedInternalInventory(source, limit);
    }

    @Nullable
    private InternalInventory partAt(int index) {
        if (index < 0 || index >= total) {
            return null;
        }
        // Parts are few (2-3), so a linear scan beats the bookkeeping of a binary search.
        for (int i = parts.length - 1; i >= 0; i--) {
            if (index >= starts[i]) {
                return parts[i];
            }
        }
        return null;
    }

    private int localIndex(int index) {
        for (int i = parts.length - 1; i >= 0; i--) {
            if (index >= starts[i]) {
                return index - starts[i];
            }
        }
        return -1;
    }

    @Override
    public int size() {
        return total;
    }

    @Override
    public int getSlotLimit(int slot) {
        var part = partAt(slot);
        return part == null ? 0 : part.getSlotLimit(localIndex(slot));
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        var part = partAt(slot);
        return part == null ? ItemStack.EMPTY : part.getStackInSlot(localIndex(slot));
    }

    @Override
    public void setItemDirect(int slot, ItemStack stack) {
        var part = partAt(slot);
        if (part != null) {
            part.setItemDirect(localIndex(slot), stack);
        }
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        var part = partAt(slot);
        return part != null && part.isItemValid(localIndex(slot), stack);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        var part = partAt(slot);
        return part == null ? ItemStack.EMPTY : part.extractItem(localIndex(slot), amount, simulate);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        var part = partAt(slot);
        return part == null ? stack : part.insertItem(localIndex(slot), stack, simulate);
    }

    /**
     * Routes to the owning part instead of letting the interface default wrap the composite. This is
     * what keeps a part's own single-slot guard (blank-pattern pre-check) in force for terminal
     * extraction.
     */
    @Override
    public InternalInventory getSlotInv(int index) {
        var part = partAt(index);
        return part == null ? InternalInventory.empty() : part.getSlotInv(localIndex(index));
    }

    /** Hides stacks rejected by the predicate; hidden slots reject writes so they cannot be wiped. */
    private static final class FilteredInternalInventory extends BaseInternalInventory {

        private final InternalInventory source;
        private final Predicate<ItemStack> accept;

        private FilteredInternalInventory(InternalInventory source, Predicate<ItemStack> accept) {
            this.source = source;
            this.accept = accept;
        }

        private boolean visible(int slot) {
            return accept.test(source.getStackInSlot(slot));
        }

        @Override
        public int size() {
            return source.size();
        }

        @Override
        public int getSlotLimit(int slot) {
            return slot >= 0 && slot < source.size() ? source.getSlotLimit(slot) : 0;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            var stack = source.getStackInSlot(slot);
            return accept.test(stack) ? stack : ItemStack.EMPTY;
        }

        @Override
        public void setItemDirect(int slot, ItemStack stack) {
            // A slot whose real content is filtered out (e.g. a pattern disk) is read-only: AE2's
            // terminal clears rows with setItemDirect(EMPTY), which must never reach the disk.
            if (!visible(slot)) {
                return;
            }
            if (stack.isEmpty() || accept.test(stack)) {
                source.setItemDirect(slot, stack);
            }
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return accept.test(stack) && source.isItemValid(slot, stack);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return visible(slot) ? source.extractItem(slot, amount, simulate) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (!visible(slot)) {
                return stack;
            }
            return accept.test(stack) ? source.insertItem(slot, stack, simulate) : stack;
        }

        /**
         * Keeps the gate on single-slot access. The interface default would proxy {@code this}, and
         * {@code SubInventoryProxy} forwards writes without consulting {@link #isItemValid} or the
         * visibility predicate, which would re-open the hidden-slot write path.
         */
        @Override
        public InternalInventory getSlotInv(int index) {
            if (index < 0 || index >= size() || !visible(index)) {
                return InternalInventory.empty();
            }
            return new FilteredInternalInventory(source.getSlotInv(index), accept);
        }
    }

    /** Exposes only the first {@code limit} slots of {@code source}. */
    private static final class LimitedInternalInventory extends BaseInternalInventory {

        private final InternalInventory source;
        private final int limit;

        private LimitedInternalInventory(InternalInventory source, int limit) {
            this.source = source;
            this.limit = Math.max(0, Math.min(limit, source.size()));
        }

        @Override
        public int size() {
            return limit;
        }

        @Override
        public int getSlotLimit(int slot) {
            return slot >= 0 && slot < limit ? source.getSlotLimit(slot) : 0;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return slot >= 0 && slot < limit ? source.getStackInSlot(slot) : ItemStack.EMPTY;
        }

        @Override
        public void setItemDirect(int slot, ItemStack stack) {
            if (slot >= 0 && slot < limit) {
                source.setItemDirect(slot, stack);
            }
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot >= 0 && slot < limit && source.isItemValid(slot, stack);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return slot >= 0 && slot < limit ? source.extractItem(slot, amount, simulate) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return slot >= 0 && slot < limit ? source.insertItem(slot, stack, simulate) : stack;
        }

        @Override
        public InternalInventory getSlotInv(int index) {
            return index >= 0 && index < limit ? source.getSlotInv(index) : InternalInventory.empty();
        }
    }
}
