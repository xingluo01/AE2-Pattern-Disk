package io.github.lounode.ae2pattern.common.block.entity;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import appeng.api.config.Actionable;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.UpgradeInventories;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.me.helpers.MachineSource;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;

import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.pattern.PatternClassifier;
import io.github.lounode.ae2pattern.common.pattern.PatternDiskContents;
import io.github.lounode.ae2pattern.core.AEPatternBlockEntities;
import io.github.lounode.ae2pattern.core.AEPatternBlocks;
import io.github.lounode.ae2pattern.core.AEPatternComponents;
import io.github.lounode.ae2pattern.core.AEPatternItems;

/**
 * Block entity for the pattern transferer.
 *
 * <p>Inventory layout (6 input + 6 application + 1 output):</p>
 * <ul>
 *   <li>6 input slots (index 0..5): accept encoded patterns or populated pattern disks.</li>
 *   <li>6 application slots (index 6..11): target pattern disks that receive transferred patterns.</li>
 *   <li>1 output slot (index 12): temporary storage for blank patterns produced when patterns are extracted.</li>
 * </ul>
 *
 * <p>Each server tick it performs one work cycle:</p>
 * <ol>
 *   <li>Blank patterns in the output slot are returned to the connected ME network (if any).</li>
 *   <li>For each input slot: if it holds an encoded pattern, extract it into an application disk and produce a
 *       blank pattern into the output slot. If it holds a populated pattern disk, move its patterns into the
 *       application disks.</li>
 * </ol>
 */
public class PatternTransfererBlockEntity extends AENetworkedBlockEntity
        implements InternalInventoryHost, IUpgradeableObject, appeng.blockentity.ServerTickingBlockEntity {

    public static final int INPUT_SLOT_COUNT = 6;
    public static final int INPUT_START = 0;

    public static final int APPLICATION_SLOT_COUNT = 6;
    public static final int APPLICATION_START = INPUT_SLOT_COUNT;

    public static final int OUTPUT_SLOT = INPUT_SLOT_COUNT + APPLICATION_SLOT_COUNT;
    public static final int TOTAL_SLOTS = OUTPUT_SLOT + 1;

    /** Number of upgrade slots (accelerator/speed cards). */
    public static final int UPGRADE_SLOT_COUNT = 4;

    /** Ticks per second (Minecraft). */
    private static final double TICKS_PER_SECOND = 20.0;

    /** Accumulated fractional patterns to transfer (for smooth per-tick rate). */
    private double patternAccumulator = 0;

    private final AppEngInternalInventory inventory = new AppEngInternalInventory(this, TOTAL_SLOTS);
    private final IActionSource actionSource = new MachineSource(this);
    private final IUpgradeInventory upgrades;

    public PatternTransfererBlockEntity(BlockPos pos, BlockState blockState) {
        super(AEPatternBlockEntities.PATTERN_TRANSFERER.get(), pos, blockState);
        this.upgrades = UpgradeInventories.forMachine(
                AEPatternBlocks.PATTERN_TRANSFERER.get(),
                UPGRADE_SLOT_COUNT,
                this::onUpgradesChanged);
        this.getMainNode()
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .setIdlePowerUsage(0.5)
                // In-world node so adjacent AE2 cables can connect (getGridNode returns non-null).
                .setInWorldNode(true)
                .setExposedOnSides(java.util.Set.of(Direction.values()));
    }

    private void onUpgradesChanged() {
        saveChanges();
    }

    @Override
    public IUpgradeInventory getUpgrades() {
        return upgrades;
    }

    @Override
    protected IManagedGridNode createMainNode() {
        return super.createMainNode().setVisualRepresentation(AEPatternItems.PATTERN_TRANSFERER.get());
    }

    @Override
    public appeng.api.util.AECableType getCableConnectionType(Direction direction) {
        return appeng.api.util.AECableType.SMART;
    }

    @Override
    public java.util.Set<Direction> getGridConnectableSides(appeng.api.orientation.BlockOrientation orientation) {
        // The transferer can be connected to cables from all sides.
        return java.util.EnumSet.allOf(Direction.class);
    }

    @Override
    public void onMainNodeStateChanged(appeng.api.networking.IGridNodeListener.State state) {
        // Mark for client update on grid changes so the (re)connected state is reflected.
        markForUpdate();
    }

    public AppEngInternalInventory getInventory() {
        return inventory;
    }

    public static int inputSlot(int index) {
        return INPUT_START + index;
    }

    public static int applicationSlot(int index) {
        return APPLICATION_START + index;
    }

    /**
     * Server tick: perform one work cycle per tick.
     */
    @Override
    public void serverTick() {
        if (level == null || level.isClientSide()) {
            return;
        }
        tickTransfer();
    }

    /**
     * Runs the transfer at the speed-card rate: {@code 2^(2x+1)} patterns per second, where x is the
     * number of installed speed cards. Spread across 20 ticks/second with an accumulator.
     */
    private void tickTransfer() {
        int speedCards = installedSpeedCards();
        double patternsPerSecond = Math.pow(2, 2 * speedCards + 1);
        double perTick = patternsPerSecond / TICKS_PER_SECOND;

        patternAccumulator += perTick;
        int toProcess = (int) patternAccumulator;
        if (toProcess <= 0) {
            return;
        }
        patternAccumulator -= toProcess;

        boolean changed = returnBlanksToNetwork();
        changed |= processInputs(toProcess);
        if (changed) {
            saveChanges();
        }
    }

    private int installedSpeedCards() {
        var speedCard = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.parse("ae2:speed_card"));
        return speedCard == null ? 0 : upgrades.getInstalledUpgrades(speedCard);
    }

    private int countNonEmptyInputs() {
        int c = 0;
        for (int i = 0; i < INPUT_SLOT_COUNT; i++) {
            if (!inventory.getStackInSlot(inputSlot(i)).isEmpty()) {
                c++;
            }
        }
        return c;
    }

    /**
     * Moves blank patterns from the output slot back into the connected ME network.
     */
    private boolean returnBlanksToNetwork() {
        ItemStack output = inventory.getStackInSlot(OUTPUT_SLOT);
        if (output.isEmpty() || !PatternClassifier.isBlankPattern(output)) {
            return false;
        }
        var grid = this.getMainNode().getGrid();
        if (grid == null) {
            return false;
        }
        var storage = grid.getStorageService();
        if (storage == null) {
            return false;
        }
        var blankKey = AEItemKey.of(output);
        long toSend = output.getCount();
        long inserted = storage.getInventory().insert(blankKey, toSend, Actionable.MODULATE, actionSource);
        if (inserted > 0) {
            output.shrink((int) inserted);
            if (output.isEmpty()) {
                inventory.setItemDirect(OUTPUT_SLOT, ItemStack.EMPTY);
            }
            return true;
        }
        return false;
    }

    /**
     * Processes the six input slots, distributing patterns across the application disks.
     *
     * @param limit maximum number of patterns to transfer this tick.
     */
    private boolean processInputs(int limit) {
        int processed = 0;
        for (int i = 0; i < INPUT_SLOT_COUNT && processed < limit; i++) {
            ItemStack input = inventory.getStackInSlot(inputSlot(i));
            if (input.isEmpty()) {
                continue;
            }

            boolean did = false;
            if (input.getItem() instanceof PatternDiskItem disk) {
                did = drainDiskInto(input, disk, limit - processed);
            } else if (PatternClassifier.isEncodedPatternStack(input)) {
                did = extractPatternInto(input);
            }
            if (did) {
                processed++;
            }
        }
        return processed > 0;
    }

    /**
     * Moves all patterns from a populated source disk into application disks with matching/empty type.
     */
    private boolean drainDiskInto(ItemStack source, PatternDiskItem sourceDisk, int limit) {
        var sourceContents = sourceDisk.contents(source);
        if (sourceContents.isEmpty() || limit <= 0) {
            return false;
        }

        boolean changed = false;
        int moved = 0;
        for (int slot = 0; slot < APPLICATION_SLOT_COUNT && !sourceContents.isEmpty() && moved < limit; slot++) {
            ItemStack appDisk = inventory.getStackInSlot(applicationSlot(slot));
            if (appDisk.isEmpty() || !(appDisk.getItem() instanceof PatternDiskItem app)) {
                continue;
            }
            var appContents = app.contents(appDisk);
            if (appContents.isTyped() && !appContents.type().equals(sourceContents.type())) {
                continue; // Type mismatch for this disk.
            }

            while (!sourceContents.isEmpty() && !appContents.isFull() && moved < limit) {
                var candidate = sourceContents.patterns().get(0);
                // Same-result exclusivity: skip patterns that duplicate an output already on the disk.
                if (hasConflictingOutput(appContents, candidate, this.level)) {
                    sourceContents = sourceContents.remove(0);
                    continue;
                }
                var updated = appContents.add(candidate, sourceContents.type());
                if (updated == null) {
                    break;
                }
                appContents = updated;
                sourceContents = sourceContents.remove(0);
                moved++;
                changed = true;
            }

            if (moved > 0) {
                appDisk.set(AEPatternComponents.DISK_CONTENTS.get(), appContents);
            }
        }

        if (changed) {
            if (sourceContents.isEmpty()) {
                source.remove(AEPatternComponents.DISK_CONTENTS.get());
            } else {
                source.set(AEPatternComponents.DISK_CONTENTS.get(), sourceContents);
            }
        }
        return changed;
    }

    /**
     * Extracts an encoded pattern from an input slot into an application disk, producing a blank pattern
     * into the output slot.
     */
    private boolean extractPatternInto(ItemStack input) {
        var level = this.level;
        if (level == null) {
            return false;
        }
        String type = PatternClassifier.typeOf(input, level);
        if (type == null) {
            return false;
        }

        int targetSlot = -1;
        PatternDiskContents targetContents = null;
        PatternDiskItem targetDisk = null;
        for (int slot = 0; slot < APPLICATION_SLOT_COUNT; slot++) {
            ItemStack appDisk = inventory.getStackInSlot(applicationSlot(slot));
            if (appDisk.isEmpty() || !(appDisk.getItem() instanceof PatternDiskItem app)) {
                continue;
            }
            var contents = app.contents(appDisk);
            if (contents.isFull()) {
                continue;
            }
            if (!contents.isTyped() || contents.type().equals(type)) {
                targetSlot = slot;
                targetContents = contents;
                targetDisk = app;
                break;
            }
        }
        if (targetSlot < 0) {
            return false;
        }

        // Same-result exclusivity: reject if any existing pattern on the target disk produces the
        // same output as the incoming pattern (mutual exclusion among different crafting routes).
        if (hasConflictingOutput(targetContents, input, level)) {
            return false;
        }

        var updated = targetContents.add(input, type);
        if (updated == null) {
            return false;
        }

        ItemStack appDisk = inventory.getStackInSlot(applicationSlot(targetSlot));
        appDisk.set(AEPatternComponents.DISK_CONTENTS.get(), updated);
        inventory.setItemDirect(inputSlotOf(input), ItemStack.EMPTY);

        // Produce a blank pattern into the output slot.
        ItemStack blank = AEPatternItems.blankPattern();
        ItemStack output = inventory.getStackInSlot(OUTPUT_SLOT);
        if (output.isEmpty()) {
            inventory.setItemDirect(OUTPUT_SLOT, blank);
        } else if (output.is(blank.getItem())) {
            output.grow(1);
        }
        return true;
    }

    private int inputSlotOf(ItemStack input) {
        for (int i = 0; i < INPUT_SLOT_COUNT; i++) {
            if (inventory.getStackInSlot(inputSlot(i)) == input) {
                return inputSlot(i);
            }
        }
        return INPUT_START;
    }

    /**
     * Returns true if any pattern already on the disk produces the same primary output as {@code candidate}.
     * Used to enforce same-result mutual exclusion (only one crafting route per output may be stored).
     */
    private boolean hasConflictingOutput(PatternDiskContents contents, ItemStack candidate, Level level) {
        if (contents == null || contents.isEmpty()) {
            return false;
        }
        var candidateDetails = PatternClassifier.decode(candidate, level);
        if (candidateDetails == null) {
            return false;
        }
        var candidateOutput = candidateDetails.getPrimaryOutput();
        for (var existing : contents.patterns()) {
            var details = PatternClassifier.decode(existing, level);
            if (details != null) {
                var existingOutput = details.getPrimaryOutput();
                if (existingOutput != null && candidateOutput != null
                        && existingOutput.what().equals(candidateOutput.what())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        saveChanges();
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
    public void saveAdditional(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        inventory.writeToNBT(tag, "inv", registries);
        upgrades.writeToNBT(tag, "upgrades", registries);
        tag.putDouble("patternAccumulator", patternAccumulator);
    }

    @Override
    public void loadTag(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.loadTag(tag, registries);
        inventory.readFromNBT(tag, "inv", registries);
        upgrades.readFromNBT(tag, "upgrades", registries);
        patternAccumulator = tag.getDouble("patternAccumulator");
    }

    @Override
    public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        super.addAdditionalDrops(level, pos, drops);
        for (int i = 0; i < inventory.size(); i++) {
            drops.add(inventory.getStackInSlot(i));
        }
        for (int i = 0; i < upgrades.size(); i++) {
            drops.add(upgrades.getStackInSlot(i));
        }
    }

    @Override
    public void clearContent() {
        super.clearContent();
        inventory.clear();
    }

    public void openMenu(net.minecraft.world.entity.player.Player player, appeng.menu.locator.MenuHostLocator locator) {
        appeng.menu.MenuOpener.open(io.github.lounode.ae2pattern.common.menu.PatternTransfererMenu.TYPE, player, locator);
    }
}
