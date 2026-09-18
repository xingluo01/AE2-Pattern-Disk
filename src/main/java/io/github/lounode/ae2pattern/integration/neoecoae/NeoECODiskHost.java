package io.github.lounode.ae2pattern.integration.neoecoae;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import appeng.api.inventories.InternalInventory;

import io.github.lounode.ae2pattern.api.IPatternDiskHost;

/**
 * Presents one FD Smart Pattern Bus as a disk host, so the disks sitting in its bus slots reach the
 * pattern disk encoding terminal.
 *
 * <p>The bus keeps its pattern disks in the same slots as its encoded patterns rather than in a
 * separate disk inventory, so the terminal is handed that pattern inventory and picks the disks out of
 * it - the same view {@code NeoECOBusDisks} scans when it routes insertion.</p>
 *
 * <p>A bus from another mod cannot implement {@link IPatternDiskHost} at compile time; this adapter is
 * what {@code NeoECOIntegration} registers in its place.</p>
 */
final class NeoECODiskHost implements IPatternDiskHost {

    private final Object bus;
    private final NeoECOBusAccess.BusHandles handles;

    NeoECODiskHost(Object bus, NeoECOBusAccess.BusHandles handles) {
        this.bus = bus;
        this.handles = handles;
    }

    @Override
    public InternalInventory getDiskInventory() {
        InternalInventory inventory = NeoECOBusAccess.patternInventory(handles, bus);
        return inventory == null ? InternalInventory.empty() : inventory;
    }

    @Override
    public BlockPos getBlockPos() {
        // A bus is a block entity, so its position is normally to hand. Falling back to the origin when it
        // is not (mid-teardown) keeps the terminal's fingerprint stable; dropping the host there would
        // reshuffle the identity of every other disk in the list.
        return bus instanceof BlockEntity entity ? entity.getBlockPos() : BlockPos.ZERO;
    }

    @Override
    public int getIdentitySalt() {
        // The origin fallback above is shared by every bus that is not a block entity, so identity has
        // to come from the bus instance itself.
        return System.identityHashCode(bus);
    }
}
