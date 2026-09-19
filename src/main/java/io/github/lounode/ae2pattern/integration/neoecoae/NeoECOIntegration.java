package io.github.lounode.ae2pattern.integration.neoecoae;

import java.lang.reflect.Method;
import java.util.List;

import appeng.api.networking.IGrid;

import io.github.lounode.ae2pattern.api.IPatternDiskHost;
import io.github.lounode.ae2pattern.common.block.entity.PatternDiskHostRegistry;
import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.dancingsnow.neoecoae.api.integration.Integration;

/**
 * Integration entry point for NEO ECO AE Extension ({@code neoecoae}).
 *
 * <p>NEO ECO's {@code IntegrationManager} discovers this class at startup through
 * {@code @Integration("ae2_pattern_disk")} and calls {@link #apply()} server-side. The class stays a thin
 * assembler: it resolves the bus, checks that the auxiliary pattern store API is present, and hands the
 * work to the neighbouring classes. {@link NeoECOBusAccess} holds the reflection boundary,
 * {@link NeoECOBusDisks} the disk operations, {@link NeoECOAuxiliaryStore} the store NEO ECO calls back
 * into.</p>
 *
 * <h2>What the bus does with the store</h2>
 *
 * <ul>
 *   <li>recognises pattern disks in its slot filter, so they can be placed into a bus at all;</li>
 *   <li>routes an uploaded pattern to a disk ahead of its slot inventory, and keeps a bus whose slots
 *       are all full in the network's writable set while a disk still has room;</li>
 *   <li>advertises the patterns held on disks to autocrafting, alongside the slot-held ones;</li>
 *   <li>counts them in the network-wide pattern index, so an upload of a pattern that already sits on a
 *       disk is recognised instead of duplicated, and keeps them out of the slot index, where one disk
 *       would otherwise look like one unsupported pattern.</li>
 * </ul>
 *
 * <p>This targets the NEO ECO {@code v21.1.2} line, whose bus exposes
 * {@code setAuxiliaryPatternStore} and {@code getTerminalPatternInventory}. On an older build every
 * lookup fails and the integration logs why it stood down; the rest of the mod is unaffected.</p>
 */
@Integration("ae2_pattern_disk")
public class NeoECOIntegration {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.neoecoae");

    /** Server-side initialisation, called once NEO ECO has loaded and this mod is present. */
    public void apply() {
        Class<?> busClass = NeoECOBusAccess.findClass(NeoECOBusAccess.BUS_CLASS);
        if (busClass == null) {
            LOGGER.info("[AE2-Pattern-Disk] Neo ECO FD Smart Pattern Bus not present; integration skipped");
            return;
        }

        Class<?> storeInterface = NeoECOBusAccess.findClass(NeoECOBusAccess.AUXILIARY_STORE_CLASS);
        if (storeInterface == null) {
            LOGGER.warn("[AE2-Pattern-Disk] NEO ECO has no AuxiliaryPatternStore API, so pattern disks "
                    + "cannot be served by the FD Smart Pattern Bus; integration skipped");
            return;
        }

        Method setStore = NeoECOBusAccess.method(busClass, "setAuxiliaryPatternStore", storeInterface);
        if (setStore == null) {
            LOGGER.warn("[AE2-Pattern-Disk] FD Smart Pattern Bus lacks setAuxiliaryPatternStore; integration skipped");
            return;
        }

        Method getPatternInventory = NeoECOBusAccess.method(busClass, "getPatternSlotInventory");
        if (getPatternInventory == null) {
            // Older builds exposed the raw slots under the terminal's method name. The two were split so the
            // terminal's display view - which hides disks and appends their recipes - could stop being the
            // thing the disk scan and the slot accessors read.
            getPatternInventory = NeoECOBusAccess.method(busClass, "getTerminalPatternInventory");
        }
        Method getLevel = NeoECOBusAccess.method(busClass, "getLevel");
        if (getPatternInventory == null || getLevel == null) {
            LOGGER.warn("[AE2-Pattern-Disk] FD Smart Pattern Bus lacks getPatternSlotInventory/getLevel; "
                    + "integration skipped");
            return;
        }

        var handles = new NeoECOBusAccess.BusHandles(getPatternInventory, getLevel);
        Object store = NeoECOAuxiliaryStore.create(storeInterface, handles);
        if (store == null) {
            LOGGER.warn("[AE2-Pattern-Disk] NEO ECO pattern-insertion results are unusable; integration skipped");
            return;
        }

        try {
            setStore.invoke(null, store);
            LOGGER.info("[AE2-Pattern-Disk] Registered the auxiliary pattern store "
                    + "(pattern disks served by the FD Smart Pattern Bus)");
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[AE2-Pattern-Disk] auxiliary pattern store could not be registered", e);
        }

        // The disks also have to reach the encoding terminal. That terminal discovers disk slots by
        // scanning grid machines for IPatternDiskHost, which a machine from another mod cannot implement,
        // so the bus has to be handed over through the registry instead.
        PatternDiskHostRegistry.register(grid -> hostsOn(grid, busClass, handles));
        LOGGER.info("[AE2-Pattern-Disk] FD Smart Pattern Bus disks are now listed in the encoding terminal");

        // And the pattern access terminal gets a view of the bus in which the disks' recipes can be taken,
        // rather than the bus's own view in which they are only listed. Without this the listing is accurate
        // but inert: a recipe could be read off the terminal and not taken out of the disk it came from.
        registerTerminalView(busClass, handles);

        // Upload button for the encoding terminal: wire up the pattern-storage upload so that clicking the
        // NEO ECO button in the terminal screen calls into the disk's storage service.
        PatternDiskEncodingTermMenu.uploadHandler = NeoECOUploadHandler.create();
        LOGGER.info("[AE2-Pattern-Disk] Encoding terminal upload button wired");
    }

    /**
     * Hands the bus a terminal view that serves its disks, when the bus build offers the hook for it.
     *
     * <p>Missing the hook is not an error: the bus then keeps describing the disks itself, which loses the
     * interaction and nothing else.</p>
     */
    private static void registerTerminalView(Class<?> busClass, NeoECOBusAccess.BusHandles handles) {
        Class<?> hookInterface = NeoECOBusAccess.findClass(busClass.getName() + "$TerminalInventoryHook");
        Method setHook = hookInterface == null ? null
                : NeoECOBusAccess.method(busClass, "setTerminalInventoryHook", hookInterface);
        if (hookInterface == null || setHook == null) {
            LOGGER.info("[AE2-Pattern-Disk] FD Smart Pattern Bus has no terminal view hook; the disks' recipes "
                    + "stay listed but cannot be taken");
            return;
        }
        Object handler = NeoECOBusTerminalHook.create(hookInterface, handles);
        if (handler == null) {
            LOGGER.warn("[AE2-Pattern-Disk] terminal view hook could not be built; disks' recipes stay listed "
                    + "but cannot be taken");
            return;
        }
        try {
            setHook.invoke(null, handler);
            LOGGER.info("[AE2-Pattern-Disk] FD Smart Pattern Bus terminal view now serves the disks "
                    + "(taking a recipe draws a blank pattern from the network)");
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[AE2-Pattern-Disk] terminal view hook could not be registered", e);
        }
    }

    /** @return every FD Smart Pattern Bus on {@code grid}, presented as a disk host. */
    private static List<IPatternDiskHost> hostsOn(IGrid grid, Class<?> busClass,
            NeoECOBusAccess.BusHandles handles) {
        var hosts = new java.util.ArrayList<IPatternDiskHost>();
        for (var machineClass : grid.getMachineClasses()) {
            if (machineClass == null || !machineClass.isAssignableFrom(busClass)) {
                continue;
            }
            for (var machine : grid.getActiveMachines(machineClass)) {
                if (busClass.isInstance(machine)) {
                    hosts.add(new NeoECODiskHost(machine, handles));
                }
            }
        }
        return hosts;
    }

    /** Client-side initialisation: the terminal screen adds its upload button on its own. */
    public void applyClient() {
    }
}
