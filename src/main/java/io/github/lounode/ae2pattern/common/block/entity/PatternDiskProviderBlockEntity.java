package io.github.lounode.ae2pattern.common.block.entity;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import appeng.api.ids.AEComponents;
import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.AEItemKey;
import appeng.blockentity.crafting.PatternProviderBlockEntity;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.util.SettingsFrom;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;

import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.pattern.PatternDiskTerminalView;

import io.github.lounode.ae2pattern.common.logic.PatternDiskProviderLogic;
import io.github.lounode.ae2pattern.AEPatternRegistries;

/**
 * Block entity for the pattern disk provider: holds up to {@link #DISK_SLOT_COUNT} pattern disks and
 * exposes every encoded pattern on them to the ME autocrafting service.
 */
public class PatternDiskProviderBlockEntity extends PatternProviderBlockEntity
        implements PatternDiskProviderHost, InternalInventoryHost {

    public static final int DISK_SLOT_COUNT = 9;

    private final AppEngInternalInventory diskInventory = new AppEngInternalInventory(this, DISK_SLOT_COUNT);

    /**
     * Terminal view shared with the panel part (see {@link PatternDiskTerminalView}). Cached because an
     * open pattern access terminal keeps using the instance it grabbed at open time (rows frozen), so
     * any disk change invalidates it and the next terminal that opens re-scans the disks.
     */
    private final PatternDiskTerminalView terminalView = new PatternDiskTerminalView(diskInventory,
            () -> getMainNode().getGrid(), this, this::markTerminalChanged);

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
        return terminalView.view();
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
        terminalView.invalidate(); // next terminal opening rebuilds a fresh view
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
        // Dropping has to cover everything the machine really holds: AE2's addDrops does the pending
        // push list and the return inventory, but it also dumps the pattern inventory - which here is
        // only a mirror of the disks. Empty the mirror first so the drop is purely the machine's own
        // contents; super.addAdditionalDrops stays uncalled for the same reason.
        getLogic().getPatternInv().clear();
        getLogic().addDrops(drops);
        // Also clear the injected pattern inventory so nothing stale remains on break.
        clearContent();
    }

    /**
     * Applies a memory card while keeping the disk mirror intact: AE2's default implementation clears the
     * provider's pattern inventory and hands those patterns to the player, but ours only mirrors the
     * disks, so that would duplicate every encoded pattern (the disks keep the originals).
     */
    @Override
    public void importSettings(SettingsFrom mode, DataComponentMap input, @Nullable Player player) {
        var cleanInput = withoutPatterns(input);
        if (mode == SettingsFrom.MEMORY_CARD) {
            getLogic().getPatternInv().clear(); // mirror, rebuilt below from the disks
        }
        super.importSettings(mode, cleanInput, player);
        if (mode == SettingsFrom.MEMORY_CARD) {
            refreshFromDisks();
        }
    }

    /**
     * Strips the pattern section from a memory card before anyone reads it. A card written by an older
     * build - or by an AE2 pattern provider - still carries patterns, and importing those charges blank
     * patterns for copies of what the disks already hold, only for the next refresh to discard them.
     */
    private static DataComponentMap withoutPatterns(DataComponentMap input) {
        var sanitized = DataComponentMap.builder().addAll(input);
        sanitized.set(AEComponents.EXPORTED_PATTERNS, ItemContainerContents.EMPTY);
        return sanitized.build();
    }

    /**
     * Writes the settings into a memory card, minus the pattern inventory: AE2's provider puts its own
     * patterns in the card, but the card would then carry copies of what the disks already hold, and
     * importing them elsewhere charges blank patterns for those copies.
     */
    @Override
    public void exportSettings(SettingsFrom mode, DataComponentMap.Builder builder, @Nullable Player player) {
        super.exportSettings(mode, builder, player);
        if (mode == SettingsFrom.MEMORY_CARD) {
            builder.set(AEComponents.EXPORTED_PATTERNS, ItemContainerContents.EMPTY);
        }
    }

    @Override
    public void clearContent() {
        super.clearContent();
        diskInventory.clear();
        terminalView.invalidate();
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
