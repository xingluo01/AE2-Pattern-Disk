package io.github.lounode.ae2pattern.integration.extendedae_plus;

import net.neoforged.fml.ModList;

/**
 * Integration shim for ExtendedAE Plus ({@code extendedae_plus}).
 *
 * <p>That mod's "upload pattern to a provider" button looks for an empty row in a provider's pattern
 * inventory and then writes into it. A disk-backed provider has no rows of its own to write into, so
 * {@code PatternDiskRemoveInventory} answers the write and lands it on a disk; this class holds what belongs
 * to the other mod rather than to that view: its mod id, the presence probe, the terminal-contract probe (the
 * two interfaces our adapter subclasses implement only exist in builds of that mod that carry them), and the
 * rule that decides whether a free row is worth advertising.</p>
 *
 * <p>Its exit condition is ExtendedAE Plus delegating disk-backed providers to a write API of their own
 * instead of the generic terminal inventory: once that lands, this package goes away.</p>
 */
public final class ExtendedAEPlusCompat {

    /** Mod id of the uploader. Product code never references its classes, only this string. */
    public static final String MOD_ID = "extendedae_plus";

    /**
     * Marker for the builds of that mod that carry its third-party terminal upload contract. It exists on its
     * 1.21.1 branch so far (issue #154), not in the release on Modrinth, hence a class probe rather than a
     * version comparison.
     */
    private static final String UPLOAD_CONTRACT_CLASS = "com.extendedae_plus.api.upload.IPatternUploadMenu";

    /** Resolved once, and only once the mod list is there to ask. */
    private static Boolean present;

    private static Boolean uploadContract;

    private ExtendedAEPlusCompat() {
    }

    /** @return whether ExtendedAE Plus is installed. */
    public static boolean isPresent() {
        if (present == null) {
            try {
                // Only a real answer is cached: the class can initialize before the mod list exists, and
                // caching that "absent" would silently disable uploads for the rest of the session.
                present = ModList.get().isLoaded(MOD_ID);
            } catch (Throwable ignored) {
                return false;
            }
        }
        return present;
    }

    /**
     * Whether the installed ExtendedAE Plus carries the terminal upload contract. Our two adapter subclasses
     * implement its interfaces, so instantiating them against a build without those classes would end in
     * {@code NoClassDefFoundError} - probe by class, not by version, because version strings drift and the
     * release carrying the contract is not out yet.
     */
    public static boolean hasUploadContract() {
        if (uploadContract == null) {
            if (!isPresent()) {
                return false;
            }
            try {
                Class.forName(UPLOAD_CONTRACT_CLASS, false, ExtendedAEPlusCompat.class.getClassLoader());
                uploadContract = true;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return uploadContract;
    }

    /**
     * Whether a provider view should advertise a free row to that mod's row scan.
     *
     * <p>A view that cannot resolve a level can never accept an upload - decoding a pattern needs one -
     * and a view whose disks are all full has nothing to offer the caller.</p>
     *
     * @param hasLevelSupplier whether the view can resolve a level at all
     * @param freeCapacity     total free pattern slots across the view's disks
     */
    public static boolean wantsFreeRow(boolean hasLevelSupplier, int freeCapacity) {
        return hasLevelSupplier && isPresent() && freeCapacity > 0;
    }
}
