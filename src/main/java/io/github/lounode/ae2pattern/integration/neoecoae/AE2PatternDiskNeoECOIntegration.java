package io.github.lounode.ae2pattern.integration.neoecoae;

import java.lang.reflect.Method;

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
public class AE2PatternDiskNeoECOIntegration {

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

        Method getPatternInventory = NeoECOBusAccess.method(busClass, "getTerminalPatternInventory");
        Method getLevel = NeoECOBusAccess.method(busClass, "getLevel");
        if (getPatternInventory == null || getLevel == null) {
            LOGGER.warn("[AE2-Pattern-Disk] FD Smart Pattern Bus lacks getTerminalPatternInventory/getLevel; "
                    + "integration skipped");
            return;
        }

        Object store = NeoECOAuxiliaryStore.create(storeInterface,
                new NeoECOBusAccess.BusHandles(getPatternInventory, getLevel));
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
    }

    /** Client-side initialisation: the terminal screen adds its upload button on its own. */
    public void applyClient() {
    }
}
