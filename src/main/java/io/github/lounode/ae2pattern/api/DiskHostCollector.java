package io.github.lounode.ae2pattern.api;

import java.util.List;

import appeng.api.networking.IGrid;

/**
 * Supplies the disk hosts a machine from another mod provides, so this mod's disk encoding terminal can
 * list the disks in them.
 *
 * <p>A machine from another mod cannot implement {@link IPatternDiskHost} at compile time unless it
 * depends on this mod, so the alternative is to register a collector here: it returns the live hosts and
 * the terminal treats them exactly as if the machines had implemented the interface.</p>
 *
 * <p>Collectors are called on the server thread while the terminal rebuilds its disk list. They must not
 * mutate world state, and should return the hosts as they are at that moment.</p>
 */
@FunctionalInterface
public interface DiskHostCollector {

    /**
     * @return the disk hosts currently present on {@code grid}; may be empty, and a throw from here is
     *         contained by the registry rather than breaking the terminal's disk list
     */
    List<IPatternDiskHost> collect(IGrid grid);
}
