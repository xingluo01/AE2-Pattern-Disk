package io.github.lounode.ae2pattern.integration.neoecoae;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.inventories.InternalInventory;

import io.github.lounode.ae2pattern.common.item.PatternDiskItem;

/**
 * Disk operations on a bus's pattern inventory.
 *
 * <p>The bus hands out its pattern slots as an {@code InternalInventory}; the disks are simply the
 * slots holding a {@link PatternDiskItem}. Every question the auxiliary-store API asks - can this disk
 * take a pattern, does any disk still have room, what patterns do the disks carry - is answered by one
 * scan here, so the callbacks cannot drift apart from each other.</p>
 *
 * <p>Writes go back through that same inventory rather than mutating a detached copy, so a disk update
 * reaches the bus through its ordinary inventory notification and the bus can re-index what changed.</p>
 */
final class NeoECOBusDisks {

    private NeoECOBusDisks() {
    }

    /** A pattern disk found in a bus slot, kept together with the slot it must be written back to. */
    private record BusDisk(int slot, ItemStack stack, PatternDiskItem item) {
    }

    /** @return whether {@code stack} is a pattern disk, the container this integration serves from. */
    static boolean ownsDisk(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof PatternDiskItem;
    }

    /** @return {@code true} when at least one disk on the bus would accept {@code pattern}. */
    static boolean anyDiskAccepts(NeoECOBusAccess.BusHandles handles, Object bus, ItemStack pattern) {
        Level level = NeoECOBusAccess.levelOf(handles, bus);
        InternalInventory inventory = NeoECOBusAccess.patternInventory(handles, bus);
        if (level == null || inventory == null) {
            return false;
        }
        for (BusDisk disk : disksIn(inventory)) {
            // Room, locked type and same-result exclusion all live in PatternDiskItem.canInsert, so this
            // probe cannot disagree with what tryInsert would actually do.
            if (disk.item().canInsert(disk.stack(), pattern, level)) {
                return true;
            }
        }
        return false;
    }

    /** @return {@code true} when some disk is not at capacity yet, whatever the pattern would be. */
    static boolean anyDiskHasRoom(NeoECOBusAccess.BusHandles handles, Object bus) {
        InternalInventory inventory = NeoECOBusAccess.patternInventory(handles, bus);
        if (inventory == null) {
            return false;
        }
        for (BusDisk disk : disksIn(inventory)) {
            if (!disk.item().contents(disk.stack()).isFull()) {
                return true;
            }
        }
        return false;
    }

    /** @return {@code true} when {@code pattern} reached a disk; the disk stack is written back in place. */
    static boolean insertIntoDisk(NeoECOBusAccess.BusHandles handles, Object bus, ItemStack pattern) {
        Level level = NeoECOBusAccess.levelOf(handles, bus);
        InternalInventory inventory = NeoECOBusAccess.patternInventory(handles, bus);
        if (level == null || inventory == null) {
            return false;
        }
        for (BusDisk disk : disksIn(inventory)) {
            // tryInsert mutates the stack's contents component rather than returning a new stack.
            if (!disk.item().tryInsert(disk.stack(), pattern, level)) {
                continue;
            }
            inventory.setItemDirect(disk.slot(), disk.stack());
            return true;
        }
        return false;
    }

    /** @return every pattern stored on the bus's disks, still encoded. */
    static List<ItemStack> collectEncodedPatterns(NeoECOBusAccess.BusHandles handles, Object bus) {
        InternalInventory inventory = NeoECOBusAccess.patternInventory(handles, bus);
        if (inventory == null) {
            return List.of();
        }
        List<ItemStack> encoded = new ArrayList<>();
        for (BusDisk disk : disksIn(inventory)) {
            encoded.addAll(disk.item().contents(disk.stack()).patterns());
        }
        return encoded;
    }

    /**
     * A change token for the bus's disk contents.
     *
     * <p>Every mutation replaces the disk's {@code PatternDiskContents} record, so the <em>identity</em> of
     * that record changes on every write. Fingerprinting identities keeps this O(slots) instead of hashing
     * every stored pattern, which matters because the bus polls it whenever it is asked for a content
     * revision. Disks with no patterns are skipped: they contribute nothing, and a blank disk's contents
     * are freshly built on each read, so including one would invalidate on every poll.</p>
     *
     * <p>Identities are 32 bits, so the pattern count and the locked type are added as cheap
     * discriminators: an unnoticed change would have to collide on the identity hash <em>and</em> leave the
     * count and the type untouched.</p>
     */
    static long diskRevision(NeoECOBusAccess.BusHandles handles, Object bus) {
        InternalInventory inventory = NeoECOBusAccess.patternInventory(handles, bus);
        if (inventory == null) {
            return 0L;
        }
        long hash = 1L;
        for (BusDisk disk : disksIn(inventory)) {
            var contents = disk.item().contents(disk.stack());
            if (contents.patterns().isEmpty()) {
                continue;
            }
            hash = hash * 31L + disk.slot();
            hash = hash * 31L + System.identityHashCode(contents);
            hash = hash * 31L + contents.used();
            hash = hash * 31L + Objects.hashCode(contents.type());
        }
        return hash;
    }

    private static List<BusDisk> disksIn(InternalInventory inventory) {
        List<BusDisk> disks = new ArrayList<>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof PatternDiskItem item) {
                disks.add(new BusDisk(slot, stack, item));
            }
        }
        return disks;
    }
}
