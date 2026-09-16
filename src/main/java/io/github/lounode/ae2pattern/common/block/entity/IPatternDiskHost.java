package io.github.lounode.ae2pattern.common.block.entity;

import appeng.api.inventories.InternalInventory;

import net.minecraft.core.BlockPos;

/**
 * Implemented by machines that hold pattern disks in an inventory slot. Used by the pattern disk
 * encoding terminal to discover every disk host on the grid (pattern disk providers, batch molecular
 * assemblers, ...) instead of hard-coding concrete block entity classes.
 */
public interface IPatternDiskHost {

    /** @return The inventory whose slots may contain {@code PatternDiskItem} stacks. */
    InternalInventory getDiskInventory();

    /** @return The host's position, used as a stable identity for terminal-side fingerprinting. */
    BlockPos getBlockPos();

    /**
     * Extra identity mixed into the terminal fingerprint for hosts that share a position - several
     * cable-attached panels can sit on the same cable, and their disks must not be mistaken for one
     * another (a stale fingerprint means the terminal shows the wrong disks, or skips a refresh).
     *
     * @return A value unique among hosts at the same position; {@code 0} when the position is enough.
     */
    default int getIdentitySalt() {
        return 0;
    }
}
