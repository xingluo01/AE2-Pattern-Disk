package io.github.lounode.ae2pattern.common.logic;

import java.util.function.Supplier;

import net.minecraft.world.item.ItemStack;

import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IManagedGridNode;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.util.inv.AppEngInternalInventory;

import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.pattern.PatternDiskContents;

/**
 * A {@link PatternProviderLogic} whose available patterns are expanded from the contents of inserted
 * pattern disks, instead of a fixed slot-per-pattern inventory.
 *
 * <p>We do NOT touch AE2's private {@code patterns}/{@code patternInputs} fields. Instead we write the
 * disk patterns into the parent's own {@code patternInventory} (via {@link #getPatternInv()}) and let the
 * parent's {@link #updatePatterns()} decode and register them. This stays within public API.</p>
 *
 * <p>The disk inventory is supplied lazily via a {@link Supplier} so this logic may be constructed during
 * the block-entity's parent constructor before the disk inventory field is initialized.</p>
 */
public class PatternDiskProviderLogic extends PatternProviderLogic {

    private final Supplier<AppEngInternalInventory> diskInventorySupplier;

    /** Fingerprint of the last rebuilt disk content; skips a full rebuild when unchanged. */
    private int lastDiskFingerprint;
    /** True once the first successful rebuild has settled the fingerprint. */
    private boolean fingerprintInitialized;

    public PatternDiskProviderLogic(IManagedGridNode mainNode, PatternProviderLogicHost host,
            Supplier<AppEngInternalInventory> diskInventorySupplier) {
        super(mainNode, host, 1024);
        this.diskInventorySupplier = diskInventorySupplier;
        this.lastDiskFingerprint = 0;
        this.fingerprintInitialized = false;
    }

    /**
     * Rebinds the parent's pattern inventory to the encoded patterns on all inserted disks, then lets the
     * parent rebuild its pattern list.
     *
     * <p>Strategy 2 (static resolver): recompute the disk fingerprint; if it is unchanged since the last
     * rebuild we short-circuit and skip the expensive {@code clear + rewrite + updatePatterns()} so a
     * burst of same-content invocations coalesces into a single rebuild. A content change (slot in/out or
     * disk repattern) rebuilds fresh in one pass.</p>
     */
    public boolean refreshPatternsFromDisks() {
        var diskInventory = diskInventorySupplier.get();
        if (diskInventory == null) {
            return false;
        }

        int fingerprint = computeFingerprint(diskInventory);
        if (fingerprintInitialized && fingerprint == lastDiskFingerprint) {
            return false; // unchanged: coalesce, skip full rebuild
        }

        InternalInventory patternInv = getPatternInv();
        if (patternInv == null) {
            return false;
        }

        java.util.List<ItemStack> all = new java.util.ArrayList<>();
        for (int i = 0; i < diskInventory.size(); i++) {
            ItemStack diskStack = diskInventory.getStackInSlot(i);
            if (diskStack.isEmpty() || !(diskStack.getItem() instanceof PatternDiskItem disk)) {
                continue;
            }
            PatternDiskContents contents = disk.contents(diskStack);
            all.addAll(contents.patterns());
        }

        patternInv.clear();
        for (int i = 0; i < all.size() && i < patternInv.size(); i++) {
            patternInv.setItemDirect(i, all.get(i));
        }

        // Parent decodes patternInventory into its patterns list and requests a grid update.
        updatePatterns();

        lastDiskFingerprint = fingerprint;
        fingerprintInitialized = true;
        return true;
    }

    /**
     * A content fingerprint over the disk inventory: each non-empty slot contributes its item id and its
     * component patch hash (container components). Slot indices are included so removing a disk at a
     * different position is treated as a change. Ignoring stack count keeps a disk with a burst of
     * insertions stable.
     */
    private static int computeFingerprint(AppEngInternalInventory diskInventory) {
        int hash = 1;
        for (int i = 0; i < diskInventory.size(); i++) {
            ItemStack stack = diskInventory.getStackInSlot(i);
            if (stack.isEmpty()) {
                hash = 31 * hash + (i + 1); // empty slot contributes its index
                continue;
            }
            int itemHash = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).hashCode();
            int componentsHash = stack.getComponentsPatch().hashCode();
            hash = 31 * hash + itemHash;
            hash = 31 * hash + componentsHash;
            hash = 31 * hash + (i + 1);
        }
        return hash;
    }

    // The parent's updatePatterns re-reads patternInventory; our refresh already filled it.
    @Override
    public void updatePatterns() {
        super.updatePatterns();
    }
}
