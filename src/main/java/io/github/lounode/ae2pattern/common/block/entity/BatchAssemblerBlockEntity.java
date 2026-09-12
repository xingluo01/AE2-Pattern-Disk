package io.github.lounode.ae2pattern.common.block.entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.StorageCell;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.UpgradeInventories;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.blockentity.inventory.AppEngCellInventory;
import appeng.core.definitions.AEItems;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.me.helpers.MachineSource;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import appeng.util.inv.filter.IAEItemFilter;

import io.github.lounode.ae2pattern.common.pattern.PatternDiskRemoveInventory;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.logic.BatchRecipePool;

/**
 * Block entity of the batch molecular assembler (批处理分子装配室).
 *
 * <p>It consumes crafting jobs pushed by AE2 crafting CPUs (via {@link ICraftingMachine}) and buffers
 * the pushed materials inside private storage-cell slots instead of executing immediately. Once no new
 * material has arrived for a short while, the buffered jobs are assembled in one batch - crafting table,
 * smithing table and stonecutting recipes are all supported - and the results (including container
 * remainders) are pushed back into the ME network, where the crafting CPU continues from there.</p>
 *
 * <p>The cell slots never join the ME network storage: this block entity deliberately implements neither
 * {@code IStorageProvider} nor the {@code ME_STORAGE} capability.</p>
 */
public class BatchAssemblerBlockEntity extends AENetworkedBlockEntity
        implements InternalInventoryHost, IUpgradeableObject, IGridTickable, ICraftingMachine, ICraftingProvider,
        IPatternDiskHost, PatternContainer {

    public static final int CELL_SLOTS = 9;
    public static final int DISK_SLOTS = 9;
    public static final int MAX_SPEED_CARDS = 4;

    /** Flush window: idle ticks after the last input that trigger a batch, regardless of queue size. */
    private static final int STANDARD_BATCH_TICKS = 40;
    private static final int FAST_BATCH_TICKS = 10;
    /** Per-thread per-tick budget while draining a batch (threads come from speed cards). Spreading the
     * storage IO over multiple ticks keeps the server from hitching on huge orders. */
    private static final int MAX_ASSEMBLIES_PER_TICK = 128;
    /** AE charged per assembled job (container remainders and parallels do not change this). */
    private static final double ENERGY_PER_RUN = 10.0;

    private final AppEngCellInventory cellInv = new AppEngCellInventory(this, CELL_SLOTS);
    private final StorageCell[] cells = new StorageCell[CELL_SLOTS];
    private final AppEngInternalInventory diskInv = new AppEngInternalInventory(this, DISK_SLOTS);
    private final IUpgradeInventory upgrades;
    private final IActionSource actionSource = new MachineSource(this);

    private final BatchRecipePool recipePool = new BatchRecipePool();
    private final List<IPatternDetails> exposedPatterns = new ArrayList<>();

    /** Pushed jobs still to assemble, keyed by pattern with the remaining execution count. */
    private final Map<IPatternDetails, Long> queue = new LinkedHashMap<>();

    private boolean cellsDirty = true;
    private int idleTicks = 0;
    /** Fast batch mode flushes after 10 idle ticks instead of the standard 40. */
    private boolean fastBatchMode = false;
    /** True while a triggered batch is still draining (queue not empty): keeps executing every tick
     * regardless of the idle window, and new pushes merge into the drain instead of resetting it. */
    private boolean draining = false;

    public BatchAssemblerBlockEntity(BlockPos pos, BlockState blockState) {
        super(AEPatternRegistries.BE_BATCH_ASSEMBLER.get(), pos, blockState);

        this.upgrades = UpgradeInventories.forMachine(AEPatternRegistries.BLOCK_BATCH_ASSEMBLER.get(),
                MAX_SPEED_CARDS, this::onUpgradesChanged);

        this.getMainNode()
                .setIdlePowerUsage(0)
                .setInWorldNode(true)
                .setExposedOnSides(java.util.Set.of(net.minecraft.core.Direction.values()))
                .addService(IGridTickable.class, this)
                .addService(ICraftingProvider.class, this);

        this.cellInv.setFilter(new IAEItemFilter() {
            @Override
            public boolean allowInsert(InternalInventory inv, int slot, ItemStack stack) {
                return !stack.isEmpty() && StorageCells.isCellHandled(stack);
            }

            @Override
            public boolean allowExtract(InternalInventory inv, int slot, int amount) {
                // Cells may only leave while nothing is buffered (anti-duplication guard). isBusy()
                // only means "cannot take more jobs" and is false during normal operation, so it must
                // not be used to guard item extraction.
                return !hasBufferedWork();
            }
        });
    }

    private void onUpgradesChanged() {
        saveChanges();
        alertTicker();
    }

    // ---- slots ---------------------------------------------------------------

    public InternalInventory getCellInventory() {
        return cellInv;
    }

    public AppEngInternalInventory getDiskInventory() {
        return diskInv;
    }

    /**
     * Rebuilds the cached {@link StorageCell} for every slot. The cached handler points at the live
     * {@link ItemStack} in the slot; it therefore has to be dropped whenever a cell enters or leaves a
     * slot, otherwise it would keep writing into a detached stack.
     */
    private void refreshCells() {
        for (int i = 0; i < CELL_SLOTS; i++) {
            cells[i] = null;
            cellInv.setHandler(i, null);
            var stack = cellInv.getStackInSlot(i); // also persists the previous cell content
            if (stack.isEmpty()) {
                continue;
            }
            var cell = StorageCells.getCellInventory(stack, null);
            if (cell != null) {
                cellInv.setHandler(i, cell);
                cells[i] = cell;
            }
        }
        cellsDirty = false;
    }

    private StorageCell[] resolvedCells() {
        if (cellsDirty) {
            refreshCells();
        }
        return cells;
    }

    /** Simulated/real insert across every cell slot; returns how much was accepted. */
    private long insertIntoCells(AEKey key, long amount, Actionable mode) {
        long remaining = amount;
        for (var cell : resolvedCells()) {
            if (cell == null || remaining <= 0) {
                continue;
            }
            long inserted = cell.insert(key, remaining, mode, actionSource);
            remaining -= inserted;
        }
        return amount - remaining;
    }

    /** Simulated/real extract across every cell slot; returns how much was taken. */
    private long extractFromCells(AEKey key, long amount, Actionable mode) {
        long remaining = amount;
        for (var cell : resolvedCells()) {
            if (cell == null || remaining <= 0) {
                continue;
            }
            long extracted = cell.extract(key, remaining, mode, actionSource);
            remaining -= extracted;
        }
        return amount - remaining;
    }

    // ---- recipe pool ---------------------------------------------------------

    /** Rebuilds the shared recipe pool from every inserted pattern disk and re-exposes it to the CPU. */
    public void refreshRecipePool() {
        var stacks = new ArrayList<ItemStack>();
        for (int i = 0; i < DISK_SLOTS; i++) {
            var disk = diskInv.getStackInSlot(i);
            if (!(disk.getItem() instanceof PatternDiskItem diskItem)) {
                continue;
            }
            var contents = diskItem.contents(disk);
            if (contents == null) {
                continue;
            }
            stacks.addAll(contents.patterns());
        }
        recipePool.rebuild(stacks, getLevel());

        exposedPatterns.clear();
        exposedPatterns.addAll(recipePool.all());
        if (getMainNode().getNode() != null) {
            ICraftingProvider.requestUpdate(getMainNode());
        }
    }

    // ---- ICraftingMachine (jobs pushed by AE2 pattern providers) -------------

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputs, net.minecraft.core.Direction ejectionDirection) {
        return acceptPush(patternDetails, inputs);
    }

    /**
     * Common intake for both push entry points (CPU direct push and adjacent pattern provider):
     * buffers the delivered inputs and books exactly one job per push. Job volume must never be
     * scaled here - the CPU will still deliver the full order one push at a time, so any
     * compensation would multiply the total output beyond the ordered amount.
     */
    private boolean acceptPush(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        if (!(patternDetails instanceof IMolecularAssemblerSupportedPattern)) {
            // Only crafting / smithing / stonecutting can be executed here. Accepting an AE processing
            // pattern would let the machine turn whatever was pushed in into the pattern's outputs.
            return false;
        }
        if (patternDetails.getInputs().length == 0) {
            // Defensive: a pattern without inputs would queue forever as an empty freebie.
            return false;
        }
        if (!acceptsPlans() || !bufferInputs(inputHolder)) {
            return false;
        }
        // Each push books exactly one job: the CPU delivers the full order one push at a time, so the
        // job volume must never be scaled here.
        queue.merge(patternDetails, 1L, Long::sum);
        // While draining, new pushes merge into the ongoing batch instead of resetting the idle window.
        if (!draining) {
            idleTicks = 0;
        }
        alertTicker();
        saveChanges();
        return true;
    }

    @Override
    public boolean acceptsPlans() {
        // Whether the buffer can actually take the material is decided by bufferInputs; require at
        // least one usable cell so jobs are never accepted without a place to store the inputs.
        for (var cell : resolvedCells()) {
            if (cell != null) {
                return true;
            }
        }
        return false;
    }

    @Override
    public PatternContainerGroup getCraftingMachineInfo() {
        return PatternContainerGroup.nothing();
    }

    // ---- ICraftingProvider (patterns exposed to the ME autocrafting service) --

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        return exposedPatterns;
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        return acceptPush(patternDetails, inputHolder);
    }

    /** True while the queue has reached the user-configured limit (0 = no limit). */
    @Override
    public boolean isBusy() {
        // "Busy" makes the crafting CPU stop offering jobs to this provider, so it must only be true when
        // the buffer genuinely cannot take more - otherwise batching could never accumulate. There is no
        // queue-size limit: the cell buffer's own capacity is the natural bound.
        return !acceptsPlans();
    }

    /** True while buffered jobs are waiting to be assembled (drives the slot locks). */
    public boolean hasBufferedWork() {
        return queuedJobs() > 0;
    }

    private long queuedJobs() {
        long total = 0;
        for (var count : queue.values()) {
            total += count;
        }
        return total;
    }

    /**
     * Moves every pushed amount into the cell buffer, all-or-nothing. Cell capacity is shared across
     * keys (total bytes + bytes per type), so per-key simulation can over-estimate: the insert is
     * verified for real and anything short is rolled back before the push is rejected.
     */
    private boolean bufferInputs(KeyCounter[] inputs) {
        var insertedKeys = new ArrayList<AEKey>();
        var insertedAmounts = new ArrayList<Long>();
        boolean complete = true;

        for (var counter : inputs) {
            for (var entry : counter) {
                long amount = entry.getLongValue();
                if (amount <= 0) {
                    continue;
                }
                var key = entry.getKey();
                long accepted = insertIntoCells(key, amount, Actionable.MODULATE);
                insertedKeys.add(key);
                insertedAmounts.add(accepted);
                if (accepted < amount) {
                    complete = false;
                    break;
                }
            }
            if (!complete) {
                break;
            }
        }

        if (!complete) {
            for (int i = 0; i < insertedKeys.size(); i++) {
                long accepted = insertedAmounts.get(i);
                if (accepted > 0) {
                    extractFromCells(insertedKeys.get(i), accepted, Actionable.MODULATE);
                }
            }
            return false;
        }

        for (var counter : inputs) {
            counter.removeZeros();
        }
        return true;
    }

    // ---- ticking / batch execution ------------------------------------------

    @Override
    public TickingRequest getTickingRequest(IGridNode node) {
        return new TickingRequest(1, 1, false);
    }

    @Override
    public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
        if (queue.isEmpty()) {
            return TickRateModulation.SLEEP;
        }
        // The tick manager reports the whole sleep gap on the first tick after waking, so clamp it: the
        // idle window must measure time since the last pushed input, not time since the last tick.
        idleTicks += Math.min(ticksSinceLastCall, 1);
        boolean batchReady = draining || idleTicks >= batchIdleTicks();
        if (!batchReady) {
            return TickRateModulation.FASTER;
        }
        boolean worked = runBatch();
        return worked ? TickRateModulation.IDLE : TickRateModulation.SLOWER;
    }

    /**
     * Assembles a batch from the cell buffer. Inputs are consumed only through the cells and each
     * pattern's outputs are returned to the ME network, so the crafting CPU's waiting list always gets
     * satisfied and a job is either assembled or left untouched for the next batch.
     */
    private boolean runBatch() {
        boolean worked = false;
        // Worker threads: 1 by default, x2 per speed card (max 4 cards = 16 threads). Each thread owns
        // one pattern's drain: a single-pattern order stays single-threaded, while chained orders
        // (multiple patterns queued) advance their layers in parallel. Per-tick totals stay bounded so
        // the storage IO of one tick cannot hitch the server.
        int threads = 1 << upgrades.getInstalledUpgrades(appeng.core.definitions.AEItems.SPEED_CARD);
        int budgetPerThread = MAX_ASSEMBLIES_PER_TICK;
        int totalBudget = budgetPerThread * threads;

        var it = queue.entrySet().iterator();
        while (it.hasNext() && totalBudget > 0) {
            var entry = it.next();
            var pattern = entry.getKey();
            long remaining = entry.getValue();

            if (!hasInputs(pattern)) {
                // Nothing to consume for this job yet: skip it before charging power so an unsatisfiable
                // job cannot drain the grid every tick.
                continue;
            }

            int patternBudget = budgetPerThread;
            while (remaining > 0 && patternBudget > 0 && totalBudget > 0) {
                if (!assembleOnce(pattern)) {
                    break;
                }
                remaining--;
                patternBudget--;
                totalBudget--;
                worked = true;
            }

            if (remaining <= 0) {
                it.remove();
            } else {
                entry.setValue(remaining);
            }
        }

        if (worked) {
            // Keep draining while the queue is not empty: stay batch-ready so the next tick continues
            // without waiting for another idle window; only a fully drained queue rearms the timer.
            draining = !queue.isEmpty();
            idleTicks = draining ? Math.max(idleTicks, batchIdleTicks()) : 0;
            saveChanges();
        }
        return worked;
    }

    /**
     * Executes one pattern from the cell buffer: inputs are consumed first, then the whole output set
     * (main outputs + container remainders) is pushed to the ME network. When the network cannot take
     * the output set, the consumed inputs are rolled back into the cell buffer and the run is deferred
     * - outputs are never parked in the cell buffer. Internal chaining is deliberately not performed -
     * see {@link BatchRecipePool}.
     */
    private boolean assembleOnce(IPatternDetails pattern) {
        if (!hasInputs(pattern)) {
            return false;
        }
        var consumed = consumeInputs(pattern);
        if (consumed == null) {
            return false;
        }

        // Collect every produced key (main outputs + container remainders) before touching the network.
        var outputs = new LinkedHashMap<AEKey, Long>();
        for (var output : pattern.getOutputs()) {
            if (output.what() != null && output.amount() > 0) {
                outputs.merge(output.what(), output.amount(), Long::sum);
            }
        }
        var inputs = pattern.getInputs();
        for (int i = 0; i < inputs.length; i++) {
            var input = inputs[i];
            if (input == null || input.getPossibleInputs().length == 0) {
                continue;
            }
            // Container remainders are computed against the variant actually consumed (the inputs may
            // accept substitutes whose containers differ).
            var usedKey = consumed.usedByInput().get(i);
            if (usedKey == null) {
                continue;
            }
            // Buckets, bottles and other containers must be handed back, otherwise they vanish and the
            // crafting CPU keeps waiting for its expected container items forever.
            var remaining = input.getRemainingKey(usedKey);
            long consumedCount = input.getMultiplier();
            if (remaining != null && consumedCount > 0) {
                // AE2 books one remaining item per consumed template, i.e. one per occupied slot.
                outputs.merge(remaining, consumedCount, Long::sum);
            }
        }

        // Defer (keeping the inputs in the cell buffer) whenever the network cannot take everything.
        if (!canNetworkAcceptAll(outputs)) {
            rollbackInputs(consumed.byKey());
            return false;
        }
        if (!consumePower()) {
            rollbackInputs(consumed.byKey());
            return false;
        }
        // Commit the output set. The per-key simulation above is optimistic (the network's storage
        // shares one byte pool across types), so any partial failure rolls the already-pushed part
        // back out of the network - same tick, exact amounts - and defers the whole run.
        long leftoverTotal = 0;
        var pushed = new LinkedHashMap<AEKey, Long>();
        for (var entry : outputs.entrySet()) {
            long leftover = pushToNetwork(entry.getKey(), entry.getValue());
            pushed.put(entry.getKey(), entry.getValue() - leftover);
            leftoverTotal += leftover;
        }
        if (leftoverTotal > 0) {
            var storage = getMainNode().getGrid().getStorageService().getInventory();
            for (var entry : pushed.entrySet()) {
                if (entry.getValue() > 0) {
                    storage.extract(entry.getKey(), entry.getValue(), Actionable.MODULATE, actionSource);
                }
            }
            rollbackInputs(consumed.byKey());
            return false;
        }
        return true;
    }

    /**
     * Charges the grid for one assembly run. Energy is charged per run, not per parallel item, matching
     * the design rule that parallel work does not add extra consumption.
     */
    private boolean consumePower() {
        var node = getMainNode().getNode();
        if (node == null || node.getGrid() == null) {
            return false;
        }
        var energy = node.getGrid().getEnergyService();
        if (energy == null) {
            return false;
        }
        if (energy.extractAEPower(ENERGY_PER_RUN, Actionable.SIMULATE,
                PowerMultiplier.CONFIG) < ENERGY_PER_RUN - 0.01) {
            return false;
        }
        return energy.extractAEPower(ENERGY_PER_RUN, Actionable.MODULATE, PowerMultiplier.CONFIG) > 0;
    }

    private boolean hasInputs(IPatternDetails pattern) {
        for (var input : pattern.getInputs()) {
            if (input == null) {
                continue;
            }
            long needed = input.getMultiplier();
            if (!canSupply(input, needed)) {
                return false;
            }
        }
        return true;
    }

    private boolean canSupply(IPatternDetails.IInput input, long needed) {
        for (var possible : input.getPossibleInputs()) {
            if (possible == null || possible.what() == null) {
                continue;
            }
            if (extractFromCells(possible.what(), needed, Actionable.SIMULATE) >= needed) {
                return true;
            }
        }
        return false;
    }

    /**
     * Consumes the inputs for one execution from the cell buffer. Returns the per-key totals (for
     * rollback) plus the variant actually consumed per input index (containers may differ), or
     * {@code null} when a required input is missing.
     */
    private Consumption consumeInputs(IPatternDetails pattern) {
        var byKey = new LinkedHashMap<AEKey, Long>();
        var usedByInput = new ArrayList<AEKey>();
        for (var input : pattern.getInputs()) {
            if (input == null) {
                usedByInput.add(null);
                continue;
            }
            var usedKey = consumeOne(input, input.getMultiplier());
            if (usedKey == null) {
                rollbackInputs(byKey);
                return null;
            }
            byKey.merge(usedKey, input.getMultiplier(), Long::sum);
            usedByInput.add(usedKey);
        }
        return new Consumption(byKey, usedByInput);
    }

    /** Per-run consumption record: {@code byKey} drives rollbacks, {@code usedByInput} drives container remainders. */
    private record Consumption(LinkedHashMap<AEKey, Long> byKey, List<AEKey> usedByInput) {
    }

    /** Extracts one input's requirement from the cells; returns the key actually consumed, or null. */
    private AEKey consumeOne(IPatternDetails.IInput input, long needed) {
        for (var possible : input.getPossibleInputs()) {
            if (possible == null || possible.what() == null) {
                continue;
            }
            if (extractFromCells(possible.what(), needed, Actionable.SIMULATE) >= needed) {
                extractFromCells(possible.what(), needed, Actionable.MODULATE);
                return possible.what();
            }
        }
        return null;
    }

    /** Puts rolled-back inputs (and only those) back into the cell buffer they came from. */
    private void rollbackInputs(LinkedHashMap<AEKey, Long> consumed) {
        for (var entry : consumed.entrySet()) {
            insertIntoCells(entry.getKey(), entry.getValue(), Actionable.MODULATE);
        }
    }

    /** Simulates whether the ME network can take the whole output set in one go. */
    private boolean canNetworkAcceptAll(LinkedHashMap<AEKey, Long> outputs) {
        var node = getMainNode().getNode();
        if (node == null || node.getGrid() == null) {
            return false;
        }
        var storage = node.getGrid().getStorageService().getInventory();
        for (var entry : outputs.entrySet()) {
            if (storage.insert(entry.getKey(), entry.getValue(), Actionable.SIMULATE,
                    actionSource) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Pushes a produced key to the ME network; returns the amount that did not fit (the caller rolls
     * the whole output set back on any leftover).
     */
    private long pushToNetwork(AEKey key, long amount) {
        var node = getMainNode().getNode();
        if (node == null || node.getGrid() == null) {
            // Without a grid nothing can be delivered: report the full amount as leftover.
            return amount;
        }
        var storage = node.getGrid().getStorageService().getInventory();
        long inserted = storage.insert(key, amount, Actionable.MODULATE, actionSource);
        return amount - inserted;
    }

    /** Empties the cell buffer back into the ME network (used by the "cancel crafting" action). */
    public void cancelAndReturnContents() {
        queue.clear();
        // Reset the drain state: the queue is gone, so the next push must go through a fresh idle window.
        draining = false;
        idleTicks = 0;
        for (var cell : resolvedCells()) {
            if (cell == null) {
                continue;
            }
            var contents = new KeyCounter();
            cell.getAvailableStacks(contents);
            for (var entry : contents) {
                long amount = entry.getLongValue();
                if (amount <= 0) {
                    continue;
                }
                long taken = cell.extract(entry.getKey(), amount, Actionable.MODULATE, actionSource);
                if (taken > 0) {
                    pushToNetwork(entry.getKey(), taken);
                }
            }
        }
        saveChanges();
        alertTicker();
    }

    private void alertTicker() {
        this.getMainNode().ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
    }

    /** Idle ticks that flush a batch for the currently selected mode. */
    public int batchIdleTicks() {
        return fastBatchMode ? FAST_BATCH_TICKS : STANDARD_BATCH_TICKS;
    }

    public boolean isFastBatchMode() {
        return fastBatchMode;
    }

    /** Switches between the standard (40 tick) and fast (10 tick) batch delay. */
    public void setFastBatchMode(boolean fastBatchMode) {
        this.fastBatchMode = fastBatchMode;
        // Restart the idle window so the new threshold is measured from now.
        this.idleTicks = 0;
        saveChanges();
        alertTicker();
    }

    // ---- inventory host ------------------------------------------------------

    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        if (inv == diskInv) {
            // Only disk changes alter the recipe pool; cell content changes on every buffered input and
            // must not trigger a network-wide pattern index refresh.
            refreshRecipePool();
            cachedTerminalInventory = null; // invalidate the pattern access terminal view
        } else {
            cellsDirty = true;
        }
        saveChanges();
        alertTicker();
    }

    // ---- pattern access terminal (PatternContainer) -------------------------

    /** Cached terminal view over the disk contents; invalidated whenever the disks change. */
    private PatternDiskRemoveInventory cachedTerminalInventory;

    @Override
    public IGrid getGrid() {
        return getMainNode().getGrid();
    }

    @Override
    public InternalInventory getTerminalPatternInventory() {
        if (cachedTerminalInventory != null) {
            return cachedTerminalInventory;
        }
        cachedTerminalInventory = new PatternDiskRemoveInventory(diskInv,
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

    @Override
    public PatternContainerGroup getTerminalGroup() {
        // getName() falls back to the block entity's visual item, which is empty for this machine and
        // renders as "Air" in the pattern access terminal - prefer an anvil custom name if present,
        // otherwise the block's display name (matching the pattern provider's terminal behaviour).
        var name = hasCustomName() ? getCustomName() : getBlockState().getBlock().getName();
        return new PatternContainerGroup(
                AEItemKey.of(AEPatternRegistries.ITEM_BATCH_ASSEMBLER.get()),
                name,
                List.of());
    }

    /** Read-only pre-check: whether the ME network holds at least {@code count} blank patterns. */
    private boolean canDrawBlankPattern(int count) {
        var grid = getMainNode().getGrid();
        if (grid == null || count <= 0) {
            return false;
        }
        var storage = grid.getStorageService().getInventory();
        var blank = AEItemKey.of(AEItems.BLANK_PATTERN);
        return storage.extract(blank, count, Actionable.SIMULATE, actionSource) == count;
    }

    /** Draws {@code count} blank patterns from the ME network, all-or-nothing. */
    private boolean tryDrawBlankPattern(int count) {
        if (!canDrawBlankPattern(count)) {
            return false;
        }
        var grid = getMainNode().getGrid();
        var storage = grid.getStorageService().getInventory();
        var blank = AEItemKey.of(AEItems.BLANK_PATTERN);
        return storage.extract(blank, count, Actionable.MODULATE, actionSource) == count;
    }

    /** Returns {@code count} blank patterns to the ME network (undo of a swap restore). */
    private boolean returnBlankPattern(int count) {
        var grid = getMainNode().getGrid();
        if (grid == null || count <= 0) {
            return false;
        }
        var storage = grid.getStorageService().getInventory();
        var blank = AEItemKey.of(AEItems.BLANK_PATTERN);
        return storage.insert(blank, count, Actionable.MODULATE, actionSource) == count;
    }

    private void markTerminalChanged() {
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
    public IUpgradeInventory getUpgrades() {
        return upgrades;
    }

    // ---- persistence ---------------------------------------------------------

    @Override
    public void onLoad() {
        super.onLoad();
        refreshRecipePool();
    }

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        for (int i = 0; i < CELL_SLOTS; i++) {
            // getStackInSlot persists the cached cell content into the item before saving.
            tag.put("cell" + i, cellInv.getStackInSlot(i).saveOptional(registries));
        }
        diskInv.writeToNBT(tag, "disks", registries);
        upgrades.writeToNBT(tag, "upgrades", registries);
        tag.putBoolean("fastBatchMode", fastBatchMode);
    }

    @Override
    public void loadTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadTag(tag, registries);
        for (int i = 0; i < CELL_SLOTS; i++) {
            cellInv.setItemDirect(i, ItemStack.parseOptional(registries, tag.getCompound("cell" + i)));
        }
        diskInv.readFromNBT(tag, "disks", registries);
        upgrades.readFromNBT(tag, "upgrades", registries);
        fastBatchMode = tag.getBoolean("fastBatchMode");
        cellsDirty = true;
        refreshRecipePool();
    }

    @Override
    public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        // Anything still buffered belongs to jobs the CPU already accounts for; hand it back before the
        // cells (and therefore their contents) leave the machine.
        if (hasBufferedWork()) {
            cancelAndReturnContents();
        }
        super.addAdditionalDrops(level, pos, drops);
        for (int i = 0; i < CELL_SLOTS; i++) {
            var cell = cellInv.getStackInSlot(i);
            if (!cell.isEmpty()) {
                drops.add(cell);
            }
        }
        for (int i = 0; i < DISK_SLOTS; i++) {
            var disk = diskInv.getStackInSlot(i);
            if (!disk.isEmpty()) {
                drops.add(disk);
            }
        }
        for (int i = 0; i < upgrades.size(); i++) {
            var upgrade = upgrades.getStackInSlot(i);
            if (!upgrade.isEmpty()) {
                drops.add(upgrade);
            }
        }
    }

    @Override
    public void clearContent() {
        super.clearContent();
        for (int i = 0; i < CELL_SLOTS; i++) {
            cellInv.setItemDirect(i, ItemStack.EMPTY);
        }
        diskInv.clear();
        upgrades.clear();
        queue.clear();
        cellsDirty = true;
        refreshCells();
    }
}
