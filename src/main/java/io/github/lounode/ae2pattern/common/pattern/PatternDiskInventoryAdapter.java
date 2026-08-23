package io.github.lounode.ae2pattern.common.pattern;

import java.util.List;

import net.minecraft.world.item.ItemStack;

import appeng.api.inventories.InternalInventory;

/**
 * Read-only {@link InternalInventory} view over the encoded patterns stored on a pattern disk.
 * Exposes each stored pattern as one slot so AE2 pattern access terminals (and similar UIs) can
 * enumerate and display the disk's contents.
 */
public class PatternDiskInventoryAdapter implements InternalInventory {

    private final List<ItemStack> patterns;

    public PatternDiskInventoryAdapter(List<ItemStack> patterns) {
        this.patterns = patterns;
    }

    @Override
    public int size() {
        return patterns.size();
    }

    @Override
    public ItemStack getStackInSlot(int index) {
        if (index < 0 || index >= patterns.size()) {
            return ItemStack.EMPTY;
        }
        return patterns.get(index);
    }

    @Override
    public void setItemDirect(int index, ItemStack stack) {
        // Read-only view: disk contents are immutable from the terminal side.
    }
}
