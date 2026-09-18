package io.github.lounode.ae2pattern.common.part;

import java.util.EnumSet;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.phys.Vec3;

import appeng.api.ids.AEComponents;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGridNodeListener;
import appeng.api.parts.IPartCollisionHelper;
import appeng.api.parts.IPartItem;
import appeng.api.parts.IPartModel;
import appeng.api.stacks.AEItemKey;
import appeng.api.util.AECableType;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.items.parts.PartModels;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import appeng.parts.AEBasePart;
import appeng.parts.PartModel;
import appeng.util.SettingsFrom;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;

import io.github.lounode.ae2pattern.common.block.entity.PatternDiskProviderBlockEntity;
import io.github.lounode.ae2pattern.common.block.entity.PatternDiskProviderHost;
import io.github.lounode.ae2pattern.common.logic.PatternDiskProviderLogic;
import io.github.lounode.ae2pattern.common.pattern.PatternDiskTerminalView;
import io.github.lounode.ae2pattern.common.menu.PatternDiskProviderMenu;

/**
 * Panel form of the ME pattern disk provider: a cable-attached part that carries
 * {@link PatternDiskProviderBlockEntity#DISK_SLOT_COUNT} pattern disks and exposes the encoded
 * patterns on them to the ME autocrafting service.
 *
 * <p>Everything that makes the provider a provider lives in {@link PatternDiskProviderLogic}, which
 * only needs a {@code PatternProviderLogicHost}. This part therefore stores nothing but the disk
 * inventory and hands the same logic the block entity uses; the menu is likewise shared, because
 * {@link PatternDiskProviderHost} is the only host type either of them asks for. The look and push
 * direction mirror AE2's cable pattern provider: it pushes only towards the side it is attached
 * to.</p>
 */
public class PatternDiskProviderPart extends AEBasePart
        implements PatternDiskProviderHost, InternalInventoryHost {

    /** Panel model, registered so AE2's part model registry knows the resource exists. */
    @PartModels
    public static final ResourceLocation MODEL = ResourceLocation.parse(
            "ae2_pattern_disk:part/pattern_disk_provider_base");

    public static final IPartModel MODELS = new PartModel(MODEL);

    private final AppEngInternalInventory diskInventory = new AppEngInternalInventory(this,
            PatternDiskProviderBlockEntity.DISK_SLOT_COUNT);

    /**
     * Terminal view shared with the block form (see {@link PatternDiskTerminalView}): the pattern access
     * terminal must read from the disks, never from the derived inventory the logic fills, or a take
     * would leave the disk untouched.
     */
    private final PatternDiskTerminalView terminalView = new PatternDiskTerminalView(diskInventory,
            () -> getMainNode().getGrid(), this, this::markTerminalChanged, this::getLevel);

    protected final PatternProviderLogic logic = createLogic();

    public PatternDiskProviderPart(IPartItem<?> partItem) {
        super(partItem);
    }

    protected PatternProviderLogic createLogic() {
        // Lazy disk supplier, written the same way as the block entity's: the logic may be constructed
        // before the disk inventory field exists, so it must not touch it during construction.
        return new PatternDiskProviderLogic(getMainNode(), this, this::getDiskInventory);
    }

    // ---- PatternDiskProviderHost / IPatternDiskHost ---------------------------

    @Override
    public AppEngInternalInventory getDiskInventory() {
        return diskInventory;
    }

    @Override
    public BlockPos getBlockPos() {
        var host = getBlockEntity();
        return host != null ? host.getBlockPos() : BlockPos.ZERO;
    }

    /** Panels sharing one cable share its block position, so their side has to identify them. */
    @Override
    public int getIdentitySalt() {
        return getSide().name().hashCode();
    }

    @Override
    public PatternProviderLogic getLogic() {
        return logic;
    }

    @Override
    public EnumSet<Direction> getTargets() {
        // A panel only pushes towards the side it is mounted on, like AE2's cable pattern provider.
        return EnumSet.of(getSide());
    }

    @Override
    public AEItemKey getTerminalIcon() {
        return AEItemKey.of(getPartItem());
    }

    public ItemStack getMainMenuIcon() {
        return new ItemStack(getPartItem());
    }

    // ---- Inventory host ------------------------------------------------------

    @Override
    public void saveChanges() {
        getHost().markForSave();
    }

    @Override
    public void saveChangedInventory(AppEngInternalInventory inv) {
        saveChanges();
    }

    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        if (inv == diskInventory) {
            // A disk was inserted/removed or its contents changed: rebuild the exposed pattern list.
            refreshFromDisks();
            saveChanges();
        }
    }

    /** Rebuilds the provider's pattern list from the current disk contents. */
    public void refreshFromDisks() {
        if (logic instanceof PatternDiskProviderLogic diskLogic) {
            diskLogic.refreshPatternsFromDisks();
        }
        terminalView.invalidate(); // next terminal opening re-scans the disks into a fresh view
    }

    /** Invalidates the cached terminal view and persists after a real mutation from a terminal take. */
    private void markTerminalChanged() {
        refreshFromDisks();
        saveChanges();
    }

    @Override
    public InternalInventory getTerminalPatternInventory() {
        return terminalView.view();
    }

    // ---- Part lifecycle ------------------------------------------------------

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        super.onMainNodeStateChanged(reason);
        this.logic.onMainNodeStateChanged();
    }

    @Override
    public void addToWorld() {
        super.addToWorld();
        this.logic.updatePatterns();
    }

    @Override
    public void getBoxes(IPartCollisionHelper bch) {
        // Same footprint as AE2's cable pattern provider.
        bch.addBox(2, 2, 14, 14, 14, 16);
        bch.addBox(5, 5, 12, 11, 11, 14);
    }

    @Override
    public float getCableConnectionLength(AECableType cable) {
        return 4;
    }

    @Override
    public IPartModel getStaticModels() {
        return MODELS;
    }

    @Override
    public void readFromNBT(CompoundTag data, HolderLookup.Provider registries) {
        super.readFromNBT(data, registries);
        this.logic.readFromNBT(data, registries);
        diskInventory.readFromNBT(data, "disks", registries);
        refreshFromDisks();
    }

    @Override
    public void writeToNBT(CompoundTag data, HolderLookup.Provider registries) {
        super.writeToNBT(data, registries);
        this.logic.writeToNBT(data, registries);
        diskInventory.writeToNBT(data, "disks", registries);
    }

    @Override
    public void addAdditionalDrops(List<ItemStack> drops, boolean wrenched) {
        super.addAdditionalDrops(drops, wrenched);
        // Only the disks are the source of truth: the pattern inventory the logic fills is derived from
        // them (the same reasoning the block entity documents for its own drops).
        for (int i = 0; i < diskInventory.size(); i++) {
            ItemStack stack = diskInventory.getStackInSlot(i);
            if (!stack.isEmpty()) {
                drops.add(stack);
            }
        }
        // AE2's addDrops covers the pending push list and the return inventory, but it also dumps the
        // pattern inventory - for us a mirror of the disks. Empty the mirror first so nothing but the
        // machine's own contents drops.
        logic.getPatternInv().clear();
        logic.addDrops(drops);
        // The part clears itself because the removal path always drops it: collecting the drops first
        // and wiping afterwards (instead of leaving it to the caller) makes the two idempotent.
        clearContent();
    }

    @Override
    public void clearContent() {
        super.clearContent();
        diskInventory.clear();
        logic.clearContent();
        terminalView.invalidate();
    }

    /**
     * Writes the settings into a memory card, minus the pattern inventory: AE2's provider puts its own
     * patterns in the card, but ours would then carry copies of what the disks already hold, and
     * importing them elsewhere charges blank patterns for those copies.
     */
    @Override
    public void exportSettings(SettingsFrom mode, DataComponentMap.Builder builder) {
        super.exportSettings(mode, builder);
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
            logic.getPatternInv().clear(); // mirror, rebuilt below from the disks
        }
        super.importSettings(mode, cleanInput, player);

        if (mode == SettingsFrom.MEMORY_CARD) {
            logic.importSettings(cleanInput, player);
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

    @Override
    public void onNeighborChanged(BlockGetter level, BlockPos pos, BlockPos neighbor) {
        // A panel sits on a cable, so redstone reaches it through the cable block; AE2's own cable
        // pattern provider reacts the same way, without which a locked provider never unlocks.
        logic.updateRedstoneState();
    }

    @Override
    public boolean onUseWithoutItem(Player p, Vec3 pos) {
        if (!p.level().isClientSide()) {
            MenuOpener.open(PatternDiskProviderMenu.TYPE, p, MenuLocators.forPart(this));
        }
        return true;
    }
}
