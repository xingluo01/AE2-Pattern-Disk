package io.github.lounode.ae2pattern.common.block.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import appeng.api.networking.IGrid;

/**
 * Registry for disk hosts that live outside this mod (integration-provided machines).
 *
 * <p>The pattern disk encoding terminal discovers disk slots by scanning grid machines for
 * {@link IPatternDiskHost}. Machines from other mods cannot implement that interface at compile
 * time, so an integration registers a {@link HostCollector} here and the terminal merges the
 * collected hosts into its disk list, exactly as if the machines implemented the interface.</p>
 *
 * <p>Collectors are called on the server thread while the terminal rebuilds its disk list; they must
 * not mutate world state.</p>
 */
public final class PatternDiskHostRegistry {

    /**
     * Supplies the extra disk hosts currently present on a grid. Implementations are expected to
     * return the live hosts every call (the terminal re-scans on fingerprint change).
     */
    @FunctionalInterface
    public interface HostCollector {
        List<IPatternDiskHost> collect(IGrid grid);
    }

    private static final List<HostCollector> COLLECTORS = new CopyOnWriteArrayList<>();

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration");

    private PatternDiskHostRegistry() {
    }

    /** Registers a collector. Safe to call from an integration's server-side initialisation. */
    public static void register(HostCollector collector) {
        COLLECTORS.add(collector);
    }

    /**
     * @return every extra disk host contributed for {@code grid}; empty when no integration is loaded
     */
    public static List<IPatternDiskHost> collectExtra(@Nullable IGrid grid) {
        if (grid == null || COLLECTORS.isEmpty()) {
            return List.of();
        }
        var hosts = new ArrayList<IPatternDiskHost>();
        for (var collector : COLLECTORS) {
            try {
                var collected = collector.collect(grid);
                if (collected != null) {
                    hosts.addAll(collected);
                }
            } catch (RuntimeException e) {
                // A broken integration collector must not break the terminal's disk list.
                LOGGER.warn("Disk host collector failed; skipping its hosts", e);
            }
        }
        return hosts;
    }
}
