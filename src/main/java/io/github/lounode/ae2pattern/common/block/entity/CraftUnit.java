package io.github.lounode.ae2pattern.common.block.entity;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;

import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;

/**
 * One independent execution unit of the {@link PatternDiskAssemblerBlockEntity}. Each unit owns a
 * 3x3 crafting grid plus an output slot, an optional manual encoded pattern, a progress counter and
 * an output push direction. Eight units run concurrently inside the machine.
 */
final class CraftUnit {

    /**
     * 3x3 crafting grid (slots 0-8) plus the output slot (index 9). Slots hold one item each, matching AE2's
     * molecular assembler: a grid slot designates a recipe ingredient, not a quantity.
     */
    final AppEngInternalInventory grid;
    /** Manual encoded pattern (slot 0); when populated this unit self-executes it (AE2-style). */
    final AppEngInternalInventory patternInv;
    // Persisted crafting container, reused across crafts (avoids per-craft object churn).
    final TransientCraftingContainer craftingInv;
    IMolecularAssemblerSupportedPattern plan = null;
    /** The manual pattern item that produced {@link #plan}, or empty for a provider-pushed plan. */
    ItemStack planSource = ItemStack.EMPTY;
    double progress = 0;
    Direction pushDirection = null;

    CraftUnit(InternalInventoryHost host) {
        this.grid = new AppEngInternalInventory(host, PatternDiskAssemblerBlockEntity.GRID_SIZE + 1, 1);
        this.patternInv = new AppEngInternalInventory(host, 1);
        var menu = new AbstractContainerMenu(null, 0) {
            @Override
            public net.minecraft.world.item.ItemStack quickMoveStack(net.minecraft.world.entity.player.Player p, int i) {
                return net.minecraft.world.item.ItemStack.EMPTY;
            }

            @Override
            public boolean stillValid(net.minecraft.world.entity.player.Player p) {
                return true;
            }
        };
        this.craftingInv = new TransientCraftingContainer(menu, 3, 3);
    }
}
