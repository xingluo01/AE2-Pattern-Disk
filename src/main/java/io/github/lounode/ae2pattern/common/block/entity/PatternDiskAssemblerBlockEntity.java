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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import appeng.api.config.Actionable;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.stacks.AEItemKey;
import appeng.api.inventories.InternalInventory;
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
        /** Manual encoded pattern (slot 0); when populated this unit self-executes it (AE2-style). */
        final AppEngInternalInventory patternInv;
        // Persisted crafting container, reused across crafts (avoids per-craft object churn).
        final net.minecraft.world.inventory.TransientCraftingContainer craftingInv;
        IMolecularAssemblerSupportedPattern plan = null;
        double progress = 0;
        Direction pushDirection = null;

        CraftUnit(InternalInventoryHost host) {
            this.patternInv = new AppEngInternalInventory(host, 1);
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
            units[i] = new CraftUnit(this);
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

    /** Number of threads currently executing a plan (busy). */
    public int getRunningThreads() {
        int busy = 0;
        for (var unit : units) {
            if (unit.plan != null || !unit.grid.isEmpty()) {
                busy++;
            }
        }
        return busy;
    }

    /**
     * Read-only view of one unit's crafting grid (3x3 input + output at index 9). The returned
     * inventory is a live view of the unit; the client uses it to render the per-unit grid slot
     * contents while the machine runs. Never modify it from the client.
     */
    public InternalInventory getUnitGrid(int index) {
        if (index < 0 || index >= THREADS) {
            return InternalInventory.empty();
        }
        return units[index].grid;
    }

    /**
     * Read-only view of one unit's manual pattern slot (encoded pattern drives this unit's execution).
     */
    public InternalInventory getUnitPatternInv(int index) {
        if (index < 0 || index >= THREADS) {
            return InternalInventory.empty();
        }
        return units[index].patternInv;
    }

    /** Current crafting progress of one unit, in percent (0..100). */
    public int getUnitProgress(int index) {
        if (index < 0 || index >= THREADS) {
            return 0;
        }
        return (int) Math.min(units[index].progress, 100);
    }

    /** Current supported crafting pattern for one unit, or {@code null} when the page is idle. */
    public @Nullable IMolecularAssemblerSupportedPattern getCurrentPattern(int index) {
        if (index < 0 || index >= THREADS) {
            return null;
        }
        var unit = units[index];
        if (unit.plan == null && !unit.patternInv.isEmpty() && level instanceof ServerLevel) {
            // Decode the manually inserted pattern on demand so slot validation works immediately.
            var item = unit.patternInv.getStackInSlot(0);
            if (PatternDetailsHelper.decodePattern(item, level) instanceof IMolecularAssemblerSupportedPattern supported) {
                unit.plan = supported;
            }
        }
        return unit.plan;
    }

    /**
     * World-render helper: returns the first output of the earliest active unit, or {@link ItemStack#EMPTY}
     * when no unit is currently assembling. The renderer draws this inside the machine to mirror the
     * original molecular assembler's in-world item depiction.
     */
    public ItemStack getRenderedAssemblyItem() {
        for (int i = 0; i < THREADS; i++) {
            if (!isUnitBusy(i)) {
                continue;
            }
            var pattern = units[i].plan;
            if (pattern == null) {
                continue;
            }
            var primary = pattern.getPrimaryOutput();
            if (primary != null && primary.what() instanceof AEItemKey itemKey) {
                return itemKey.toStack((int) Math.max(1, primary.amount()));
            }
        }
        return ItemStack.EMPTY;
    }

    /** True when the machine has an active grid channel (used to drive the border status light). */
    public boolean isPowered() {
        return getMainNode() != null && getMainNode().isActive();
    }

    /** True if one unit is currently executing a plan or holding leftover output. */
    public boolean isUnitBusy(int index) {
        if (index < 0 || index >= THREADS) {
            return false;
        }
        return units[index].plan != null || !units[index].grid.isEmpty();
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
            // A unit holding a manually inserted pattern executes that pattern and does not
            // accept provider-pushed jobs (mirrors AE2's pattern-inventory gating).
            if (unit.plan == null && unit.grid.isEmpty() && unit.patternInv.isEmpty()) {
                unit.plan = pattern;
                unit.pushDirection = where;
                fillGrid(unit, table, pattern);
                this.getMainNode().ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
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

    /**
     * Extracts this unit's pattern inputs from the ME network into its crafting grid. Used only for
     * self-executing pages (a pattern manually inserted into the unit's pattern slot). Missing inputs
     * are simply left empty; the caller re-checks readiness via {@link #canAssemble}.
     */
    private void tryFillGridFromNetwork(CraftUnit unit) {
        var plan = unit.plan;
        if (plan == null) {
            return;
        }
        var grid = this.getMainNode().getGrid();
        if (grid == null) {
            return;
        }
        var storage = grid.getStorageService().getInventory();
        var inputs = plan.getInputs();
        boolean changed = false;
        for (int slot = 0; slot < GRID_SIZE && slot < inputs.length; slot++) {
            if (!unit.grid.getStackInSlot(slot).isEmpty()) {
                continue;
            }
            var input = inputs[slot];
            if (input == null) {
                continue;
            }
            for (var possible : input.getPossibleInputs()) {
                if (possible == null || possible.what() == null) {
                    continue;
                }
                long amount = possible.amount() * input.getMultiplier();
                if (amount <= 0) {
                    continue;
                }
                if (possible.what() instanceof AEItemKey itemKey) {
                    long extracted = storage.extract(itemKey, amount, Actionable.MODULATE, actionSource);
                    if (extracted > 0) {
                        unit.grid.setItemDirect(slot, itemKey.toStack((int) extracted));
                        changed = true;
                        break;
                    }
                }
            }
        }
        if (changed) {
            saveChanges();
        }
    }

    /** True when the unit's grid can already assemble its plan into a non-empty result. */
    private boolean canAssemble(CraftUnit unit) {
        var plan = unit.plan;
        if (plan == null || level == null) {
            return false;
        }
        var positioned = craftGrid(unit);
        return !plan.assemble(positioned.input(), level).isEmpty();
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
        return new TickingRequest(1, 1, false);
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
        // Keep retrying both finished output and container remainders until all leftovers are exported.
        ItemStack output = unit.grid.getStackInSlot(OUTPUT_SLOT);
        if (!output.isEmpty()) {
            pushOut(unit, output);
            if (!unit.grid.getStackInSlot(OUTPUT_SLOT).isEmpty()) {
                return true;
            }
        }
        if (unit.plan == null) {
            // Self-executing page: decode the manually inserted encoded pattern from the unit's slot.
            if (!unit.patternInv.isEmpty() && level instanceof ServerLevel) {
                var item = unit.patternInv.getStackInSlot(0);
                if (PatternDetailsHelper.decodePattern(item, level) instanceof IMolecularAssemblerSupportedPattern supported) {
                    unit.plan = supported;
                }
            }
            if (unit.plan == null) {
                return drainRemainders(unit);
            }
            if (!unit.patternInv.isEmpty()) {
                // Pull inputs from the ME network and only start crafting once the grid is complete,
                // so a shortage never consumes progress or destroys partially extracted inputs.
                tryFillGridFromNetwork(unit);
                if (!canAssemble(unit)) {
                    return true;
                }
            }
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
                var plan = unit.plan;
                ItemStack result = plan.assemble(craftInput, level);
                if (result.isEmpty()) {
                    // A failed recipe must release its inputs instead of retrying forever or
                    // treating ordinary inputs as container remainders.
                    unit.plan = null;
                    return drainRemainders(unit);
                }

                unit.grid.setItemDirect(OUTPUT_SLOT, result);
                // A successful craft consumes the input grid; put container remainders back afterward.
                for (int i = 0; i < GRID_SIZE; i++) {
                    unit.grid.setItemDirect(i, ItemStack.EMPTY);
                }
                var remainders = plan.getRemainingItems(craftInput);
                for (int r = 0; r < remainders.size() && r < GRID_SIZE; r++) {
                    if (!remainders.get(r).isEmpty()) {
                        unit.grid.setItemDirect(r, remainders.get(r));
                    }
                }
                unit.plan = null;
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

    /** Keeps returning crafting containers and other remainders until the unit is empty. */
    private boolean drainRemainders(CraftUnit unit) {
        boolean pending = false;
        for (int i = 0; i < GRID_SIZE; i++) {
            ItemStack remainder = unit.grid.getStackInSlot(i);
            if (remainder.isEmpty()) {
                continue;
            }
            ItemStack left = pushItemOut(unit, remainder);
            unit.grid.setItemDirect(i, left);
            pending |= !left.isEmpty();
        }
        return pending;
    }

    /** Pushes an item to the configured adjacent target and then to ME storage. */
    private ItemStack pushItemOut(CraftUnit unit, ItemStack stack) {
        if (level == null || level.isClientSide() || stack.isEmpty()) {
            return stack;
        }
        Direction dir = unit.pushDirection;
        if (dir != null) {
            stack = pushToAdjacent(stack, dir);
        } else {
            for (Direction d : Direction.values()) {
                stack = pushToAdjacent(stack, d);
                if (stack.isEmpty()) {
                    return stack;
                }
            }
        }
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
        return stack;
    }

    /**
     * Pushes the output slot through the same adjacent/network path used for remainders.
     */
    private void pushOut(CraftUnit unit, ItemStack stack) {
        ItemStack left = pushItemOut(unit, stack);
        unit.grid.setItemDirect(OUTPUT_SLOT, left);
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
            units[i].patternInv.writeToNBT(tag, "unitPattern" + i, registries);
            tag.putDouble("unitProgress" + i, units[i].progress);
        }
        upgrades.writeToNBT(tag, "upgrades", registries);
    }

    @Override
    public void loadTag(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.loadTag(tag, registries);
        for (int i = 0; i < THREADS; i++) {
            units[i].grid.readFromNBT(tag, "unit" + i, registries);
            units[i].patternInv.readFromNBT(tag, "unitPattern" + i, registries);
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
            if (!units[i].patternInv.isEmpty()) {
                drops.add(units[i].patternInv.getStackInSlot(0));
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
            unit.patternInv.clear();
            unit.plan = null;
            unit.progress = 0;
        }
        upgrades.clear();
    }
}
