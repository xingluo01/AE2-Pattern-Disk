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

    public PatternDiskProviderLogic(IManagedGridNode mainNode, PatternProviderLogicHost host,
            Supplier<AppEngInternalInventory> diskInventorySupplier) {
        super(mainNode, host, 1024);
        this.diskInventorySupplier = diskInventorySupplier;
    }

    /**
     * Rebinds the parent's pattern inventory to the encoded patterns on all inserted disks, then lets the
     * parent rebuild its pattern list.
     */
    public void refreshPatternsFromDisks() {
        var diskInventory = diskInventorySupplier.get();
        if (diskInventory == null) {
            return;
        }

        InternalInventory patternInv = getPatternInv();
        if (patternInv == null) {
            return;
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
    }

    // The parent's updatePatterns re-reads patternInventory; our refresh already filled it.
    @Override
    public void updatePatterns() {
        super.updatePatterns();
    }
}
