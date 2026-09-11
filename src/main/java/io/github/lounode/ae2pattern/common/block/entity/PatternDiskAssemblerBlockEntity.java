package io.github.lounode.ae2pattern.common.block.entity;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

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

import io.github.lounode.ae2pattern.network.AssemblerAnimationPayload;
import io.github.lounode.ae2pattern.network.AssemblerAnimationStatus;
import io.github.lounode.ae2pattern.AEPatternRegistries;

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

    private final CraftUnit[] units = new CraftUnit[THREADS];

    /** Client-synced power state. */
    private boolean isPowered = false;

    @OnlyIn(Dist.CLIENT)
    private AssemblerAnimationStatus animationStatus;

    public PatternDiskAssemblerBlockEntity(BlockPos pos, BlockState blockState) {
        super(AEPatternRegistries.BE_ASSEMBLER.get(), pos, blockState);
        for (int i = 0; i < THREADS; i++) {
            units[i] = new CraftUnit(this);
        }
        this.upgrades = UpgradeInventories.forMachine(AEPatternRegistries.BLOCK_ASSEMBLER.get(), 5,
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

    /** True while a unit executes a plan; the client uses it to keep provider-driven pages undimmed. */
    public boolean isUnitExecuting(int index) {
        if (index < 0 || index >= THREADS) {
            return false;
        }
        return units[index].plan != null;
    }

    /** Current supported crafting pattern for one unit, or {@code null} when the page is idle. */
    public @Nullable IMolecularAssemblerSupportedPattern getCurrentPattern(int index) {
        if (index < 0 || index >= THREADS) {
            return null;
        }
        var unit = units[index];
        if (isClientSide()) {
            // The execution plan is server-side state and is never mirrored to the client. Decode the
            // manual pattern from the (synced) pattern slot instead, so slot validation and the
            // disabled-slot overlay match the server-side state.
            return decodeManualPattern(unit);
        }
        if (unit.plan == null && !unit.patternInv.isEmpty()) {
            // Decode the manually inserted pattern on demand so slot validation works immediately.
            var supported = decodeManualPattern(unit);
            if (supported != null) {
                unit.plan = supported;
                unit.planSource = unit.patternInv.getStackInSlot(0).copy();
            }
        }
        return unit.plan;
    }

    /** Decodes the encoded pattern manually inserted into a unit's slot, or {@code null} if unusable. */
    private @Nullable IMolecularAssemblerSupportedPattern decodeManualPattern(CraftUnit unit) {
        if (level == null || unit.patternInv.isEmpty()) {
            return null;
        }
        var item = unit.patternInv.getStackInSlot(0);
        if (item.isEmpty()) {
            return null;
        }
        return PatternDetailsHelper.decodePattern(item, level) instanceof IMolecularAssemblerSupportedPattern supported
                ? supported
                : null;
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

    @Override
    protected boolean readFromStream(RegistryFriendlyByteBuf data) {
        final boolean c = super.readFromStream(data);
        final boolean oldPower = this.isPowered;
        this.isPowered = data.readBoolean();
        return this.isPowered != oldPower || c;
    }

    @Override
    protected void writeToStream(RegistryFriendlyByteBuf data) {
        super.writeToStream(data);
        data.writeBoolean(this.isPowered);
    }

    /** True when the machine has an active grid channel (used to drive the border status light). */
    public boolean isPowered() {
        return isPowered;
    }

    @OnlyIn(Dist.CLIENT)
    public void setAnimationStatus(@org.jetbrains.annotations.Nullable AssemblerAnimationStatus status) {
        this.animationStatus = status;
    }

    @OnlyIn(Dist.CLIENT)
    @org.jetbrains.annotations.Nullable
    public AssemblerAnimationStatus getAnimationStatus() {
        return animationStatus;
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
                unit.planSource = ItemStack.EMPTY;
                unit.progress = 0;
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
     *
     * <p>The sparse 3x3 slot mapping is resolved through
     * {@link IMolecularAssemblerSupportedPattern#fillCraftingGrid}, because the compressed input list
     * returned by {@code IPatternDetails#getInputs()} does not expose recipe slot indices. Items are
     * extracted from the network before they are placed into the grid, so the grid never contains
     * items that were not actually removed from ME storage.</p>
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
        if (inputs.length == 0) {
            return;
        }

        // Availability probe per input: the table only has to be large enough for the sparse slots
        // the pattern maps onto it, so availability of a single item is checked and GRID_SIZE offered.
        var table = new KeyCounter[inputs.length];
        for (int i = 0; i < inputs.length; i++) {
            table[i] = new KeyCounter();
            var input = inputs[i];
            if (input == null) {
                continue;
            }
            for (var possible : input.getPossibleInputs()) {
                if (possible == null || !(possible.what() instanceof AEItemKey itemKey)) {
                    continue;
                }
                if (storage.extract(itemKey, 1, Actionable.SIMULATE, actionSource) > 0) {
                    table[i].add(itemKey, GRID_SIZE);
                    break;
                }
            }
        }

        // Resolve which sparse slot requires which item without touching the real grid first:
        // fillCraftingGrid writes each required input into its sparse slot, captured here.
        var target = new ItemStack[GRID_SIZE];
        plan.fillCraftingGrid(table, (slot, stack) -> {
            if (slot >= 0 && slot < GRID_SIZE) {
                target[slot] = stack;
            }
        });

        // Extract for real, but only for slots that are still empty.
        boolean changed = false;
        for (int slot = 0; slot < GRID_SIZE; slot++) {
            var stack = target[slot];
            if (stack == null || stack.isEmpty() || !unit.grid.getStackInSlot(slot).isEmpty()) {
                continue;
            }
            var key = AEItemKey.of(stack);
            if (key == null) {
                continue;
            }
            // A crafting grid slot holds exactly one item, even if a third-party pattern wrote a
            // larger stack into the probe table.
            long extracted = storage.extract(key, 1, Actionable.MODULATE, actionSource);
            if (extracted <= 0) {
                continue;
            }
            unit.grid.setItemDirect(slot, key.toStack(1));
            changed = true;
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
        // A unit running a manually inserted pattern does not take provider-pushed jobs, so the
        // machine only accepts new plans while at least one unit is completely idle.
        for (var unit : units) {
            if (unit.plan == null && unit.grid.isEmpty() && unit.patternInv.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public appeng.api.implementations.blockentities.PatternContainerGroup getCraftingMachineInfo() {
        return new appeng.api.implementations.blockentities.PatternContainerGroup(
                AEItemKey.of(AEPatternRegistries.ITEM_ASSEMBLER.get()),
                AEPatternRegistries.ITEM_ASSEMBLER.get().getDescription(),
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
            // The output slot is free again: move any grid item that is no longer valid for the
            // current pattern into it, so stale inputs cannot block the unit forever.
            ejectHeldItems(unit);
        }
        // Keep the plan in sync with the manual pattern slot: while the slot holds an item it is
        // authoritative, so a newly inserted or swapped pattern takes over immediately and a removed
        // pattern releases the grid (mirrors AE2's recalculatePlan).
        var slotPattern = unit.patternInv.getStackInSlot(0);
        if (slotPattern.isEmpty()) {
            if (!unit.planSource.isEmpty()) {
                unit.plan = null;
                unit.planSource = ItemStack.EMPTY;
                unit.progress = 0;
            }
            if (unit.plan == null) {
                return drainRemainders(unit);
            }
        } else if (unit.plan == null || !ItemStack.isSameItemSameComponents(slotPattern, unit.planSource)) {
            var supported = decodeManualPattern(unit);
            if (supported == null) {
                // The inserted pattern cannot be decoded: release the grid instead of stalling.
                unit.plan = null;
                unit.planSource = ItemStack.EMPTY;
                unit.progress = 0;
                return drainRemainders(unit);
            }
            unit.plan = supported;
            unit.planSource = slotPattern.copy();
            unit.progress = 0;
        }

        // Self-executing page guard: never accumulate progress unless the grid can actually be
        // assembled, so a material shortage can not consume progress or destroy partial inputs.
        if (!unit.patternInv.isEmpty() && !canAssemble(unit)) {
            // Drop anything the pattern no longer uses before topping the grid up from the network.
            ejectHeldItems(unit);
            tryFillGridFromNetwork(unit);
            if (!canAssemble(unit)) {
                return true;
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

                // Send animation packet to nearby players (visual parity with AE2 molecular assembler)
                var itemKey = AEItemKey.of(result);
                if (itemKey != null) {
                    PacketDistributor.sendToPlayersNear(
                            node.getLevel(), null,
                            worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                            32,
                            new AssemblerAnimationPayload(worldPosition, (byte) speed, itemKey));
                }

                // A successful craft consumes the input grid; put container remainders back into the
                // exact slots of the recipe bounding box inside the 3x3 grid.
                var remainders = plan.getRemainingItems(craftInput);
                for (int i = 0; i < GRID_SIZE; i++) {
                    unit.grid.setItemDirect(i, ItemStack.EMPTY);
                }
                for (int row = 0; row < craftInput.height(); row++) {
                    for (int col = 0; col < craftInput.width(); col++) {
                        var remainder = remainders.get(col + row * craftInput.width());
                        if (!remainder.isEmpty()) {
                            unit.grid.setItemDirect(col + positioned.left() + (row + positioned.top()) * 3, remainder);
                        }
                    }
                }
                // A manually inserted pattern stays in the unit and drives the next craft; a
                // provider-pushed plan is consumed with this craft.
                if (unit.planSource.isEmpty()) {
                    unit.plan = null;
                }
                ejectHeldItems(unit);
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

    /**
     * Moves one grid item that is no longer a valid input for the unit's current pattern into the
     * output slot (when free), so it can be pushed out instead of blocking the unit forever. Mirrors
     * AE2's ejection of stale crafting-grid items after pattern changes and craft completion.
     */
    private void ejectHeldItems(CraftUnit unit) {
        if (!unit.grid.getStackInSlot(OUTPUT_SLOT).isEmpty()) {
            return;
        }
        var plan = unit.plan;
        for (int i = 0; i < GRID_SIZE; i++) {
            var stack = unit.grid.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            var key = AEItemKey.of(stack);
            if (plan != null && key != null && plan.isItemValid(i, key, level)) {
                continue;
            }
            unit.grid.setItemDirect(OUTPUT_SLOT, stack);
            unit.grid.setItemDirect(i, ItemStack.EMPTY);
            saveChanges();
            return;
        }
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
        boolean newState = getMainNode() != null && getMainNode().isActive();
        if (newState != this.isPowered) {
            this.isPowered = newState;
            markForUpdate();
        }
    }

    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        saveChanges();
        // AE2 parks this device through TickRateModulation.SLEEP once no unit is busy and only resumes
        // it via the tick manager, so inventory changes (a pattern inserted into a page, grid items to
        // return) must nudge the ticker or the new work would never be picked up.
        alertTicker();
    }

    @Override
    public void saveChangedInventory(AppEngInternalInventory inv) {
        saveChanges();
    }

    private void alertTicker() {
        this.getMainNode().ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
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
