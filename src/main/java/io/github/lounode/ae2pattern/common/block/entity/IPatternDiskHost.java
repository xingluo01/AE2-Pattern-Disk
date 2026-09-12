package io.github.lounode.ae2pattern.common.block.entity;

import appeng.util.inv.AppEngInternalInventory;

import net.minecraft.core.BlockPos;

/**
 * Implemented by machines that hold pattern disks in an inventory slot. Used by the pattern disk
 * encoding terminal to discover every disk host on the grid (pattern disk providers, batch molecular
 * assemblers, ...) instead of hard-coding concrete block entity classes.
 */
public interface IPatternDiskHost {

    /** @return The inventory whose slots may contain {@code PatternDiskItem} stacks. */
    AppEngInternalInventory getDiskInventory();

    /** @return The host's position, used as a stable identity for terminal-side fingerprinting. */
    BlockPos getBlockPos();
}
