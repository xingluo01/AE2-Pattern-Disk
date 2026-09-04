package io.github.lounode.ae2pattern.common.block.entity;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import appeng.api.inventories.InternalInventory;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.blockentity.crafting.PatternProviderBlockEntity;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;

import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.pattern.PatternDiskRemoveInventory;

import io.github.lounode.ae2pattern.common.logic.PatternDiskProviderLogic;
import io.github.lounode.ae2pattern.AEPatternRegistries;

/**
 * Block entity for the pattern disk provider: holds up to {@link #DISK_SLOT_COUNT} pattern disks and
 * exposes every encoded pattern on them to the ME autocrafting service.
 */
public class PatternDiskProviderBlockEntity extends PatternProviderBlockEntity implements InternalInventoryHost {

    public static final int DISK_SLOT_COUNT = 9;

    private final AppEngInternalInventory diskInventory = new AppEngInternalInventory(this, DISK_SLOT_COUNT);

    /**
     * Cached terminal view for the next PAT session that opens. AE2's PAT keeps a fixed row count per
     * open session, so an open PAT keeps using the instance it grabbed at open time (its rows freeze,
     * removals mark rows empty in place). Any disk change invalidates this cache so the next PAT that
     * opens re-scans the disks into a fresh, compacted view (re-flowed rows + newly written patterns).
     */
    private PatternDiskRemoveInventory cachedTerminalInventory;

    public PatternDiskProviderBlockEntity(BlockPos pos, BlockState blockState) {
        super(AEPatternRegistries.BE_PROVIDER.get(), pos, blockState);
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

    @Override
    public appeng.api.inventories.InternalInventory getTerminalPatternInventory() {
        if (cachedTerminalInventory != null) {
            return cachedTerminalInventory;
        }
        cachedTerminalInventory = new PatternDiskRemoveInventory(diskInventory,
                new PatternDiskRemoveInventory.BlankPatternSink() {
                    @Override
                    public boolean drawBlankPatterns(int count) {
                        return tryDrawBlankPattern(count);
                    }

                    @Override
                    public boolean hasBlankPatterns(int count) {
                        return canDrawBlankPattern(count);
                    }

                    @Override
                    public boolean returnBlankPatterns(int count) {
                        return returnBlankPattern(count);
                    }
                },
                this::markTerminalChanged);
        return cachedTerminalInventory;
    }

    /**
     * Read-only pre-check: whether the attached ME network currently holds at least {@code count}
     * blank patterns (SIMULATE, nothing is extracted). Returns false when the machine is not on a
     * grid yet or the network cannot cover the whole count.
     */
    private boolean canDrawBlankPattern(int count) {
        var grid = getMainNode().getGrid();
        if (grid == null || count <= 0) {
            return false;
        }
        var storage = grid.getStorageService().getInventory();
        var blank = AEItemKey.of(appeng.core.definitions.AEItems.BLANK_PATTERN);
        return storage.extract(blank, count, appeng.api.config.Actionable.SIMULATE,
                IActionSource.ofMachine(this)) == count;
    }

    /**
     * Draws {@code count} blank patterns from the attached ME network, all-or-nothing: first a
     * simulated check confirms the network can cover count, then a single modulate extract removes them.
     * Returns {@code false} (drawing nothing) when the network lacks count blank patterns or the machine
     * is not on a grid yet.
     */
    private boolean tryDrawBlankPattern(int count) {
        if (!canDrawBlankPattern(count)) {
            return false;
        }
        var grid = getMainNode().getGrid();
        var storage = grid.getStorageService().getInventory();
        var blank = AEItemKey.of(appeng.core.definitions.AEItems.BLANK_PATTERN);
        return storage.extract(blank, count, appeng.api.config.Actionable.MODULATE,
                IActionSource.ofMachine(this)) == count;
    }

    /**
     * Returns {@code count} blank patterns to the attached ME network (undo of {@link #tryDrawBlankPattern},
     * used when an AE2 swap restore re-inserts a just-taken pattern).
     */
    private boolean returnBlankPattern(int count) {
        var grid = getMainNode().getGrid();
        if (grid == null || count <= 0) {
            return false;
        }
        var storage = grid.getStorageService().getInventory();
        var blank = AEItemKey.of(appeng.core.definitions.AEItems.BLANK_PATTERN);
        return storage.insert(blank, count, appeng.api.config.Actionable.MODULATE,
                IActionSource.ofMachine(this)) == count;
    }

    /** Rebuilds the provider's pattern list and invalidates the cached terminal view after a real
     * mutation. An already-open PAT keeps using its own instance (rows frozen, removals empty rows in
     * place); the next PAT that opens rebuilds a fresh, compacted view of the current disk layout. */
    private void markTerminalChanged() {
        refreshFromDisks();
        saveChanges();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        refreshFromDisks();
    }

    /**
     * Rebuilds the provider's pattern list from current disk contents and invalidates the cached
     * terminal view so the next PAT session opens a fresh, compacted view. An open PAT keeps its own
     * (frozen-row) instance and is never re-synced here — re-syncing would grow its row count past the
     * PAT's fixed client slot count and crash the server (AE2 limitation).
     */
    public void refreshFromDisks() {
        if (getLogic() instanceof PatternDiskProviderLogic diskLogic) {
            diskLogic.refreshPatternsFromDisks();
        }
        cachedTerminalInventory = null; // invalidate: next PAT opening rebuilds a fresh view
    }

    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        if (inv == diskInventory) {
            // 磁盘槽内容变化（含 PAT 取出真删、编码终端写盘、取盘/换盘）：磁盘已是最终状态，
            // 只需重建 pattern 列表并使缓存视图失效（下次 PAT 打开重建紧凑视图）。
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
        return AEItemKey.of(AEPatternRegistries.ITEM_PROVIDER.get());
    }

    @Override
    public ItemStack getMainMenuIcon() {
        return new ItemStack(AEPatternRegistries.ITEM_PROVIDER.get());
    }
}
