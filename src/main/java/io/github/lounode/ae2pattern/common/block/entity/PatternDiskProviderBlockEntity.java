package io.github.lounode.ae2pattern.common.block.entity;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.AEItemKey;
import appeng.blockentity.crafting.PatternProviderBlockEntity;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;

import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.pattern.PatternDiskInventoryAdapter;

import io.github.lounode.ae2pattern.common.logic.PatternDiskProviderLogic;
import io.github.lounode.ae2pattern.core.AEPatternBlockEntities;
import io.github.lounode.ae2pattern.core.AEPatternItems;

/**
 * Block entity for the pattern disk provider: holds up to {@link #DISK_SLOT_COUNT} pattern disks and
 * exposes every encoded pattern on them to the ME autocrafting service.
 */
public class PatternDiskProviderBlockEntity extends PatternProviderBlockEntity implements InternalInventoryHost {

    public static final int DISK_SLOT_COUNT = 9;

    private final AppEngInternalInventory diskInventory = new AppEngInternalInventory(this, DISK_SLOT_COUNT);

    /** Cached terminal view; rebuilt only when the disk fingerprint actually changes. */
    private InternalInventory cachedTerminalInventory;

    public PatternDiskProviderBlockEntity(BlockPos pos, BlockState blockState) {
        super(AEPatternBlockEntities.PATTERN_DISK_PROVIDER.get(), pos, blockState);
    }

    @Override
    protected PatternProviderLogic createLogic() {
        // Lazy supplier: the block entity's parent constructor calls createLogic() before the
        // diskInventory field is initialized, so defer its access until refresh time.
        return new PatternDiskProviderLogic(getMainNode(), this, this::getDiskInventory);
    }

    public AppEngInternalInventory getDiskInventory() {
        return diskInventory;
    }

    /**
     * Terminal view: exposes every encoded pattern stored across all inserted disks so AE2 pattern
     * access terminals can enumerate and display them. The view is cached and only rebuilt when the
     * disk fingerprint actually changes (led by {@link #refreshFromDisks()}).
     */
    @Override
    public appeng.api.inventories.InternalInventory getTerminalPatternInventory() {
        if (cachedTerminalInventory != null) {
            return cachedTerminalInventory;
        }
        var all = new java.util.ArrayList<net.minecraft.world.item.ItemStack>();
        for (int i = 0; i < diskInventory.size(); i++) {
            var stack = diskInventory.getStackInSlot(i);
            if (!stack.isEmpty() && stack.getItem() instanceof PatternDiskItem disk) {
                all.addAll(disk.contents(stack).patterns());
            }
        }
        cachedTerminalInventory = new PatternDiskInventoryAdapter(all);
        return cachedTerminalInventory;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        refreshFromDisks();
    }

    /**
     * Rebuilds the provider's pattern list from current disk contents.
     */
    public void refreshFromDisks() {
        boolean rebuilt = false;
        if (getLogic() instanceof PatternDiskProviderLogic diskLogic) {
            rebuilt = diskLogic.refreshPatternsFromDisks();
        }
        if (rebuilt) {
            cachedTerminalInventory = null; // invalidate cached terminal view
        }
    }

    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        if (inv == diskInventory) {
            refreshFromDisks();
            saveChanges();
        }
    }

    @Override
    public void saveChangedInventory(AppEngInternalInventory inv) {
        saveChanges();
    }

    @Override
    public boolean isClientSide() {
        return level != null && level.isClientSide();
    }

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        diskInventory.writeToNBT(tag, "disks", registries);
    }

    @Override
    public void loadTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadTag(tag, registries);
        diskInventory.readFromNBT(tag, "disks", registries);
        refreshFromDisks();
    }

    @Override
    public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        // Do NOT call super.addAdditionalDrops: the parent would drop the injected patternInventory
        // (the encoded patterns copied out of disks into the provider logic), which is not the source
        // of truth. Only the actual disk items should drop.
        for (int i = 0; i < diskInventory.size(); i++) {
            if (!diskInventory.getStackInSlot(i).isEmpty()) {
                drops.add(diskInventory.getStackInSlot(i));
            }
        }
        // Also clear the injected pattern inventory so nothing stale remains on break.
        clearContent();
    }

    @Override
    public void clearContent() {
        super.clearContent();
        diskInventory.clear();
        cachedTerminalInventory = null;
    }

    @Override
    public AEItemKey getTerminalIcon() {
        return AEItemKey.of(AEPatternItems.PATTERN_DISK_PROVIDER.get());
    }

    @Override
    public ItemStack getMainMenuIcon() {
        return new ItemStack(AEPatternItems.PATTERN_DISK_PROVIDER.get());
    }
}
