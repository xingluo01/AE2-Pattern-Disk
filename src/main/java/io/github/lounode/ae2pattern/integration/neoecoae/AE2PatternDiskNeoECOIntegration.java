package io.github.lounode.ae2pattern.integration.neoecoae;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.core.definitions.AEItems;
import appeng.util.inv.AppEngInternalInventory;

import cn.dancingsnow.neoecoae.api.IECOPatternStorage;
import cn.dancingsnow.neoecoae.api.integration.Integration;

import io.github.lounode.ae2pattern.common.block.entity.IPatternDiskHost;
import io.github.lounode.ae2pattern.common.block.entity.PatternDiskHostRegistry;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.pattern.CompositePatternInventory;
import io.github.lounode.ae2pattern.common.pattern.PatternClassifier;
import io.github.lounode.ae2pattern.common.pattern.PatternDiskRemoveInventory;

/**
 * Integration entry point for NEO ECO AE Extension ({@code neoecoae}).
 *
 * <p>Discovered by NEO ECO's {@code IntegrationManager} at startup through
 * {@code @Integration("ae2_pattern_disk")}; the manager calls {@link #apply()} server-side. Every hook
 * is installed reflectively because the FD Smart Pattern Bus block entity references LDLib, which this
 * mod deliberately keeps off its compile classpath.</p>
 *
 * <h3>What this integration installs</h3>
 * <ul>
 *   <li><b>extraInsertFilter</b> — lets pattern disks into the bus's slot inventory.</li>
 *   <li><b>diskSpaceHook</b> — reports whether a bus currently has a disk with room, so the grid's
 *       pattern storage can prefer disks over arbitrary slots (see {@code PatternStorage}).</li>
 *   <li><b>diskInsertHook</b> — routes an uploaded pattern into a disk instead of a slot.</li>
 *   <li><b>patternDiscoveryHook</b> — publishes disk-stored patterns to autocrafting.</li>
 *   <li><b>terminalInventoryHook</b> — exposes slot patterns and disk patterns to the pattern access
 *       terminal as one flat view.</li>
 *   <li><b>PatternDiskHostRegistry</b> collector — makes the bus's disks visible to the pattern disk
 *       encoding terminal's disk list, like the disk provider and batch assembler.</li>
 * </ul>
 */
@Integration("ae2_pattern_disk")
public class AE2PatternDiskNeoECOIntegration {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.neoecoae");

    private static final String BUS_CLASS =
            "cn.dancingsnow.neoecoae.blocks.entity.crafting.ECOCraftingPatternBusBlockEntity";

    /**
     * Server-side initialisation. Called by NEO ECO's {@code IntegrationManager} when the
     * {@code neoecoae} mod is present and loaded.
     */
    public void apply() {
        final Class<?> busClass;
        try {
            busClass = Class.forName(BUS_CLASS);
        } catch (ClassNotFoundException e) {
            LOGGER.info("[AE2-Pattern-Disk] Neo ECO FD Smart Pattern Bus not present; integration skipped");
            return;
        }

        final Method getPatternInventory;
        try {
            getPatternInventory = busClass.getMethod("getPatternInventory");
        } catch (NoSuchMethodException e) {
            // NEO ECO older than the disk-aware API: nothing can be wired, but the terminal still works.
            LOGGER.warn("[AE2-Pattern-Disk] FD Smart Pattern Bus lacks getPatternInventory; integration skipped");
            return;
        }

        installExtraInsertFilter(busClass);
        installDiskSpaceHook(busClass);
        installDiskInsertHook(busClass, getPatternInventory);
        installPatternDiscoveryHook(busClass, getPatternInventory);
        installTerminalInventoryHook(busClass, getPatternInventory);
        installHostCollector(busClass, getPatternInventory);
    }

    /** Client-side initialisation: the terminal screen adds its upload button on its own. */
    public void applyClient() {
    }

    // ---- hook installation --------------------------------------------------

    private void installExtraInsertFilter(Class<?> busClass) {
        try {
            var setter = busClass.getMethod("setExtraInsertFilter", BiPredicate.class);
            BiPredicate<ItemStack, Level> acceptsDisks = (stack, level) -> stack.getItem() instanceof PatternDiskItem;
            setter.invoke(null, acceptsDisks);
            LOGGER.info("[AE2-Pattern-Disk] Registered extraInsertFilter (accepts PatternDiskItem)");
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[AE2-Pattern-Disk] extraInsertFilter unavailable", e);
        }
    }

    private void installDiskSpaceHook(Class<?> busClass) {
        try {
            var setter = busClass.getMethod("setDiskSpaceHook", BiPredicate.class);
            BiPredicate<Object, ItemStack> hasDiskRoom = (bus, pattern) -> {
                var level = levelOf(bus);
                if (level == null) {
                    return false;
                }
                var inv = rawInventoryOf(bus, busClass);
                if (inv == null) {
                    return false;
                }
                for (int i = 0; i < inv.size(); i++) {
                    var stack = inv.getStackInSlot(i);
                    if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem disk)) {
                        continue;
                    }
                    // Room, locked type and same-result exclusion all live in PatternDiskItem.canInsert,
                    // so the upload path cannot drift from the terminal/transferer rules.
                    if (disk.canInsert(stack, pattern, level)) {
                        return true;
                    }
                }
                return false;
            };
            setter.invoke(null, hasDiskRoom);
            LOGGER.info("[AE2-Pattern-Disk] Registered diskSpaceHook (disks preferred grid-wide)");
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[AE2-Pattern-Disk] diskSpaceHook unavailable", e);
        }
    }

    private void installDiskInsertHook(Class<?> busClass, Method getPatternInventory) {
        try {
            var hookClass = Class.forName(BUS_CLASS + "$DiskInsertHook");
            var setter = busClass.getMethod("setDiskInsertHook", hookClass);
            Object hook = java.lang.reflect.Proxy.newProxyInstance(
                    hookClass.getClassLoader(),
                    new Class<?>[] { hookClass },
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return handleObjectMethod(proxy, method, args);
                        }
                        if (!"tryInsert".equals(method.getName())) {
                            return false;
                        }
                        var bus = args[0];
                        var pattern = (ItemStack) args[1];
                        var level = levelOf(bus);
                        var inv = inventoryOf(getPatternInventory, bus);
                        if (level == null || inv == null) {
                            return false;
                        }
                        for (int i = 0; i < inv.size(); i++) {
                            var stack = inv.getStackInSlot(i);
                            if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem disk)) {
                                continue;
                            }
                            // tryInsert enforces room, locked type and same-result exclusion, and
                            // mutates the stack in place; write it back to the slot.
                            if (disk.tryInsert(stack, pattern, level)) {
                                inv.setItemDirect(i, stack);
                                return true;
                            }
                        }
                        return false;
                    });
            setter.invoke(null, hook);
            LOGGER.info("[AE2-Pattern-Disk] Registered DiskInsertHook on FD Smart Pattern Bus");
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[AE2-Pattern-Disk] DiskInsertHook unavailable", e);
        }
    }

    private void installPatternDiscoveryHook(Class<?> busClass, Method getPatternInventory) {
        try {
            var hookClass = Class.forName(BUS_CLASS + "$PatternDiscoveryHook");
            var setter = busClass.getMethod("setPatternDiscoveryHook", hookClass);
            Object hook = java.lang.reflect.Proxy.newProxyInstance(
                    hookClass.getClassLoader(),
                    new Class<?>[] { hookClass },
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return handleObjectMethod(proxy, method, args);
                        }
                        if (!"discover".equals(method.getName())) {
                            return List.of();
                        }
                        var bus = args[0];
                        var level = levelOf(bus);
                        var inv = inventoryOf(getPatternInventory, bus);
                        if (level == null || inv == null) {
                            return List.of();
                        }
                        var found = new ArrayList<IPatternDetails>();
                        for (int i = 0; i < inv.size(); i++) {
                            var stack = inv.getStackInSlot(i);
                            if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem disk)) {
                                continue;
                            }
                            for (var encoded : disk.contents(stack).patterns()) {
                                var details = PatternDetailsHelper.decodePattern(encoded, level);
                                if (details != null) {
                                    found.add(details);
                                }
                            }
                        }
                        return found;
                    });
            setter.invoke(null, hook);
            LOGGER.info("[AE2-Pattern-Disk] Registered PatternDiscoveryHook on FD Smart Pattern Bus");
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[AE2-Pattern-Disk] PatternDiscoveryHook unavailable", e);
        }
    }

    private void installTerminalInventoryHook(Class<?> busClass, Method getPatternInventory) {
        // The bus allocates every page's slots up front; only getPatternSlotCount() of them are
        // actually visible. Clipping matters: AE2 unfolds the terminal view by size(), so the raw
        // count would render hundreds of phantom rows and multiply the per-slot lookups.
        Method getPatternSlotCount = null;
        try {
            getPatternSlotCount = busClass.getMethod("getPatternSlotCount");
        } catch (NoSuchMethodException e) {
            LOGGER.warn("[AE2-Pattern-Disk] FD Smart Pattern Bus lacks getPatternSlotCount; using raw size");
        }
        final Method slotCount = getPatternSlotCount;

        try {
            var setter = busClass.getMethod("setTerminalInventoryHook", Function.class);
            Function<Object, InternalInventory> view =
                    bus -> terminalView(bus, getPatternInventory, slotCount);
            setter.invoke(null, view);
            LOGGER.info("[AE2-Pattern-Disk] Registered terminalInventoryHook (pattern access terminal)");
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[AE2-Pattern-Disk] terminalInventoryHook unavailable", e);
        }
    }

    /**
     * Terminal views, reused within a single game tick. AE2's pattern access terminal asks for the
     * inventory twice per slot while filtering containers ({@code PatternAccessTermMenu.isFull}), so a
     * fully occupied bus in NOT_FULL mode would otherwise rebuild the view millions of times per tick.
     * Reuse ends with the tick, and a disk mutation drops the entry outright, so a view with holey rows
     * can never be handed to a later session.
     */
    private static final Map<Object, CachedTerminalView> TERMINAL_VIEWS =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private record CachedTerminalView(InternalInventory view, long tick) {
    }

    private static InternalInventory terminalView(Object bus, Method getPatternInventory, Method getPatternSlotCount) {
        long tick = gameTimeOf(bus);
        var cached = TERMINAL_VIEWS.get(bus);
        if (cached != null && cached.tick() == tick) {
            return cached.view();
        }
        var raw = inventoryOf(getPatternInventory, bus);
        if (raw == null) {
            return null;
        }
        var visible = visibleSlots(getPatternSlotCount, bus, raw.size());
        var clipped = CompositePatternInventory.limited(raw, visible);
        // Disk patterns (removal draws a blank pattern from the ME network) plus the bus's ordinary
        // slot patterns, minus the disks themselves. A real removal invalidates the cache so the next
        // caller (and the next session) re-scans the disks instead of reusing a view with holey rows.
        var diskPatterns = new PatternDiskRemoveInventory(clipped, blankPatternSink(bus), () -> TERMINAL_VIEWS.remove(bus));
        var slotPatterns = CompositePatternInventory.filtering(
                clipped, stack -> !(stack.getItem() instanceof PatternDiskItem));
        var built = CompositePatternInventory.of(slotPatterns, diskPatterns);
        if (TERMINAL_VIEWS.size() > MAX_CACHED_TERMINAL_VIEWS) {
            TERMINAL_VIEWS.clear();
        }
        TERMINAL_VIEWS.put(bus, new CachedTerminalView(built, tick));
        return built;
    }

    private static final int MAX_CACHED_TERMINAL_VIEWS = 128;

    /** @return the bus's level game time, or {@code Long.MIN_VALUE} when unavailable (caching disabled) */
    private static long gameTimeOf(Object bus) {
        var level = levelOf(bus);
        return level instanceof net.minecraft.server.level.ServerLevel serverLevel
                ? serverLevel.getGameTime()
                : Long.MIN_VALUE;
    }

    private static int visibleSlots(@Nullable Method getPatternSlotCount, Object bus, int fallback) {
        if (getPatternSlotCount == null) {
            return fallback;
        }
        try {
            var count = (Integer) getPatternSlotCount.invoke(bus);
            return count == null || count <= 0 ? fallback : count;
        } catch (ReflectiveOperationException e) {
            return fallback;
        }
    }

    private void installHostCollector(Class<?> busClass, Method getPatternInventory) {
        PatternDiskHostRegistry.register(grid -> {
            var hosts = new ArrayList<IPatternDiskHost>();
            for (var node : grid.getNodes()) {
                var storage = node.getService(IECOPatternStorage.class);
                if (storage == null || !busClass.isInstance(storage)) {
                    continue;
                }
                hosts.add(new BusDiskHost(storage, getPatternInventory));
            }
            return hosts;
        });
        LOGGER.info("[AE2-Pattern-Disk] Registered PatternDiskHostRegistry collector (encoding terminal disk list)");
    }

    /**
     * Answers the {@link Object} methods a proxy may receive (identity, equality, logging) so a hook
     * object behaves sanely if it is ever printed or put into a collection.
     */
    private static Object handleObjectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "ae2_pattern_disk:neoecoae_hook";
            default -> null;
        };
    }

    // ---- helpers ------------------------------------------------------------

    /** Draws/returns blank patterns for the disk removal protocol out of the bus's ME network. */
    private static PatternDiskRemoveInventory.BlankPatternSink blankPatternSink(Object bus) {
        return new PatternDiskRemoveInventory.BlankPatternSink() {
            @Override
            public boolean drawBlankPatterns(int count) {
                return moveBlankPatterns(bus, count, true);
            }

            @Override
            public boolean hasBlankPatterns(int count) {
                var grid = gridOf(bus);
                if (grid == null || count <= 0) {
                    return false;
                }
                var blank = AEItemKey.of(AEItems.BLANK_PATTERN);
                return grid.getStorageService().getInventory()
                        .extract(blank, count, Actionable.SIMULATE, IActionSource.empty()) == count;
            }

            @Override
            public boolean returnBlankPatterns(int count) {
                return moveBlankPatterns(bus, count, false);
            }
        };
    }

    private static boolean moveBlankPatterns(Object bus, int count, boolean extract) {
        var grid = gridOf(bus);
        if (grid == null || count <= 0) {
            return false;
        }
        var storage = grid.getStorageService().getInventory();
        var blank = AEItemKey.of(AEItems.BLANK_PATTERN);
        if (extract) {
            return storage.extract(blank, count, Actionable.MODULATE, IActionSource.empty()) == count;
        }
        return storage.insert(blank, count, Actionable.MODULATE, IActionSource.empty()) == 0;
    }

    @Nullable
    private static InternalInventory inventoryOf(Method getPatternInventory, Object bus) {
        try {
            return (InternalInventory) getPatternInventory.invoke(bus);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @Nullable
    private static AppEngInternalInventory rawInventoryOf(Object bus, Class<?> busClass) {
        try {
            return (AppEngInternalInventory) busClass.getMethod("getPatternInventory").invoke(bus);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @Nullable
    private static Level levelOf(Object bus) {
        try {
            return (Level) bus.getClass().getMethod("getLevel").invoke(bus);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @Nullable
    private static IGrid gridOf(Object bus) {
        try {
            return (IGrid) bus.getClass().getMethod("getGrid").invoke(bus);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * Adapts the FD Smart Pattern Bus to {@link IPatternDiskHost} so the pattern disk encoding
     * terminal lists its disks exactly like the disk provider's and batch assembler's.
     */
    private static final class BusDiskHost implements IPatternDiskHost {

        private final Object bus;
        private final Method getPatternInventory;

        private BusDiskHost(Object bus, Method getPatternInventory) {
            this.bus = bus;
            this.getPatternInventory = getPatternInventory;
        }

        @Override
        public AppEngInternalInventory getDiskInventory() {
            try {
                return (AppEngInternalInventory) getPatternInventory.invoke(bus);
            } catch (ReflectiveOperationException e) {
                return new AppEngInternalInventory(0);
            }
        }

        @Override
        public BlockPos getBlockPos() {
            try {
                return (BlockPos) bus.getClass().getMethod("getBlockPos").invoke(bus);
            } catch (ReflectiveOperationException e) {
                return BlockPos.ZERO;
            }
        }
    }
}
