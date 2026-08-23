package io.github.lounode.ae2pattern.common.block.entity;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import appeng.api.config.Actionable;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.UpgradeInventories;
import appeng.api.util.AECableType;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.core.definitions.AEItems;
import appeng.me.helpers.MachineSource;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;

import io.github.lounode.ae2pattern.core.AEPatternBlockEntities;
import io.github.lounode.ae2pattern.core.AEPatternBlocks;
import io.github.lounode.ae2pattern.core.AEPatternItems;

/**
 * A parallel molecular assembler that accepts many crafting patterns (from a pattern disk provider or any
 * AE2 provider) and executes up to {@link #THREADS} of them concurrently.
 *
 * <p>Design mirrors AE2's {@code MolecularAssemblerBlockEntity} but scales the single crafting pipeline
 * into N independent execution units. Each unit owns a 3x3 grid + a progress counter; {@code pushPattern}
 * dispatches a pattern to the first idle unit; the grid tick advances all busy units.</p>
 */
public class PatternDiskAssemblerBlockEntity extends AENetworkedBlockEntity
        implements InternalInventoryHost, IUpgradeableObject, IGridTickable,
        appeng.api.implementations.blockentities.ICraftingMachine {

    public static final int THREADS = 8;
    public static final int GRID_SIZE = 9; // 3x3
    private static final int OUTPUT_SLOT = 9; // gridInv is 10 slots (9 grid + 1 output)
    private static final int[] SPEED_STEPS = { 10, 13, 17, 20, 25, 50 };
    private static final double[] POWER_MULT = { 1.0, 1.3, 1.7, 2.0, 2.5, 5.0 };

    private final IActionSource actionSource = new MachineSource(this);
    private final IUpgradeInventory upgrades;

    /** One execution unit per thread. */
    private static final class CraftUnit {
        final AppEngInternalInventory grid = new AppEngInternalInventory(GRID_SIZE + 1);
        // Persisted crafting container, reused across crafts (avoids per-craft object churn).
        final net.minecraft.world.inventory.TransientCraftingContainer craftingInv;
        IMolecularAssemblerSupportedPattern plan = null;
        double progress = 0;
        Direction pushDirection = null;
        boolean forcePlan = false;

        CraftUnit() {
            var menu = new net.minecraft.world.inventory.AbstractContainerMenu(null, 0) {
                @Override
                public net.minecraft.world.item.ItemStack quickMoveStack(net.minecraft.world.entity.player.Player p, int i) {
                    return net.minecraft.world.item.ItemStack.EMPTY;
                }

                @Override
                public boolean stillValid(net.minecraft.world.entity.player.Player p) {
                    return true;
                }
            };
            this.craftingInv = new net.minecraft.world.inventory.TransientCraftingContainer(menu, 3, 3);
        }
    }

    private final CraftUnit[] units = new CraftUnit[THREADS];

    public PatternDiskAssemblerBlockEntity(BlockPos pos, BlockState blockState) {
        super(AEPatternBlockEntities.PATTERN_DISK_ASSEMBLER.get(), pos, blockState);
        for (int i = 0; i < THREADS; i++) {
            units[i] = new CraftUnit();
        }
        this.upgrades = UpgradeInventories.forMachine(AEPatternBlocks.PATTERN_DISK_ASSEMBLER.get(), 5,
                this::onUpgradesChanged);
        this.getMainNode()
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .setIdlePowerUsage(0)
                .setInWorldNode(true)
                .setExposedOnSides(java.util.Set.of(Direction.values()))
                .addService(IGridTickable.class, this);;
    }

    private void onUpgradesChanged() {
        saveChanges();
    }

    /** Total crafting progress across all threads (0..THREADS*100). */
    public int getCraftingProgressAcrossThreads() {
        int total = 0;
        for (var unit : units) {
            total += (int) Math.min(unit.progress, 100);
        }
        return total;
    }

    @Override
    public IUpgradeInventory getUpgrades() {
        return upgrades;
    }

    @Override
    public AECableType getCableConnectionType(Direction direction) {
        return AECableType.SMART;
    }

    /**
     * Accepts a pattern from any AE2 provider and dispatches it to the first idle unit.
     */
    @Override
    public boolean pushPattern(appeng.api.crafting.IPatternDetails patternDetails, KeyCounter[] table,
            Direction where) {
        if (!(patternDetails instanceof IMolecularAssemblerSupportedPattern pattern)) {
            return false;
        }
        for (var unit : units) {
            if (unit.plan == null && unit.grid.isEmpty()) {
                unit.forcePlan = true;
                unit.plan = pattern;
                unit.pushDirection = where;
                fillGrid(unit, table, pattern);
                return true;
            }
        }
        return false; // all units busy
    }

    private void fillGrid(CraftUnit unit, KeyCounter[] table, IMolecularAssemblerSupportedPattern adapter) {
        adapter.fillCraftingGrid(table, unit.grid::setItemDirect);
        for (var list : table) {
            list.removeZeros();
        }
    }

    @Override
    public boolean acceptsPlans() {
        return true;
    }

    @Override
    public appeng.api.implementations.blockentities.PatternContainerGroup getCraftingMachineInfo() {
        return new appeng.api.implementations.blockentities.PatternContainerGroup(
                AEItemKey.of(AEPatternItems.PATTERN_DISK_ASSEMBLER.get()),
                AEPatternItems.PATTERN_DISK_ASSEMBLER.get().getDescription(),
                List.of(Component.translatable("ae2_pattern_disk.assembler.threads", THREADS)));
    }

    @Override
    public TickingRequest getTickingRequest(IGridNode node) {
        return new TickingRequest(10, 10, false);
    }

    @Override
    public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
        boolean anyBusy = false;
        for (var unit : units) {
            anyBusy |= advanceUnit(node, unit, ticksSinceLastCall);
        }
        return anyBusy ? TickRateModulation.FASTER : TickRateModulation.SLEEP;
    }

    /**
     * Advances one crafting unit; returns true if it is still busy.
     */
    private boolean advanceUnit(IGridNode node, CraftUnit unit, int ticksSinceLastCall) {
        // Eject finished output first.
        ItemStack output = unit.grid.getStackInSlot(OUTPUT_SLOT);
        if (!output.isEmpty()) {
            pushOut(unit, output);
            return unit.plan != null;
        }

        if (unit.plan == null) {
            return false;
        }

        int speedCards = upgrades.getInstalledUpgrades(AEItems.SPEED_CARD);
        int speed = SPEED_STEPS[Math.min(speedCards, 5)];
        double powerMult = POWER_MULT[Math.min(speedCards, 5)];
        // Power gating: skip if no power available.
        if (!hasPower(powerMult)) {
            return true;
        }
        unit.progress += ticksSinceLastCall * speed;
        if (unit.progress >= 100) {
            unit.progress = 0;
            var level = this.level;
            if (level != null) {
                var positioned = craftGrid(unit);
                var craftInput = positioned.input();
                ItemStack result = unit.plan.assemble(craftInput, level);
                if (!result.isEmpty()) {
                    unit.grid.setItemDirect(OUTPUT_SLOT, result);
                }
                // Handle crafting remainders (non-consumed ingredients, e.g. buckets).
                var remainders = unit.plan.getRemainingItems(craftInput);
                for (int r = 0; r < remainders.size() && r < GRID_SIZE; r++) {
                    if (!remainders.get(r).isEmpty()) {
                        unit.grid.setItemDirect(r, remainders.get(r));
                    }
                }
                return true;
            }
        }
        return true;
    }

    /** True if the grid can supply the per-work energy for this unit. */
    private boolean hasPower(double powerMult) {
        var grid = this.getMainNode().getGrid();
        if (grid == null) {
            return false;
        }
        var energy = grid.getEnergyService();
        return energy == null || energy.isNetworkPowered();
    }

    /**
     * Builds a positioned crafting input from the unit's grid.
     */
    /**
     * Builds a positioned crafting input from the unit's grid, reusing the unit's cached crafting container.
     */
    private net.minecraft.world.item.crafting.CraftingInput.Positioned craftGrid(CraftUnit unit) {
        for (int i = 0; i < GRID_SIZE; i++) {
            unit.craftingInv.setItem(i, unit.grid.getStackInSlot(i));
        }
        return unit.craftingInv.asPositionedCraftInput();
    }

    private void pushOut(CraftUnit unit, ItemStack stack) {
        if (level == null || level.isClientSide()) {
            return;
        }
        // Push to the adjacent machine indicated by pushDirection, or any face-if none set.
        Direction dir = unit.pushDirection;
        if (dir != null) {
            stack = pushToAdjacent(stack, dir);
        } else {
            for (Direction d : Direction.values()) {
                stack = pushToAdjacent(stack, d);
                if (stack.isEmpty()) {
                    break;
                }
            }
        }
        // Anything left goes back to the ME network (best-effort), else stays in output slot.
        if (!stack.isEmpty()) {
            var grid = this.getMainNode().getGrid();
            if (grid != null) {
                var storage = grid.getStorageService();
                if (storage != null) {
                    var inserted = storage.getInventory().insert(AEItemKey.of(stack), stack.getCount(),
                            Actionable.MODULATE, actionSource);
                    if (inserted > 0) {
                        stack.shrink((int) inserted);
                    }
                }
            }
        }
        if (!stack.isEmpty()) {
            unit.grid.setItemDirect(OUTPUT_SLOT, stack);
        } else {
            unit.grid.setItemDirect(OUTPUT_SLOT, ItemStack.EMPTY);
        }
        unit.forcePlan = false;
        unit.plan = null;
    }

    private ItemStack pushToAdjacent(ItemStack output, Direction d) {
        if (output.isEmpty()) {
            return output;
        }
        var adaptor = appeng.api.inventories.InternalInventory.wrapExternal(level, worldPosition.relative(d), d.getOpposite());
        if (adaptor == null) {
            return output;
        }
        int size = output.getCount();
        output = adaptor.addItems(output);
        int newSize = output.isEmpty() ? 0 : output.getCount();
        if (size != newSize) {
            saveChanges();
        }
        return output;
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State state) {
        markForUpdate();
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
        for (int i = 0; i < THREADS; i++) {
            units[i].grid.writeToNBT(tag, "unit" + i, registries);
            tag.putDouble("unitProgress" + i, units[i].progress);
        }
        upgrades.writeToNBT(tag, "upgrades", registries);
    }

    @Override
    public void loadTag(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.loadTag(tag, registries);
        for (int i = 0; i < THREADS; i++) {
            units[i].grid.readFromNBT(tag, "unit" + i, registries);
            units[i].progress = tag.getDouble("unitProgress" + i);
        }
        upgrades.readFromNBT(tag, "upgrades", registries);
    }

    @Override
    public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        super.addAdditionalDrops(level, pos, drops);
        for (int i = 0; i < THREADS; i++) {
            for (int s = 0; s < units[i].grid.size(); s++) {
                if (!units[i].grid.getStackInSlot(s).isEmpty()) {
                    drops.add(units[i].grid.getStackInSlot(s));
                }
            }
        }
        for (int i = 0; i < upgrades.size(); i++) {
            ItemStack u = upgrades.getStackInSlot(i);
            if (!u.isEmpty()) {
                drops.add(u);
            }
        }
    }

    @Override
    public void clearContent() {
        super.clearContent();
        for (var unit : units) {
            unit.grid.clear();
            unit.plan = null;
            unit.progress = 0;
        }
        upgrades.clear();
    }
}
