package io.github.lounode.ae2pattern.api;

import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionHost;

import io.github.lounode.ae2pattern.common.block.entity.PatternDiskHostRegistry;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.pattern.PatternDiskContents;
import io.github.lounode.ae2pattern.common.pattern.PatternDiskTerminalView;

/**
 * Public entry points for addons that <em>carry</em> pattern disks or <em>serve</em> the patterns stored
 * on them.
 *
 * <p>Everything reachable from this class is stable surface: the methods here, together with every type
 * that appears in their signatures, change only with {@link #API_VERSION} - which an addon can assert
 * once during its own setup, to fail loudly rather than misbehave quietly.</p>
 *
 * <h2>What an addon usually needs</h2>
 *
 * <ul>
 *   <li><b>Reading a disk.</b> {@link #contents(ItemStack)} says what one holds without naming the
 *       disk's item class, so a slot filter can keep a stack opaque and still ask.</li>
 *   <li><b>Serving the disks a machine holds.</b> {@link #terminalView} wraps slots that contain disks
 *       into an inventory whose rows are the patterns <em>on</em> those disks - the shape AE2's pattern
 *       access terminal reads, so the recipes show up there instead of an undecodable disk item.</li>
 *   <li><b>Letting players write to those disks.</b> {@link #registerDiskHost} hands this mod's disk
 *       encoding terminal the machines to list, so their disks can be encoded into from there.</li>
 * </ul>
 */
public final class PatternDiskApi {

    /**
     * Version of this entry point. It only changes when a signature reachable from this class changes;
     * an addon may assert it at startup to fail loudly instead of misbehaving quietly.
     */
    public static final int API_VERSION = 1;

    private PatternDiskApi() {
    }

    /**
     * @return whether {@code stack} is one of this mod's pattern disks, without exposing its item class
     */
    public static boolean isPatternDisk(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof PatternDiskItem;
    }

    /**
     * <p>The pattern stacks inside the returned value are the live ones held by the disk: read them, but
     * do not modify them in place - writes belong to the disk's own insert path.</p>
     *
     * @return what {@code stack} holds, or {@code null} when it is not a pattern disk - a disk that
     *         holds nothing still answers, with an untyped empty set of its own capacity
     */
    @Nullable
    public static PatternDiskContents contents(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem disk)) {
            return null;
        }
        return disk.contents(stack);
    }

    /**
     * @return whether the disk would accept {@code pattern} right now. Room, the disk's locked type and
     *         the same-result exclusion all live in this answer, so it never disagrees with a write.
     */
    public static boolean canAccept(ItemStack disk, ItemStack pattern, Level level) {
        return disk != null && !disk.isEmpty() && disk.getItem() instanceof PatternDiskItem item
                && item.canInsert(disk, pattern, level);
    }

    /**
     * Wraps slots that hold pattern disks into a view whose rows are the patterns on those disks.
     *
     * <p>Exposing the slots directly would show a player an undecodable disk item; this view expands it,
     * charges a blank pattern from the network for every pattern taken and removes that recipe from its
     * disk. The returned view caches its row layout, so call {@link PatternDiskTerminalView#invalidate()}
     * when the disks change.</p>
     *
     * <p>Its write path (a pattern being uploaded onto a disk) first checks that the network can take the
     * blank pattern that write frees, and <b>refuses the write</b> when it cannot - so an upload can fail
     * for a full or offline network, and the pattern stays with the caller instead of the blank pattern
     * being lost.</p>
     *
     * @param diskSlots  the slots holding pattern disks (slots holding anything else are ignored). The
     *                   view writes back through {@code setItemDirect} whenever a pattern is taken,
     *                   uploaded or rolled back, so the inventory must actually persist writes
     * @param grid       resolves the attached grid, used to draw and return blank patterns
     * @param machine    the host, used as the action source for that accounting
     * @param onChanged  invoked after a real mutation so the host can persist and rebuild
     * @param level      resolves the level, needed to decode a pattern before it is written to a disk
     */
    public static PatternDiskTerminalView terminalView(InternalInventory diskSlots, Supplier<IGrid> grid,
            IActionHost machine, Runnable onChanged, Supplier<Level> level) {
        return new PatternDiskTerminalView(diskSlots, grid, machine, onChanged, level);
    }

    /**
     * Registers a collector of the disk hosts a machine from another mod provides, so this mod's disk
     * encoding terminal lists their disks too. Safe to call from an integration's server-side setup.
     */
    public static void registerDiskHost(DiskHostCollector collector) {
        PatternDiskHostRegistry.register(collector);
    }
}
