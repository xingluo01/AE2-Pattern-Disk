package io.github.lounode.ae2pattern.common.pattern;

import java.util.function.Supplier;

import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.core.definitions.AEItems;
import appeng.util.inv.AppEngInternalInventory;

/**
 * Terminal-facing view of the disks held by an ME pattern disk provider, shared by both of its forms
 * (the in-world block and the cable-attached panel part).
 *
 * <p>The provider logic keeps an internal pattern inventory that is only a <em>derived copy</em> of the
 * patterns stored on the disks. Exposing that copy to AE2's pattern access terminal would let a player
 * take an encoded pattern without touching the disk it came from - the take would be undone by the next
 * rebuild, and the disk would still hold the pattern. Both hosts therefore expose
 * {@link PatternDiskRemoveInventory} instead, which expands reads from the disks, charges a blank
 * pattern from the network for every take and physically removes the recipe from its disk, refusing
 * all writes.</p>
 *
 * <p>The disk inventory is small, but the view is rebuilt from scratch by the constructor and by
 * {@link #invalidate()}, and its row layout must stay stable while a terminal is open - so the view is
 * cached between rebuilds and only replaced when the disks actually change.</p>
 */
public final class PatternDiskTerminalView implements PatternDiskRemoveInventory.BlankPatternSink {

    private final AppEngInternalInventory diskInventory;
    private final Supplier<IGrid> gridSupplier;
    private final IActionSource actionSource;
    private final Runnable onChanged;
    private final Supplier<Level> levelSupplier;

    private PatternDiskRemoveInventory view;

    /**
     * @param diskInventory the slots holding pattern disks
     * @param gridSupplier  resolves the attached grid lazily; may return {@code null} while off-grid
     * @param machine       the host, used as the action source for blank-pattern accounting
     * @param onChanged     invoked after a real mutation so the host can persist and rebuild
     * @param levelSupplier resolves the level lazily, for decoding a pattern before it is written to a disk
     */
    public PatternDiskTerminalView(AppEngInternalInventory diskInventory, Supplier<IGrid> gridSupplier,
            IActionHost machine, Runnable onChanged, Supplier<Level> levelSupplier) {
        this.diskInventory = diskInventory;
        this.gridSupplier = gridSupplier;
        this.actionSource = IActionSource.ofMachine(machine);
        this.onChanged = onChanged;
        this.levelSupplier = levelSupplier;
    }

    /** @return the current terminal view, rebuilding it if the disks changed since the last call. */
    public InternalInventory view() {
        if (view == null) {
            view = new PatternDiskRemoveInventory(diskInventory, this, onChanged, levelSupplier);
        }
        return view;
    }

    /** Drops the cached view so the next terminal session re-scans the disks into a fresh layout. */
    public void invalidate() {
        view = null;
    }

    @Override
    public boolean drawBlankPatterns(int count) {
        // All-or-nothing: check first, then take in a single modulating extract.
        if (!hasBlankPatterns(count)) {
            return false;
        }
        var grid = gridSupplier.get();
        if (grid == null) {
            return false;
        }
        var storage = grid.getStorageService().getInventory();
        var blank = AEItemKey.of(AEItems.BLANK_PATTERN);
        return storage.extract(blank, count, Actionable.MODULATE, actionSource) == count;
    }

    @Override
    public boolean hasBlankPatterns(int count) {
        var grid = gridSupplier.get();
        if (grid == null || count <= 0) {
            return false;
        }
        var storage = grid.getStorageService().getInventory();
        var blank = AEItemKey.of(AEItems.BLANK_PATTERN);
        return storage.extract(blank, count, Actionable.SIMULATE, actionSource) == count;
    }

    @Override
    public boolean returnBlankPatterns(int count) {
        var grid = gridSupplier.get();
        if (grid == null || count <= 0) {
            return false;
        }
        var storage = grid.getStorageService().getInventory();
        var blank = AEItemKey.of(AEItems.BLANK_PATTERN);
        return storage.insert(blank, count, Actionable.MODULATE, actionSource) == count;
    }
}
