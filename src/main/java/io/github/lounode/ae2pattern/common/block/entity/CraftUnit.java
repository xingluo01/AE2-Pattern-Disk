package io.github.lounode.ae2pattern.common.block.entity;

import net.minecraft.core.Direction;
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

    final AppEngInternalInventory grid = new AppEngInternalInventory(PatternDiskAssemblerBlockEntity.GRID_SIZE + 1);
    /** Manual encoded pattern (slot 0); when populated this unit self-executes it (AE2-style). */
    final AppEngInternalInventory patternInv;
    // Persisted crafting container, reused across crafts (avoids per-craft object churn).
    final TransientCraftingContainer craftingInv;
    IMolecularAssemblerSupportedPattern plan = null;
    double progress = 0;
    Direction pushDirection = null;

    CraftUnit(InternalInventoryHost host) {
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
