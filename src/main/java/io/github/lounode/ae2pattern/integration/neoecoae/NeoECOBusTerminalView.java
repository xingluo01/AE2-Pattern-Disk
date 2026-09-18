package io.github.lounode.ae2pattern.integration.neoecoae;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.ItemStack;

import appeng.api.config.Actionable;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.core.definitions.AEItems;

import io.github.lounode.ae2pattern.common.pattern.PatternDiskRemoveInventory;

/**
 * What the pattern access terminal sees when this integration has taken over an FD Smart Pattern Bus: the
 * bus's own patterns first, the recipes held on the disks sitting in its slots after them.
 *
 * <p>The bus builds a view of that shape itself, but lets the terminal only look at it - the recipes it
 * appends are display rows, and taking one out does nothing. This one is what replaces it, and the
 * difference is that a row can be taken: the recipe leaves the disk and a blank pattern is drawn from the
 * bus's ME network to pay for the one that went into the network's pattern index. That is the whole point
 * of serving the disks here rather than letting the bus describe them.</p>
 *
 * <p>Two things are load-bearing:</p>
 * <ul>
 *   <li>The row mapping is scanned once, when the view is built, and never reflowed. A terminal sizes its
 *       mirror from whatever the container reported at the moment the session opened and keeps indexing
 *       that mirror afterwards, so a view that reflows under it walks off the end. A removal marks its row
 *       empty in place instead of shrinking, for the same reason. The bus asks for a fresh view once the
 *       disks' revision moves, which is when a change is meant to be seen.</li>
 *   <li>The movable rows are declared through the hook rather than by implementing the bus's prefix
 *       interface. For the terminal's move-all that declaration is what keeps the appended rows - which
 *       are not slots and cannot be written back - from being copied into a player's inventory.</li>
 * </ul>
 */
final class NeoECOBusTerminalView implements InternalInventory {

    private final InternalInventory slots;
    private final PatternDiskRemoveInventory disks;
    /** Visible head rows in order: the bus slots that hold something other than a disk. */
    private final int[] head;

    private final IGrid grid;

    /**
     * The bus this view speaks for. Not read on any path yet: the blank a removal costs is drawn with an
     * unattributed source because the bus is not statically an action host here. Kept so the view knows which
     * bus it belongs to, which is what naming it would need.
     */
    private final Object bus;

    NeoECOBusTerminalView(InternalInventory slots, IGrid grid, Object bus) {
        this.slots = slots;
        this.grid = grid;
        this.bus = bus;
        // Null level supplier: this view is the bus's own rows, not a provider face a terminal could upload
        // through, so it also keeps reporting stored patterns only.
        this.disks = new PatternDiskRemoveInventory(slots, this::drawBlankPatterns, () -> { }, null);
        this.head = headRows(slots);
    }

    /** The rows the terminal may move in bulk: the bus's own, never the disk recipes appended after them. */
    int movableRows() {
        return head.length;
    }

    @Override
    public int size() {
        return head.length + disks.size();
    }

    @Override
    public ItemStack getStackInSlot(int index) {
        return index < head.length
                ? slots.getStackInSlot(head[index]).copy()
                : disks.getStackInSlot(index - head.length);
    }

    @Override
    public void setItemDirect(int index, ItemStack stack) {
        if (index < head.length) {
            slots.setItemDirect(head[index], stack);
        } else {
            // Reached when the terminal clears a row: this is where the recipe leaves the disk and the blank
            // pattern is drawn for it.
            disks.setItemDirect(index - head.length, stack);
        }
    }

    @Override
    public ItemStack extractItem(int index, int amount, boolean simulate) {
        return index < head.length
                ? slots.extractItem(head[index], amount, simulate)
                : disks.extractItem(index - head.length, amount, simulate);
    }

    @Override
    public boolean isItemValid(int index, ItemStack stack) {
        // The bus's own filter has the final say - it is the one that decides what belongs in a pattern slot -
        // and a disk is excluded here for the same reason it is excluded from the head mapping: the bus keeps
        // those in the same slots, and this view already shows their contents as rows of their own.
        return index < head.length && !NeoECOBusDisks.ownsDisk(stack)
                && slots.isItemValid(head[index], stack);
    }

    @Override
    public InternalInventory getSlotInv(int index) {
        return index < head.length
                ? slots.getSlotInv(head[index])
                : disks.getSlotInv(index - head.length);
    }

    @Override
    public int getSlotLimit(int index) {
        // A slot holds one pattern; the default would let a caller hand over a full stack of them.
        return index < head.length ? slots.getSlotLimit(head[index]) : 1;
    }

    /** Draws the blanks a removal costs from the bus's network, all or nothing. */
    private boolean drawBlankPatterns(int count) {
        return NeoECOBusDisks.drawBlankPatterns(grid, count);
    }

    /**
     * @return the bus slots that show as rows, in slot order.
     *
     *         <p>A disk's slot is left out: its contents are the rows appended after the head, and a row that
     *         both stood for the disk and listed what is on it would be two rows for one thing. Empty slots
     *         stay in, since placing a pattern into one is what the head rows are for.</p>
     */
    private static int[] headRows(InternalInventory slots) {
        List<Integer> rows = new ArrayList<>();
        for (int slot = 0; slot < slots.size(); slot++) {
            if (!NeoECOBusDisks.ownsDisk(slots.getStackInSlot(slot))) {
                rows.add(slot);
            }
        }
        int[] mapped = new int[rows.size()];
        for (int index = 0; index < mapped.length; index++) {
            mapped[index] = rows.get(index);
        }
        return mapped;
    }
}
